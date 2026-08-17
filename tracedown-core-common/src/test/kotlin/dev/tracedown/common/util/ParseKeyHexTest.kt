package dev.tracedown.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the always-on hardening of [VariableCryptoEngine.parseKeyHex]:
 * exactly 64 hex chars (AES-256) are accepted; anything shorter or non-hex is
 * rejected loudly rather than silently zero-padded into a weak key.
 */
class ParseKeyHexTest {

    @Test
    fun `accepts the all-zero dev default (64 hex)`() {
        val key = VariableCryptoEngine.parseKeyHex("0".repeat(64))
        assertEquals(32, key.size)
        assertContentEquals(ByteArray(32), key)
    }

    @Test
    fun `accepts a real 64-hex key (upper and lower case)`() {
        val hex = "00112233445566778899AABBCCDDEEFF00112233445566778899aabbccddeeff"
        assertEquals(32, VariableCryptoEngine.parseKeyHex(hex).size)
    }

    @Test
    fun `rejects a short key rather than zero-padding it`() {
        assertFailsWith<IllegalArgumentException> { VariableCryptoEngine.parseKeyHex("00112233") }
    }

    @Test
    fun `rejects an empty key`() {
        assertFailsWith<IllegalArgumentException> { VariableCryptoEngine.parseKeyHex("") }
    }

    @Test
    fun `rejects a 64-char non-hex key`() {
        assertFailsWith<IllegalArgumentException> { VariableCryptoEngine.parseKeyHex("z".repeat(64)) }
    }

    @Test
    fun `rejects an over-length key`() {
        assertFailsWith<IllegalArgumentException> { VariableCryptoEngine.parseKeyHex("0".repeat(66)) }
    }
}
