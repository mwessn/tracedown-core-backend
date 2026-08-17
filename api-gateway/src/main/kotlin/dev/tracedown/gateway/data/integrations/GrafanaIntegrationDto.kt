package dev.tracedown.gateway.data.integrations

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateGrafanaIntegrationRequest(
    val name: String = "Grafana",
    val scope: ScopeConfig? = null,
    val labels: Map<String, String>? = null,
    val enabled: Boolean = true,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
    }
}

@Serializable
data class UpdateGrafanaIntegrationRequest(
    val name: String? = null,
    val scope: ScopeConfig? = null,
    val labels: Map<String, String>? = null,
    val enabled: Boolean? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
    }
}

/** Scrape scope within the project: all services (default) or a subset. */
@Serializable
data class ScopeConfig(
    val type: String = "all",
    val ids: List<String>? = null,
)

@Serializable
data class GrafanaIntegrationSummary(
    val id: String,
    val projectId: String,
    val name: String,
    val token: String? = null,
    val scope: ScopeConfig?,
    val labels: Map<String, String>?,
    val enabled: Boolean,
    val createdAt: String,
    /** Path of the Prometheus scrape endpoint on the metrics service. */
    val scrapePath: String,
    /** Full scrape URL when the platform advertises a metrics base URL. */
    val scrapeUrl: String? = null,
)

/** GET wrapper — a project without an integration returns `integration: null`. */
@Serializable
data class GrafanaIntegrationState(
    val integration: GrafanaIntegrationSummary? = null,
)
