package dev.tracedown.gateway.routes.v1.integrations

import dev.tracedown.gateway.controllers.integrations.GrafanaIntegrationController
import dev.tracedown.gateway.data.integrations.CreateGrafanaIntegrationRequest
import dev.tracedown.gateway.data.integrations.UpdateGrafanaIntegrationRequest
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Grafana Integration
 * Per-project Grafana integration: get, create, update, delete, regenerate token.
 */
@Resource("/api/v1/projects/{projectId}/integrations/grafana")
class ProjectGrafanaIntegration(val projectId: String = "") {
    @Resource("regenerate-token")
    class RegenerateToken(val parent: ProjectGrafanaIntegration = ProjectGrafanaIntegration())
}

fun Route.grafanaIntegrationRoutes() {
    /** Returns the project's Grafana integration (token redacted), or null when unset. */
    get<ProjectGrafanaIntegration> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "projectId")
        call.respond(GrafanaIntegrationController.getForProject(orgId, projectId, principal.userId))
    }

    /** Creates the project's Grafana integration. Returns the generated bearer token. */
    post<ProjectGrafanaIntegration> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "projectId")
        val body = tryReceive<CreateGrafanaIntegrationRequest>(call)
        val result = GrafanaIntegrationController.create(orgId, projectId, body, principal.userId)
        call.respond(HttpStatusCode.Created, result)
    }

    /** Updates the project's Grafana integration. */
    patch<ProjectGrafanaIntegration> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "projectId")
        val body = tryReceive<UpdateGrafanaIntegrationRequest>(call)
        call.respond(GrafanaIntegrationController.update(orgId, projectId, body, principal.userId))
    }

    /** Soft-deletes the project's Grafana integration. */
    delete<ProjectGrafanaIntegration> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "projectId")
        GrafanaIntegrationController.delete(orgId, projectId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Regenerates the bearer token. Returns the new token. */
    post<ProjectGrafanaIntegration.RegenerateToken> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.parent.projectId, "projectId")
        call.respond(GrafanaIntegrationController.regenerateToken(orgId, projectId, principal.userId))
    }
}
