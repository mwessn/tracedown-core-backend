package dev.tracedown.gateway.routes.v1.orgs

import dev.tracedown.gateway.controllers.orgs.PermissionController
import dev.tracedown.gateway.data.orgs.ToggleOrgUserRequest
import dev.tracedown.gateway.data.orgs.UpdatePermissionsRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
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
 * @OpenAPITag Permissions
 * User and group permission management.
 */
@Resource("/api/v1/users")
class Users {
    @Resource("{userId}")
    class ById(val parent: Users = Users(), val userId: String) {
        @Resource("permissions")
        class Permissions(val parent: ById)

        @Resource("toggle")
        class Toggle(val parent: ById)

    }
}

fun Route.permissionRoutes() {
    /** Lists active organization members. Requires users.read. */
    get<Users> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(PermissionController.listUsers(orgId, principal.userId, pfs))
    }

    /** Enables or disables a member. Requires users.write; owner/self excluded. */
    post<Users.ById.Toggle> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val userId = parseUuid(resource.parent.userId, "user ID")
        val body = tryReceive<ToggleOrgUserRequest>(call)
        PermissionController.setUserActive(orgId, userId, body.isActive, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Removes a member from the org. Requires users.write; owner/self excluded. */
    delete<Users.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val userId = parseUuid(resource.userId, "user ID")
        PermissionController.removeUser(orgId, userId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Returns a user's org + resource permissions in the current org. */
    get<Users.ById.Permissions> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val userId = parseUuid(resource.parent.userId, "user ID")
        call.respond(PermissionController.getUserPermissions(orgId, userId, principal.userId))
    }

    /** Updates a user's org + resource permissions. Requires users.write. */
    patch<Users.ById.Permissions> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val userId = parseUuid(resource.parent.userId, "user ID")
        val body = tryReceive<UpdatePermissionsRequest>(call)
        call.respond(PermissionController.updateUserPermissions(orgId, userId, body, principal.userId))
    }

    /** Returns a group's org + resource permissions. */
    get<Groups.ById.Permissions> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.groupId, "group ID")
        call.respond(PermissionController.getGroupPermissions(orgId, groupId, principal.userId))
    }

    /** Updates a group's org + resource permissions. Requires users.write. */
    patch<Groups.ById.Permissions> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.groupId, "group ID")
        val body = tryReceive<UpdatePermissionsRequest>(call)
        call.respond(PermissionController.updateGroupPermissions(orgId, groupId, body, principal.userId))
    }
}
