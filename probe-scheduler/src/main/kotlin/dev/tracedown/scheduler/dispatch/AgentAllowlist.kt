package dev.tracedown.scheduler.dispatch

import java.util.UUID

/**
 * A pluggable restriction on which agents a service may dispatch to.
 *
 * Core imposes none — every registered agent is eligible, so the default provider
 * returns null (no restriction). This is the neutral seam an overlay uses to scope
 * dispatch: a provider returns the set of agent ids a service is allowed to use,
 * and [AgentSelector] intersects its eligible agents with it. Core stays unaware of
 * why the set is narrowed (e.g. ownership) — it only honors the allowlist.
 *
 * Called inside the selector's existing transaction, so a provider may query the DB.
 */
object AgentAllowlist {

    fun interface Provider {
        /** Allowed agent ids for [serviceId], or null for "no restriction" (all agents). */
        fun allowedAgentIds(serviceId: UUID): Set<Long>?
    }

    /** The default: no restriction. */
    private val ALLOW_ALL = Provider { null }

    @Volatile
    var provider: Provider = ALLOW_ALL
        private set

    /** Installs the restriction provider (last registration wins). */
    fun register(p: Provider) {
        provider = p
    }
}
