package dev.tracedown.gateway.util

import dev.tracedown.common.util.VariableCryptoEngine
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The event feed's cursor: a position in the outbox — the writing
 * transaction and the row, `(xid, seq)` — sealed for one organization.
 *
 * Sealed so it says nothing and works nowhere else: the position is a count of
 * everything every organization has done, which is not one organization's to
 * read, and a cursor carried to another organization's key must not be taken
 * for a position there. AES-256-GCM under a key derived from the platform
 * key, the organization as associated data, and the nonce derived from the
 * organization and the position — so the same position gives the same cursor
 * every time (it is deterministic on purpose, and the only thing a repeat
 * reveals is the equality of two positions of one organization).
 *
 * `ev2.` marks this form. `ev1:` cursors, the bare `seq` an earlier build of
 * this branch handed out, are still read ([decode] returns them as
 * [Position.Legacy]).
 */
object EventCursor {

    /** A position the cursor names. */
    sealed interface Position {
        /** Read on from the row after `(xid, seq)`. */
        data class At(val xid: Long, val seq: Long) : Position, Comparable<At> {
            override fun compareTo(other: At): Int = compareValuesBy(this, other, At::xid, At::seq)
        }

        /** A bare `seq`, from a cursor of the first form. */
        data class Legacy(val seq: Long) : Position
    }

    private const val PREFIX = "ev2."
    private const val LEGACY_PREFIX = "ev1:"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile
    private var key: ByteArray? = null

    /** Derives the cursor key from the platform key. Call once at startup. */
    fun init(platformKeyHex: String) {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(VariableCryptoEngine.parseKeyHex(platformKeyHex), "HmacSHA256"))
        key = mac.doFinal("tracedown event feed cursor".toByteArray())
    }

    private fun key(): ByteArray = key ?: error("EventCursor.init was not called")

    /** The cursor for [position] in [orgId]. */
    fun encode(orgId: UUID, position: Position.At): String {
        val plain = ByteBuffer.allocate(16).putLong(position.xid).putLong(position.seq).array()
        val aad = uuidBytes(orgId)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(), "HmacSHA256"))
        mac.update(aad)
        val nonce = mac.doFinal(plain).copyOf(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + sealed)
    }

    /** The position [cursor] names in [orgId], or null when it is not a cursor of that organization. */
    fun decode(orgId: UUID, cursor: String): Position? {
        if (cursor.startsWith(PREFIX)) {
            return runCatching {
                val bytes = Base64.getUrlDecoder().decode(cursor.removePrefix(PREFIX))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"),
                    GCMParameterSpec(TAG_BITS, bytes.copyOf(NONCE_BYTES)),
                )
                cipher.updateAAD(uuidBytes(orgId))
                val plain = ByteBuffer.wrap(cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size)))
                Position.At(plain.long, plain.long)
            }.getOrNull()
        }
        return runCatching {
            val text = String(Base64.getUrlDecoder().decode(cursor))
            text.removePrefix(LEGACY_PREFIX).takeIf { text.startsWith(LEGACY_PREFIX) }?.toLong()
                ?.takeIf { it >= 0 }?.let { Position.Legacy(it) }
        }.getOrNull()
    }

    private fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
}
