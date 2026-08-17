package dev.tracedown.gateway.data

import kotlinx.serialization.Serializable

/**
 * Aggregated resource usage over a window. `ingressBytes`/`egressBytes` are
 * measured HTTP-layer traffic (see the probe agent) and `requests` is the HTTP
 * call count. `agentEgressBytes` is the number of bytes the scheduler
 * dispatched to probe agents (request payloads sent scheduler → agent).
 */
@Serializable
data class UsageResponse(
    /** The window actually summed, in hours (after capping to retention / 7d). */
    val windowHours: Int,
    val requests: Long,
    val ingressBytes: Long,
    val egressBytes: Long,
    val agentEgressBytes: Long,
)
