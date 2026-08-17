package dev.tracedown.common.auth

import java.security.MessageDigest

/**
 * One-way hashing for opaque bearer tokens that are looked up by exact value
 * (session tokens, and any future equivalents).
 *
 * A SHA-256 of the raw token is stored at rest; lookup hashes the presented
 * token and matches on the digest. The tokens are already 256 bits of
 * [java.security.SecureRandom] output, so they are not brute-forceable — a
 * plain fast hash (not bcrypt) is the right primitive here, and it keeps the
 * validation path a single indexed equality lookup. The point is that a DB or
 * backup read never yields a live token.
 */
object TokenHasher {

    /** Lowercase hex SHA-256 of [token]. Stable across processes and platforms. */
    fun sha256Hex(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
