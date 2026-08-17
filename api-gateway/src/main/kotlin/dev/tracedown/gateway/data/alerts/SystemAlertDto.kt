package dev.tracedown.gateway.data.alerts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SystemAlertSummary(
    val id: String,
    val alertType: String,
    val subject: String,
    val severity: String,
    val data: JsonObject? = null,
    val createdAt: String,
    val lastSeenAt: String,
)
