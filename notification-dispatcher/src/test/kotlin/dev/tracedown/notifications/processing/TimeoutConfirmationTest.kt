package dev.tracedown.notifications.processing

import dev.tracedown.notifications.processing.TimeoutConfirmation.Tick
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The regression these guard: every timeout was announced the moment it was
 * recorded, so one agent's stalled route to a healthy target mailed "timed
 * out" and, a tick later, "recovered" — an outage that other agents, probing
 * the same target in the same minute, never saw.
 */
class TimeoutConfirmationTest {

    private val up = Tick(listOf("success"))
    private val timedOut = Tick(listOf("timeout"))
    private val failed = Tick(listOf("failure"))

    private fun announce(vararg triggers: String, previous: Tick?, beforePrevious: Tick? = null) =
        TimeoutConfirmation.shouldAnnounce(triggers.toList(), previous, beforePrevious)

    @Test
    fun `a first timeout after a success is held`() {
        assertFalse(announce("timeout", previous = up))
    }

    @Test
    fun `a service's very first run timing out is held`() {
        assertFalse(announce("timeout", previous = null))
    }

    @Test
    fun `the second timeout in a row is announced`() {
        assertTrue(announce("timeout", previous = timedOut, beforePrevious = up))
    }

    @Test
    fun `a timeout during an outage already announced as a failure is announced`() {
        assertTrue(announce("timeout", previous = failed, beforePrevious = up))
    }

    @Test
    fun `a failure never waits`() {
        assertTrue(announce("failure", previous = up))
        assertTrue(announce("failure", previous = null))
    }

    @Test
    fun `a timed-out call beside a failed check is the target's own word`() {
        assertTrue(announce("failure", "timeout", previous = up))
    }

    @Test
    fun `the recovery from a held timeout is not announced`() {
        assertFalse(announce("recovered", previous = timedOut, beforePrevious = up))
        assertFalse(announce("recovered", previous = timedOut, beforePrevious = null))
    }

    @Test
    fun `the recovery from a confirmed timeout is announced`() {
        assertTrue(announce("recovered", previous = timedOut, beforePrevious = timedOut))
        assertTrue(announce("recovered", previous = timedOut, beforePrevious = failed))
    }

    @Test
    fun `the recovery from a failure is announced`() {
        assertTrue(announce("recovered", previous = failed, beforePrevious = up))
    }

    @Test
    fun `a recovery with no history to judge by is announced`() {
        assertTrue(announce("recovered", previous = null))
    }

    @Test
    fun `one agent of several timing out does not make the tick down`() {
        val mixed = Tick(listOf("timeout", "success", "success"))
        assertFalse(mixed.down)
        // The same agent stalling again next tick is still unconfirmed...
        assertFalse(announce("timeout", previous = mixed, beforePrevious = up))
        // ...and its recovery closes nothing that was announced.
        assertFalse(announce("recovered", previous = mixed, beforePrevious = up))
    }

    @Test
    fun `every agent of a tick timing out confirms the next`() {
        val allOut = Tick(listOf("timeout", "timeout"))
        assertTrue(allOut.down)
        assertTrue(announce("timeout", previous = allOut, beforePrevious = up))
    }

    @Test
    fun `a tick with no verdicts is not down`() {
        assertFalse(Tick(emptyList()).down)
    }
}
