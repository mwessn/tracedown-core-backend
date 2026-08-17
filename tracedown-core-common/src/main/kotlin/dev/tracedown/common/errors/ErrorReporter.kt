package dev.tracedown.common.errors

/**
 * Seam for reporting **unhandled** (unexpected) errors to an external observer.
 *
 * A service calls [report] from its catch-all error handler — the point a throw
 * has already escaped every expected/handled path and is about to become a 500.
 * By default this is a no-op with zero overhead; an extension registers a listener
 * to persist or forward the errors (e.g. an operator dashboard). Core stays
 * unaware of any such consumer — this is plain observability, not knowledge of one.
 *
 * Reporting must never itself disrupt request handling, so a listener that throws
 * is swallowed.
 */
object ErrorReporter {

    /** One unhandled error: the [service] that caught it and the request context. */
    data class ErrorEvent(
        val service: String,
        val throwable: Throwable,
        val path: String?,
        val method: String?,
    )

    private val listeners = mutableListOf<(ErrorEvent) -> Unit>()

    /** Registers a listener invoked for every reported unhandled error. */
    fun register(listener: (ErrorEvent) -> Unit) {
        listeners.add(listener)
    }

    /** Reports an unhandled error to all listeners (no-op when none are registered). */
    fun report(service: String, throwable: Throwable, path: String? = null, method: String? = null) {
        if (listeners.isEmpty()) return
        val event = ErrorEvent(service, throwable, path, method)
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    /** Removes all listeners. Intended for testing only. */
    fun clearAll() {
        listeners.clear()
    }
}
