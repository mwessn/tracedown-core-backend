package dev.tracedown.gateway.data.silences

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

private val SILENCE_CHANNELS = setOf("email", "webhook", "all", "quiet-hours")

@Serializable
data class CreateSilenceRequest(
    val channel: String,
    val workspaceId: String? = null,
    val projectId: String? = null,
    val serviceId: String? = null,
    val config: String? = null,
    val quietHours: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("channel", channel)?.let(::add)
        Validators.oneOf("channel", channel, SILENCE_CHANNELS)?.let(::add)
        Validators.uuid("workspaceId", workspaceId)?.let(::add)
        Validators.uuid("projectId", projectId)?.let(::add)
        Validators.uuid("serviceId", serviceId)?.let(::add)
        Validators.maxLen("config", config, 1024)?.let(::add)
        Validators.maxLen("quietHours", quietHours, 256)?.let(::add)
    }
}

@Serializable
data class UpdateSilenceRequest(
    val channel: String? = null,
    val config: String? = null,
    val quietHours: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.oneOf("channel", channel, SILENCE_CHANNELS)?.let(::add)
        Validators.maxLen("config", config, 1024)?.let(::add)
        Validators.maxLen("quietHours", quietHours, 256)?.let(::add)
    }
}

@Serializable
data class SilenceSummary(
    val id: String,
    val orgUserId: String,
    val workspaceId: String?,
    val projectId: String?,
    val serviceId: String?,
    val channel: String,
    val config: String?,
    val quietHours: String?,
    /** Display name of the most specific silenced scope (null when scopeless). */
    val resourceName: String? = null,
)
