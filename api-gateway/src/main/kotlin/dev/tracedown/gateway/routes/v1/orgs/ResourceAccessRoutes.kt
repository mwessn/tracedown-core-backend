package dev.tracedown.gateway.routes.v1.orgs

import dev.tracedown.gateway.controllers.orgs.ResourceAccessController
import dev.tracedown.gateway.data.orgs.UpsertAccessRequest
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * @OpenAPITag ResourceAccess
 * Resource-scoped grants — who has access to a workspace, project or service.
 */
@Serializable
@Resource("/api/v1/access/{resourceType}/{resourceId}")
class Access(val resourceType: String, val resourceId: String) {
    @Serializable
    @Resource("{principalType}/{principalId}")
    class Principal(val parent: Access, val principalType: String, val principalId: String)
}

fun Route.resourceAccessRoutes() {
    /** Lists the principals granted access to the resource. Requires resource write. */
    get<Access> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val resourceId = parseUuid(resource.resourceId, "resource ID")
        call.respond(ResourceAccessController.list(orgId, resource.resourceType, resourceId, principal.userId))
    }

    /** Grants or updates one principal's level on the resource. Requires resource write. */
    put<Access> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val resourceId = parseUuid(resource.resourceId, "resource ID")
        val body = tryReceive<UpsertAccessRequest>(call)
        ResourceAccessController.upsert(orgId, resource.resourceType, resourceId, body, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Revokes a principal's grant on the resource. Requires resource write. */
    delete<Access.Principal> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val resourceId = parseUuid(resource.parent.resourceId, "resource ID")
        ResourceAccessController.remove(
            orgId, resource.parent.resourceType, resourceId,
            resource.principalType, resource.principalId, principal.userId,
        )
        call.respond(mapOf("ok" to true))
    }
}
