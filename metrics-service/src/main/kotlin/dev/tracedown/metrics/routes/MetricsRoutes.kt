package dev.tracedown.metrics.routes

import dev.tracedown.metrics.auth.IntegrationAuth
import dev.tracedown.metrics.scrape.MetricsReader
import dev.tracedown.metrics.scrape.PrometheusFormatter
import dev.tracedown.metrics.scrape.ScopeResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Registers the Prometheus scrape endpoint.
 *
 * GET /metrics/{integrationId}
 * Authorization: Bearer {token}
 *
 * Returns Prometheus exposition format text with all services in scope.
 */
fun Route.metricsRoutes(metricsReader: MetricsReader) {
    route("/metrics") {
        get("/{id}") {
            val idParam = call.parameters["id"]
            val integrationId = try {
                UUID.fromString(idParam)
            } catch (_: Exception) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }

            // Extract bearer token
            val authHeader = call.request.headers["Authorization"]
            val bearerToken = if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authHeader.substring(7)
            } else {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }

            // Authenticate
            val integration = IntegrationAuth.authenticate(integrationId, bearerToken)
            if (integration == null) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }

            // Resolve scope → service list
            val scopeConfig = integration.config["scope"]?.jsonObject
            val services = ScopeResolver.resolve(integration.projectId, scopeConfig)

            if (services.isEmpty()) {
                call.respondText("", contentType = ContentType.Text.Plain)
                return@get
            }

            // Read metrics from Redis B
            val entries = services.mapNotNull { info ->
                val metrics = metricsReader.read(info.id) ?: return@mapNotNull null
                info to metrics
            }

            // Parse custom labels
            val customLabels = integration.config["labels"]?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.content }
                ?: emptyMap()

            // Read rolled-up uptime/error-rate from probe_aggregates for the same services
            val aggregates = metricsReader.readAggregates(entries.map { it.first.id })

            // Format and respond
            val output = PrometheusFormatter.format(entries, customLabels, aggregates)
            call.respondText(output, contentType = ContentType.Text.Plain)
        }
    }
}
