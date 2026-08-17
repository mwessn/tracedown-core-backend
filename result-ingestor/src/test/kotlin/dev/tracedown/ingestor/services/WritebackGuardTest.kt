package dev.tracedown.ingestor.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Agent `.store()` writeback may only overwrite metric variables. Secret and
 * encrypted rows must be left untouched (no plaintext overwrite / hijack).
 */
class WritebackGuardTest {

    @Test
    fun `metric variable may be overwritten`() {
        assertTrue(ResultPersistenceService.writebackMayOverwrite(existingSecret = false, existingEncrypted = false))
    }

    @Test
    fun `secret variable is never overwritten`() {
        assertFalse(ResultPersistenceService.writebackMayOverwrite(existingSecret = true, existingEncrypted = false))
    }

    @Test
    fun `encrypted variable is never overwritten`() {
        assertFalse(ResultPersistenceService.writebackMayOverwrite(existingSecret = false, existingEncrypted = true))
    }

    @Test
    fun `secret and encrypted variable is never overwritten`() {
        assertFalse(ResultPersistenceService.writebackMayOverwrite(existingSecret = true, existingEncrypted = true))
    }
}
