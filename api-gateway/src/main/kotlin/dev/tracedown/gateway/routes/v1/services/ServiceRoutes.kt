package dev.tracedown.gateway.routes.v1.services

import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.variables.VariableHierarchyController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.services.UpdateScriptRequest
import dev.tracedown.gateway.data.services.UpdateServiceRequest
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
import io.ktor.server.resources.put

/**
 * @OpenAPITag Services
 * Service CRUD, script management, toggle, and service-level variables.
 */
@Resource("/api/v1/services")
class Services {
    @Resource("{id}")
    class ById(val parent: Services = Services(), val id: String) {
        @Resource("script")
        class Script(val parent: ById)

        @Resource("snapshot")
        class Snapshot(val parent: ById)

        @Resource("agents")
        class Agents(val parent: ById)

        @Resource("toggle")
        class Toggle(val parent: ById)

        @Resource("run")
        class Run(val parent: ById)

        @Resource("variables")
        class Variables(val parent: ById) {
            @Resource("hierarchy")
            class Hierarchy(val parent: Variables)

            @Resource("{varId}")
            class VarById(val parent: Variables, val varId: String) {
                @Resource("reveal")
                class Reveal(val parent: VarById)
            }
        }
    }
}

fun Route.serviceRoutes() {
    /** Creates a service inside a project. */
    post<Services> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateServiceRequest>(call)
        val projectId = parseUuid(body.projectId, "project ID")
        call.respond(ServiceController.create(orgId, projectId, body, principal.userId))
    }

    /** Lists all services in a project the user has access to. */
    get<Services> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(call.request.queryParameters["projectId"] ?: "", "projectId query parameter")
        val pfs = parsePfsParams(call)
        call.respond(ServiceController.list(orgId, projectId, principal.userId, pfs))
    }

    /** Returns a single service. */
    get<Services.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.id, "id")
        call.respond(ServiceController.get(orgId, serviceId, principal.userId))
    }

    /** Combined detail + recent probe points — one round-trip for the live channel. */
    get<Services.ById.Snapshot> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        call.respond(ServiceController.snapshot(orgId, serviceId, principal.userId))
    }

    /** Allowed agent slugs for the service (empty = all agents). */
    get<Services.ById.Agents> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "service ID")
        call.respond(ServiceController.listAllowedAgents(orgId, serviceId, principal.userId))
    }

    /** Replaces the allowed-agent set. An empty list restores the default (all). */
    put<Services.ById.Agents> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "service ID")
        val body = tryReceive<dev.tracedown.gateway.data.services.SetAllowedAgentsRequest>(call)
        call.respond(ServiceController.setAllowedAgents(orgId, serviceId, body.slugs, principal.userId))
    }

    /** Updates service configuration. */
    patch<Services.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.id, "id")
        val body = tryReceive<UpdateServiceRequest>(call)
        call.respond(ServiceController.update(orgId, serviceId, body, principal.userId))
    }

    /** Soft-deletes a service. */
    delete<Services.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.id, "id")
        ServiceController.delete(orgId, serviceId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Updates the service's Lace script. Validates before saving. */
    patch<Services.ById.Script> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<UpdateScriptRequest>(call)
        call.respond(ServiceController.updateScript(orgId, serviceId, body, principal.userId))
    }

    /** Enables or disables a service. */
    patch<Services.ById.Toggle> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggle(orgId, serviceId, body, principal.userId))
    }

    /** Requests an immediate one-off probe run. Returns 202 once queued for dispatch. */
    post<Services.ById.Run> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        ServiceController.triggerRun(orgId, serviceId, principal.userId)
        call.respond(io.ktor.http.HttpStatusCode.Accepted, mapOf("ok" to true))
    }

    /** Lists service variables. Encrypted values are masked. */
    get<Services.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        val pfs = parsePfsParams(call)
        call.respond(ServiceController.listVariables(orgId, serviceId, principal.userId, pfs))
    }

    /** Full inherited variable hierarchy (service → project → workspace → org) + locked vars. */
    get<Services.ById.Variables.Hierarchy> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.parent.id, "id")
        call.respond(VariableHierarchyController.forService(orgId, serviceId, principal.userId))
    }

    /** Creates a service variable. */
    post<Services.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(ServiceController.createVariable(orgId, serviceId, body, principal.userId))
    }

    /** Decrypts and returns a single service variable. Secrets cannot be revealed. */
    get<Services.ById.Variables.VarById.Reveal> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.parent.parent.id, "id")
        val varId = parseUuid(resource.parent.varId, "variable ID")
        call.respond(ServiceController.revealVariable(orgId, serviceId, varId, principal.userId))
    }

    /** Updates a service variable's value. */
    patch<Services.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.parent.id, "id")
        val varId = parseUuid(resource.varId, "variable ID")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(ServiceController.updateVariable(orgId, serviceId, varId, body, principal.userId))
    }

    /** Soft-deletes a service variable. */
    delete<Services.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.parent.id, "id")
        val varId = parseUuid(resource.varId, "variable ID")
        ServiceController.deleteVariable(orgId, serviceId, varId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
