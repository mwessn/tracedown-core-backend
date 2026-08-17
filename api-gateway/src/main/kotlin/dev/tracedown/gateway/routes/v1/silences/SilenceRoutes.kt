package dev.tracedown.gateway.routes.v1.silences

import dev.tracedown.gateway.controllers.silences.SilenceController
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.silences.UpdateSilenceRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Silences
 * Notification silences: create, list, update, delete.
 */
@Resource("/api/v1/silences")
class Silences {
    @Resource("{silenceId}")
    class ById(val parent: Silences = Silences(), val silenceId: String)
}

fun Route.silenceRoutes() {
    /** Creates a notification silence for the current user. */
    post<Silences> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateSilenceRequest>(call)
        call.respond(SilenceController.create(orgId, principal.userId, body))
    }

    /** Lists the current user's notification silences. */
    get<Silences> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(SilenceController.list(orgId, principal.userId, pfs))
    }

    /** Returns a single silence. */
    get<Silences.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val silenceId = parseUuid(resource.silenceId, "silence ID")
        call.respond(SilenceController.get(orgId, principal.userId, silenceId))
    }

    /** Updates a silence's channel, config, or quiet hours. */
    patch<Silences.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val silenceId = parseUuid(resource.silenceId, "silence ID")
        val body = tryReceive<UpdateSilenceRequest>(call)
        call.respond(SilenceController.update(orgId, principal.userId, silenceId, body))
    }

    /** Removes a notification silence. */
    delete<Silences.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val silenceId = parseUuid(resource.silenceId, "silence ID")
        SilenceController.delete(orgId, principal.userId, silenceId)
        call.respond(mapOf("ok" to true))
    }
}
