package dev.tracedown.common.logging

import org.slf4j.MDC
import java.util.UUID

/**
 * Central diagnostic-context façade over SLF4J's [MDC].
 *
 * The shared `logback-base.xml` renders these keys into every log line and,
 * when file logging is enabled, sifts a per-tenant file by [ORG]. Populate the
 * context at each entry point (an authenticated request, a probe dispatch, an
 * outbox event) so downstream log lines are attributable to an organization
 * without threading the id through every call.
 *
 * MDC is thread-local. Set the context on the thread that will do the logging
 * and clear it when the unit of work ends — [scoped] does both around a block
 * and is the safe default; the bare [putOrg]/[clear] setters exist for entry
 * points (a request handler) whose teardown is elsewhere.
 */
object LogContext {

    /** Owning organization. Rendered in every line and used for per-org file sifting. */
    const val ORG = "org"

    /** Acting user, when a request carries one. */
    const val USER = "user"

    /** Workspace in scope, when applicable. */
    const val WORKSPACE = "ws"

    /** Project in scope, when applicable. */
    const val PROJECT = "proj"

    /** Service (monitored target) in scope, when applicable. */
    const val SERVICE = "svc"

    /** Sets (or, given null, removes) a single MDC key. */
    fun put(key: String, value: UUID?) {
        if (value == null) MDC.remove(key) else MDC.put(key, value.toString())
    }

    /** Sets (or, given null/blank, removes) a single MDC key from a string. */
    fun put(key: String, value: String?) {
        if (value.isNullOrBlank()) MDC.remove(key) else MDC.put(key, value)
    }

    /** Convenience for the most common key. */
    fun putOrg(orgId: UUID?) = put(ORG, orgId)

    /** Clears every key this façade manages, leaving unrelated MDC keys intact. */
    fun clear() {
        MDC.remove(ORG)
        MDC.remove(USER)
        MDC.remove(WORKSPACE)
        MDC.remove(PROJECT)
        MDC.remove(SERVICE)
    }

    /**
     * Runs [block] with the given ids in context, restoring the previous values
     * afterwards (nesting-safe, exception-safe). Only non-null ids are applied;
     * pass an id to set it, omit it to leave the enclosing scope's value.
     */
    inline fun <T> scoped(
        org: UUID? = null,
        user: UUID? = null,
        workspace: UUID? = null,
        project: UUID? = null,
        service: UUID? = null,
        block: () -> T,
    ): T {
        val prev = arrayOf(
            MDC.get(ORG), MDC.get(USER), MDC.get(WORKSPACE), MDC.get(PROJECT), MDC.get(SERVICE),
        )
        org?.let { MDC.put(ORG, it.toString()) }
        user?.let { MDC.put(USER, it.toString()) }
        workspace?.let { MDC.put(WORKSPACE, it.toString()) }
        project?.let { MDC.put(PROJECT, it.toString()) }
        service?.let { MDC.put(SERVICE, it.toString()) }
        try {
            return block()
        } finally {
            restore(ORG, prev[0]); restore(USER, prev[1]); restore(WORKSPACE, prev[2])
            restore(PROJECT, prev[3]); restore(SERVICE, prev[4])
        }
    }

    /** Restores an MDC key to a captured prior value (removing it if that was null). */
    @PublishedApi
    internal fun restore(key: String, previous: String?) {
        if (previous == null) MDC.remove(key) else MDC.put(key, previous)
    }

    /** Runs [block] with [orgId] in context, restoring the prior org afterwards. */
    inline fun <T> withOrg(orgId: UUID?, block: () -> T): T = scoped(org = orgId, block = block)
}
