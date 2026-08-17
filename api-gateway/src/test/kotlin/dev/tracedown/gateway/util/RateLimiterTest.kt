package dev.tracedown.gateway.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the two hardening changes in the rate limiter:
 * 1. the client IP is derived a trusted number of proxy hops back, so a
 *    client-supplied X-Forwarded-For cannot spoof the key, and
 * 2. when Redis is unreachable the auth tier fails CLOSED (login/reset/export
 *    must not become unthrottled), while the general tier fails open.
 */
class RateLimiterTest {

    private val config = RateLimitConfig(
        enabled = true,
        general = TierConfig(maxRequests = 120, windowSeconds = 60),
        auth = TierConfig(maxRequests = 15, windowSeconds = 60),
        trustedProxies = 1,
    )

    // ── client IP derivation ──

    @Test
    fun `takes the client IP one trusted hop back from the peer`() {
        // nginx (10.0.0.2) appended the real client (203.0.113.9) to XFF.
        assertEquals("203.0.113.9", resolveClientIp("203.0.113.9", "10.0.0.2", 1))
    }

    @Test
    fun `ignores a spoofed X-Forwarded-For prefix`() {
        // Client injected "1.1.1.1"; the trusted proxy appended the real IP last.
        assertEquals("203.0.113.9", resolveClientIp("1.1.1.1, 203.0.113.9", "10.0.0.2", 1))
    }

    @Test
    fun `trustedProxies 0 ignores XFF entirely`() {
        assertEquals("10.0.0.2", resolveClientIp("1.1.1.1, 203.0.113.9", "10.0.0.2", 0))
    }

    @Test
    fun `two trusted proxies take two hops back`() {
        // client -> cdn -> nginx -> app: XFF = "client, cdn", peer = nginx.
        assertEquals("203.0.113.9", resolveClientIp("203.0.113.9, 198.51.100.7", "10.0.0.2", 2))
    }

    @Test
    fun `short chain falls back to the direct peer`() {
        assertEquals("10.0.0.2", resolveClientIp("", "10.0.0.2", 2))
        assertEquals("10.0.0.2", resolveClientIp(null, "10.0.0.2", 1))
    }

    // ── fail-closed on the auth tier ──

    @Test
    fun `auth tier fails closed when redis is down`() {
        val limiter = RateLimiter(redis = { throw RuntimeException("redis down") }, config = config)
        val result = limiter.check("203.0.113.9", RateLimiter.Tier.AUTH)
        assertFalse(result.allowed, "auth tier must reject when the limiter store is unreachable")
        assertTrue(result.retryAfterSeconds > 0)
    }

    @Test
    fun `general tier fails open when redis is down`() {
        val limiter = RateLimiter(redis = { throw RuntimeException("redis down") }, config = config)
        val result = limiter.check("203.0.113.9", RateLimiter.Tier.GENERAL)
        assertTrue(result.allowed, "general tier stays available over a brief limiter outage")
    }
}
