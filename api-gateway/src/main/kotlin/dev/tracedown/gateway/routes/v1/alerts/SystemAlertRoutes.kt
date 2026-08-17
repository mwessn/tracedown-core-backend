package dev.tracedown.gateway.routes.v1.alerts

import dev.tracedown.gateway.controllers.alerts.SystemAlertController
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import io.ktor.server.resources.post

/**
 * @OpenAPITag System Alerts
 * Platform-raised operational alerts: list active, dismiss per user.
 */
@Resource("/api/v1/system-alerts")
class SystemAlertsRes {
    @Resource("history")
    class History(val parent: SystemAlertsRes = SystemAlertsRes())

    @Resource("{id}/dismiss")
    class Dismiss(val parent: SystemAlertsRes = SystemAlertsRes(), val id: String = "")
}

fun Route.systemAlertRoutes() {
    /** Lists active, non-dismissed alerts for the caller (settings write required). */
    get<SystemAlertsRes> {
        val (principal, orgId) = requireAuthWithOrg(call)
        call.respond(SystemAlertController.listActive(orgId, principal.userId))
    }

    /** Full episode history for the warning log (newest first, paged). */
    get<SystemAlertsRes.History> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(SystemAlertController.history(orgId, principal.userId, pfs))
    }

    /** Dismisses an alert for the caller only. */
    post<SystemAlertsRes.Dismiss> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val alertId = parseUuid(resource.id, "alert ID")
        SystemAlertController.dismiss(orgId, alertId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
