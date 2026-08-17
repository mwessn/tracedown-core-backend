package dev.tracedown.gateway.data.results

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProbeResultSummary(
    val id: String,
    val status: String,
    val runDurationMs: Int,
    val totalResponseMs: Int,
    val startedAt: String,
    val agentSlug: String? = null,
)

@Serializable
data class ProbeResultDetail(
    val id: String,
    val serviceId: String,
    val status: String,
    val runDurationMs: Int,
    val startedAt: String,
    val probeAgentId: Long? = null,
    val rawResult: JsonElement,
    val steps: List<ProbeStepSummary>,
)

@Serializable
data class ProbeStepSummary(
    val id: String,
    val stepNum: Short,
    val requestUrl: String,
    val statusCode: Short?,
    val responseTimeMs: Int?,
    val dnsMs: Int?,
    val connectMs: Int?,
    val tlsMs: Int?,
    val ttfbMs: Int?,
    val transferMs: Int?,
    val responseSizeBytes: Int?,
    val error: String?,
    val assertionResults: JsonElement?,
    val headers: JsonElement?,
    val hasBody: Boolean,
    val bodyNotStoredReason: String?,
)
