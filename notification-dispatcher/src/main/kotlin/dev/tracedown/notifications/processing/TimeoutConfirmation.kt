package dev.tracedown.notifications.processing

/**
 * Decides whether a run's notification events are announced, given what the
 * service's runs before it said.
 *
 * A `timeout` is the one outcome that cannot be told apart, at the moment it
 * happens, from the probing agent's own trouble: a stalled route between the
 * agent and the target, or a response the agent's network delivered too slowly,
 * ends in exactly the same result as a target that hung. A `failure` is
 * different — a refused connection, a status code or an assertion that did not
 * hold is something the target said.
 *
 * So a timeout is announced only once it is confirmed: the service's previous
 * tick must already have been down. A first timeout is recorded as observed
 * (status, downtime, history) and stays silent; if the next run times out too
 * — on another agent, where rotation allows — that one is announced. A real
 * hang is therefore announced one interval late, and a single stall never is.
 *
 * The recovery follows the same book-keeping. laceEmitRecovery emits
 * `recovered` from `prev.outcome`, with no knowledge of what was mailed, so a
 * held timeout followed by a success would announce the end of an outage
 * nobody was told about. A recovery is announced only when something in the
 * tick before it was.
 *
 * Pure, so the rule is testable without a database.
 */
object TimeoutConfirmation {

    /**
     * The statuses of every result a service produced at one scheduled moment:
     * one in most probe modes, one per agent in `simultaneous`. `error` and
     * `skipped` results are not part of a tick — neither says anything about
     * the target.
     */
    data class Tick(val statuses: List<String>) {
        /** No agent reached a verdict of `success` at this moment. */
        val down: Boolean get() = statuses.isNotEmpty() && SUCCESS !in statuses
    }

    /**
     * @param triggers the triggers of this run's notification events
     * @param previous the service's tick before this run, null when it has none
     * @param beforePrevious the tick before [previous]
     */
    fun shouldAnnounce(triggers: Collection<String>, previous: Tick?, beforePrevious: Tick?): Boolean {
        if (RECOVERED in triggers) {
            // No history to judge by (purged, or never there): say it.
            if (previous == null) return true
            return FAILURE in previous.statuses ||
                (TIMEOUT in previous.statuses && beforePrevious?.down == true)
        }
        // Anything beside a timeout is the target's own word and needs no
        // second opinion — a run with a failed check and a timed-out call
        // included.
        if (triggers.any { it != TIMEOUT }) return true
        return previous?.down == true
    }

    const val SUCCESS = "success"
    const val FAILURE = "failure"
    const val TIMEOUT = "timeout"
    const val RECOVERED = "recovered"

    /** The statuses that make up a [Tick]. */
    val VERDICTS = listOf(SUCCESS, FAILURE, TIMEOUT)
}
