package dev.tracedown.common.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the [Validators] primitives every request DTO composes.
 *
 * The invariant that matters most is the length boundary: a value of exactly the
 * cap must pass (so `maxLen(N)` mirrors `varchar(N)` and never rejects a value the
 * column would accept), while `N+1` must fail (so nothing that overflows the column
 * can slip past). The format checks assert the returned error *code* too, since the
 * frontend maps those codes to messages.
 */
class ValidatorsTest {

    @Test
    fun `notBlank rejects null and blank, accepts content`() {
        assertEquals("name_required", Validators.notBlank("name", null))
        assertEquals("name_required", Validators.notBlank("name", ""))
        assertEquals("name_required", Validators.notBlank("name", "   "))
        assertNull(Validators.notBlank("name", "ok"))
    }

    @Test
    fun `maxLen passes at the cap and fails one over`() {
        assertNull(Validators.maxLen("name", "a".repeat(128), 128), "exactly the cap is allowed")
        assertEquals("name_too_long", Validators.maxLen("name", "a".repeat(129), 128))
        assertNull(Validators.maxLen("name", null, 128), "null is not this check's concern")
    }

    @Test
    fun `minLen enforces a floor on present values`() {
        assertEquals("password_too_short", Validators.minLen("password", "short", 8))
        assertNull(Validators.minLen("password", "longenough", 8))
        assertNull(Validators.minLen("password", null, 8), "null passes; use notBlank for required")
    }

    @Test
    fun `email accepts plausible addresses and rejects junk`() {
        assertNull(Validators.email("email", "a@b.co"))
        assertNull(Validators.email("email", "  spaced@example.com  "), "surrounding space is trimmed")
        assertEquals("invalid_email", Validators.email("email", "no-at-sign"))
        assertEquals("invalid_email", Validators.email("email", "a@b"))
        assertNull(Validators.email("email", null), "null passes; pair with notBlank when required")
    }

    @Test
    fun `countryCode requires ISO alpha-2`() {
        assertNull(Validators.countryCode("country", "DE"))
        assertNull(Validators.countryCode("country", "de"), "case is not enforced here")
        assertEquals("invalid_country", Validators.countryCode("country", "DEU"))
        assertEquals("invalid_country", Validators.countryCode("country", "D1"))
    }

    @Test
    fun `uuid accepts canonical form and rejects the rest`() {
        assertNull(Validators.uuid("orgId", "3f6b0e2a-9c1d-4b7e-8a2f-1c2d3e4f5a6b"))
        assertNull(Validators.uuid("orgId", "3F6B0E2A-9C1D-4B7E-8A2F-1C2D3E4F5A6B"), "case-insensitive")
        assertEquals("invalid_orgId", Validators.uuid("orgId", "not-a-uuid"))
        assertEquals("invalid_orgId", Validators.uuid("orgId", "3f6b0e2a9c1d4b7e8a2f1c2d3e4f5a6b"), "hyphens required")
    }

    @Test
    fun `pattern applies the supplied regex`() {
        val sixDigits = Regex("^[0-9]{6}$")
        assertNull(Validators.pattern("code", "123456", sixDigits))
        assertEquals("invalid_code", Validators.pattern("code", "12345", sixDigits))
        assertEquals("invalid_code", Validators.pattern("code", "abcdef", sixDigits))
    }

    @Test
    fun `oneOf restricts to the allowed set`() {
        val allowed = setOf("http-01", "dns-01")
        assertNull(Validators.oneOf("verificationType", "dns-01", allowed))
        assertEquals("invalid_verificationType", Validators.oneOf("verificationType", "tls-01", allowed))
    }

    @Test
    fun `inRange bounds a present integer`() {
        assertNull(Validators.inRange("expiresInDays", 30, 1..3650))
        assertNull(Validators.inRange("expiresInDays", 1, 1..3650), "inclusive lower bound")
        assertNull(Validators.inRange("expiresInDays", 3650, 1..3650), "inclusive upper bound")
        assertEquals("invalid_expiresInDays", Validators.inRange("expiresInDays", 0, 1..3650))
        assertEquals("invalid_expiresInDays", Validators.inRange("expiresInDays", 3651, 1..3650))
        assertNull(Validators.inRange("expiresInDays", null, 1..3650), "null passes")
    }

    @Test
    fun `each reports the first failing element and ignores null lists`() {
        val check = { s: String -> Validators.maxLen("tag", s, 3) }
        assertNull(Validators.each(listOf("ok", "yes"), check))
        assertEquals("tag_too_long", Validators.each(listOf("ok", "toolong", "alsolong"), check))
        assertNull(Validators.each(null, check))
    }
}
