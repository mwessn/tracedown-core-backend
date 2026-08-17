package dev.tracedown.common.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TokenHasherTest {

    @Test
    fun `hash is deterministic 64-char hex and not the input`() {
        val token = "abc123-a-secret-bearer-token"
        val a = TokenHasher.sha256Hex(token)
        val b = TokenHasher.sha256Hex(token)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertEquals(true, a.all { it in "0123456789abcdef" })
        // The stored value must not be the raw token.
        assertNotEquals(token, a)
    }

    @Test
    fun `distinct tokens hash differently and matches a known vector`() {
        assertNotEquals(TokenHasher.sha256Hex("x"), TokenHasher.sha256Hex("y"))
        // SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TokenHasher.sha256Hex("abc"),
        )
    }
}
