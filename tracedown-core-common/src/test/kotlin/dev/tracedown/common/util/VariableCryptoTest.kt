package dev.tracedown.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for the envelope-encryption engine, run against an in-memory
 * DEK store (the DB-backed store is covered by the module integration tests).
 */
class VariableCryptoTest {

    /** Engine with the org_encryption_keys access points replaced by a map. */
    private class InMemoryEngine(
        kekHex: String,
        cacheTtlMs: Long = 5 * 60 * 1000L,
    ) : VariableCryptoEngine(parseKeyHex(kekHex), dekCacheTtlMs = cacheTtlMs) {
        val store = ConcurrentHashMap<UUID, String>()
        override fun loadWrappedDek(orgId: UUID): String? = store[orgId]
        override fun mintWrappedDek(orgId: UUID): String = store.getOrPut(orgId) { wrapNewDek(orgId) }
    }

    private val kekA = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val kekB = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    private val orgId: UUID = UUID.randomUUID()

    // ── Envelope round trip + format ──

    @Test
    fun `envelope round trip decrypts to the original plaintext`() {
        val engine = InMemoryEngine(kekA)
        val stored = engine.encryptSecret(orgId, "s3cret-value", "org", "apiKey")

        assertTrue(stored.startsWith("v2:"), "envelope values carry the version prefix")
        assertTrue(engine.isEnvelope(stored))
        assertEquals("s3cret-value", engine.decryptValue(orgId, stored, null, "org", "apiKey"))
    }

    @Test
    fun `minting is idempotent and ciphertexts differ per encryption`() {
        val engine = InMemoryEngine(kekA)
        val a = engine.encryptSecret(orgId, "same", "org", "k")
        val b = engine.encryptSecret(orgId, "same", "org", "k")
        assertEquals(1, engine.store.size, "one DEK per org")
        assertTrue(a != b, "fresh IV per encryption")
        assertEquals("same", engine.decryptValue(orgId, b, null, "org", "k"))
    }

    // ── AAD binding ──

    @Test
    fun `decrypt fails when the variable key does not match`() {
        val engine = InMemoryEngine(kekA)
        val stored = engine.encryptSecret(orgId, "v", "org", "apiKey")
        assertFailsWith<Exception> {
            engine.decryptValue(orgId, stored, null, "org", "otherKey")
        }
    }

    @Test
    fun `decrypt fails when the scope does not match`() {
        val engine = InMemoryEngine(kekA)
        val stored = engine.encryptSecret(orgId, "v", "workspace", "apiKey")
        assertFailsWith<Exception> {
            engine.decryptValue(orgId, stored, null, "service", "apiKey")
        }
    }

    @Test
    fun `decrypt fails under a different org even when that org has its own DEK`() {
        val engine = InMemoryEngine(kekA)
        val otherOrg = UUID.randomUUID()
        val stored = engine.encryptSecret(orgId, "v", "org", "apiKey")
        engine.mintOrgKey(otherOrg)
        assertFailsWith<Exception> {
            engine.decryptValue(otherOrg, stored, null, "org", "apiKey")
        }
    }

    // ── Legacy fallback ──

    @Test
    fun `unprefixed ciphertexts fall back to the legacy platform-key path`() {
        val engine = InMemoryEngine(kekA)
        val (enc, iv) = engine.encryptLegacy("legacy-value")

        assertFalse(engine.isEnvelope(enc), "legacy base64 can never look like an envelope")
        // Universal decrypt handles it without any org DEK existing.
        assertEquals("legacy-value", engine.decryptValue(orgId, enc, iv, "org", "whatever"))
        assertTrue(engine.store.isEmpty(), "legacy path never touches the DEK store")
    }

    @Test
    fun `legacy decrypt without an IV fails loudly`() {
        val engine = InMemoryEngine(kekA)
        val (enc, _) = engine.encryptLegacy("legacy-value")
        assertFailsWith<IllegalStateException> {
            engine.decryptValue(orgId, enc, null, "org", "k")
        }
    }

    // ── Crypto-shredding ──

    @Test
    fun `deleting the DEK makes the ciphertext unrecoverable`() {
        val engine = InMemoryEngine(kekA, cacheTtlMs = -1) // no caching: shredding is immediate
        val stored = engine.encryptSecret(orgId, "v", "org", "apiKey")

        engine.store.clear() // the crypto-shred

        assertFailsWith<IllegalStateException> {
            engine.decryptValue(orgId, stored, null, "org", "apiKey")
        }
    }

    // ── KEK re-wrap (rotation groundwork) ──

    @Test
    fun `a DEK re-wrapped to a new KEK still decrypts old ciphertexts`() {
        val oldEngine = InMemoryEngine(kekA)
        val stored = oldEngine.encryptSecret(orgId, "survives-rotation", "org", "apiKey")
        val wrappedOld = oldEngine.store[orgId]!!

        val newEngine = InMemoryEngine(kekB)
        val oldKek = SecretKeySpec(VariableCryptoEngine.parseKeyHex(kekA).copyOf(32), "AES")
        val rewrapped = newEngine.rewrapWrappedDek(orgId, wrappedOld, oldKek)!!
        newEngine.store[orgId] = rewrapped

        assertEquals("survives-rotation", newEngine.decryptValue(orgId, stored, null, "org", "apiKey"))
    }

    @Test
    fun `re-wrap skips a DEK already wrapped with the current KEK`() {
        val engine = InMemoryEngine(kekA)
        engine.mintOrgKey(orgId)
        val wrapped = engine.store[orgId]!!
        val oldKek = SecretKeySpec(VariableCryptoEngine.parseKeyHex(kekB).copyOf(32), "AES")
        assertNull(engine.rewrapWrappedDek(orgId, wrapped, oldKek), "already-current rows are skipped")
    }

    // ── Static facade ──

    @Test
    fun `facade dispatches both formats through one decrypt entry point`() {
        val engine = InMemoryEngine(kekA)
        VariableCrypto.init(engine)

        val envelope = VariableCrypto.encrypt(orgId, "secret", "org", "k")
        val (legacyEnc, legacyIv) = VariableCrypto.encrypt("variable")

        assertEquals("secret", VariableCrypto.decrypt(orgId, envelope, null, "org", "k"))
        assertEquals("variable", VariableCrypto.decrypt(orgId, legacyEnc, legacyIv, "org", "k"))
        assertEquals("variable", VariableCrypto.decrypt(legacyEnc, legacyIv))
    }
}
