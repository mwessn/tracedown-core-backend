package dev.tracedown.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the production secret guard. The environment is driven through
 * the explicit config-value path (not the DEPLOYMENT_ENV env var), so the tests
 * are hermetic.
 */
class SecretGuardTest {

    @Test
    fun `defaults to dev when unset`() {
        assertEquals("dev", SecretGuard.environment(null))
        assertFalse(SecretGuard.isProduction(null))
    }

    @Test
    fun `production is recognised (case-insensitive)`() {
        assertTrue(SecretGuard.isProduction("production"))
        assertTrue(SecretGuard.isProduction("Production"))
        assertFalse(SecretGuard.isProduction("staging"))
    }

    @Test
    fun `throws in production when a default is insecure`() {
        val e = assertFailsWith<IllegalStateException> {
            SecretGuard.requireSecure(
                "production",
                "test-service",
                mapOf("SECRET (default)" to true, "OTHER (ok)" to false),
            )
        }
        assertTrue(e.message!!.contains("SECRET (default)"))
        assertFalse(e.message!!.contains("OTHER (ok)"))
    }

    @Test
    fun `silent in production when nothing is insecure`() {
        SecretGuard.requireSecure("production", "test-service", mapOf("SECRET" to false))
    }

    @Test
    fun `silent in dev even with insecure defaults`() {
        // The whole point: dev keeps the insecure defaults without failing startup.
        SecretGuard.requireSecure("dev", "test-service", mapOf("SECRET (default)" to true))
        SecretGuard.requireSecure(null, "test-service", mapOf("SECRET (default)" to true))
    }
}
