package dev.tracedown.gateway.routes.v1.audit

import dev.tracedown.gateway.controllers.audit.AuditController
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get

/**
 * @OpenAPITag Audit Log
 * Organization audit log.
 */
@Resource("/api/v1/audit-log")
class AuditLog

fun Route.auditRoutes() {
    /** Lists audit log entries with PFS (pagination, filtering, sorting). */
    get<AuditLog> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)

        call.respond(
            AuditController.list(orgId, principal.userId, pfs)
        )
    }
}
