package dev.tracedown.common.errors

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The unhandled-error seam: no-op by default, delivers to listeners, never throws. */
class ErrorReporterTest {

    @AfterTest
    fun cleanup() = ErrorReporter.clearAll()

    @Test
    fun `report is a no-op with no listeners`() {
        // Must not throw when nothing is registered (self-hosted Core).
        ErrorReporter.report("api-gateway", RuntimeException("boom"), "/x", "GET")
    }

    @Test
    fun `listener receives the event`() {
        var received: ErrorReporter.ErrorEvent? = null
        ErrorReporter.register { received = it }
        val cause = IllegalStateException("nope")
        ErrorReporter.report("api-gateway", cause, "/orgs/1", "POST")
        assertEquals("api-gateway", received?.service)
        assertEquals(cause, received?.throwable)
        assertEquals("/orgs/1", received?.path)
        assertEquals("POST", received?.method)
    }

    @Test
    fun `a throwing listener does not disrupt reporting`() {
        var second: ErrorReporter.ErrorEvent? = null
        ErrorReporter.register { throw RuntimeException("listener blew up") }
        ErrorReporter.register { second = it }
        // Report must not propagate the listener's throw, and later listeners still run.
        ErrorReporter.report("svc", RuntimeException("x"), null, null)
        assertTrue(second != null)
    }

    @Test
    fun `clearAll removes listeners`() {
        var got: ErrorReporter.ErrorEvent? = null
        ErrorReporter.register { got = it }
        ErrorReporter.clearAll()
        ErrorReporter.report("svc", RuntimeException("x"), null, null)
        assertNull(got)
    }
}
