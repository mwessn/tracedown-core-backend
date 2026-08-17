package dev.tracedown.gateway.routes.v1.orgs

import dev.tracedown.gateway.controllers.orgs.GroupController
import dev.tracedown.gateway.data.orgs.AddMemberRequest
import dev.tracedown.gateway.data.orgs.CreateGroupRequest
import dev.tracedown.gateway.data.orgs.SyncMembersRequest
import dev.tracedown.gateway.data.orgs.UpdateGroupRequest
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
 * @OpenAPITag Groups
 * Group management — create, update, delete groups and manage memberships.
 */
@Resource("/api/v1/groups")
class Groups {
    @Resource("{groupId}")
    class ById(val parent: Groups = Groups(), val groupId: String) {
        @Resource("members")
        class Members(val parent: ById) {
            @Resource("{userId}")
            class ByUserId(val parent: Members, val userId: String)
        }

        @Resource("permissions")
        class Permissions(val parent: ById)
    }
}

fun Route.groupRoutes() {
    /** Creates a new group in the organization. */
    post<Groups> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateGroupRequest>(call)
        call.respond(GroupController.createGroup(orgId, body.name, principal.userId))
    }

    /** Lists all groups in the organization with member counts. */
    get<Groups> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(GroupController.listGroups(orgId, principal.userId, pfs))
    }

    /** Returns details of a single group. */
    get<Groups.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.groupId, "group ID")
        call.respond(GroupController.getGroup(orgId, groupId, principal.userId))
    }

    /** Updates a group's name and/or permission levels. Only non-null fields are applied. */
    patch<Groups.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.groupId, "group ID")
        val body = tryReceive<UpdateGroupRequest>(call)
        call.respond(GroupController.updateGroup(orgId, groupId, body, principal.userId))
    }

    /** Deletes a group and all its memberships. */
    delete<Groups.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.groupId, "group ID")
        GroupController.deleteGroup(orgId, groupId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Lists all members of the group. */
    get<Groups.ById.Members> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.groupId, "group ID")
        val pfs = parsePfsParams(call)
        call.respond(GroupController.listMembers(orgId, groupId, principal.userId, pfs))
    }

    /** Adds a single user to the group. */
    post<Groups.ById.Members> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.groupId, "group ID")
        val body = tryReceive<AddMemberRequest>(call)
        val userId = parseUuid(body.userId, "user ID")
        GroupController.addMember(orgId, groupId, userId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Syncs group membership to the provided user ID list (diff-based). */
    patch<Groups.ById.Members> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.groupId, "group ID")
        val body = tryReceive<SyncMembersRequest>(call)
        val userIds = body.userIds.map { parseUuid(it, "user ID") }
        call.respond(GroupController.syncMembers(orgId, groupId, userIds, principal.userId))
    }

    /** Removes a user from the group. */
    delete<Groups.ById.Members.ByUserId> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val groupId = parseUuid(resource.parent.parent.groupId, "group ID")
        val userId = parseUuid(resource.userId, "user ID")
        GroupController.removeMember(orgId, groupId, userId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
