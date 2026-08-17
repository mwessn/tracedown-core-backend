package dev.tracedown.realtime.auth

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.auth.resolveCachedPermissions
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Authorizes WebSocket channel subscriptions and relays against the SAME
 * resource-permission model the REST API enforces — the realtime-service shares
 * the database, so a live channel must not become a side door around it.
 *
 * Resource-scoped channels (`service:`, `project:`, `workspace:`, and the
 * collaborative-edit `svc-edit:` relay) name a specific resource; a bare org
 * member must not read another team's live probe stream or inject script edits
 * into a service they cannot access. Membership alone (which the org-scoped
 * session already proves) is NOT sufficient for these — the caller must hold
 * the resource grant, with the same downward inheritance the gateway applies.
 *
 * Non-resource channels (`org:`, `agents:`, `session:`) are governed elsewhere
 * (org scope / global / self) and are not gated here.
 */
object ChannelAuthorizer {

    /** True if [userId] may subscribe (read) to [channel] within [orgId]. */
    fun canSubscribe(userId: UUID, orgId: UUID, channel: String): Boolean =
        checkResourceChannel(userId, orgId, channel, requireWrite = false)

    /** True if [userId] may relay (write) into [channel] within [orgId]. */
    fun canRelay(userId: UUID, orgId: UUID, channel: String): Boolean =
        checkResourceChannel(userId, orgId, channel, requireWrite = true)

    private fun checkResourceChannel(
        userId: UUID,
        orgId: UUID,
        channel: String,
        requireWrite: Boolean,
    ): Boolean {
        val (resourceType, rawId) = parseResourceChannel(channel)
            ?: return true // not a resource-scoped channel — not gated here
        val resourceId = try {
            UUID.fromString(rawId)
        } catch (_: Exception) {
            return false
        }

        return transaction {
            val cached = resolveCachedPermissions(orgId, userId) ?: return@transaction false

            when (resourceType) {
                "workspace" -> {
                    if (!workspaceInOrg(resourceId, orgId)) return@transaction false
                    check(cached, "workspace", resourceId, emptyList(), requireWrite)
                }
                "project" -> {
                    val workspaceId = projectWorkspace(resourceId, orgId) ?: return@transaction false
                    check(cached, "project", resourceId, listOf("workspace::$workspaceId"), requireWrite)
                }
                "service" -> {
                    val ctx = serviceContext(resourceId, orgId) ?: return@transaction false
                    check(
                        cached, "service", resourceId,
                        listOf("project::${ctx.first}", "workspace::${ctx.second}"),
                        requireWrite,
                    )
                }
                else -> false
            }
        }
    }

    private fun check(
        cached: dev.tracedown.common.auth.CachedPermissions,
        type: String,
        id: UUID,
        parentChain: List<String>,
        requireWrite: Boolean,
    ): Boolean = if (requireWrite) {
        canWriteResource(cached, type, id, parentChain)
    } else {
        canAccessResource(cached, type, id, parentChain)
    }

    /** Maps a channel name to (resourceType, id) or null if not resource-scoped. */
    private fun parseResourceChannel(channel: String): Pair<String, String>? = when {
        channel.startsWith("svc-edit:") -> "service" to channel.removePrefix("svc-edit:")
        channel.startsWith("service:") -> "service" to channel.removePrefix("service:")
        channel.startsWith("project:") -> "project" to channel.removePrefix("project:")
        channel.startsWith("workspace:") -> "workspace" to channel.removePrefix("workspace:")
        else -> null
    }

    private fun workspaceInOrg(workspaceId: UUID, orgId: UUID): Boolean =
        Workspaces.selectAll()
            .where { (Workspaces.id eq workspaceId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .any()

    private fun projectWorkspace(projectId: UUID, orgId: UUID): UUID? =
        Projects
            .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
            .selectAll()
            .where { (Projects.id eq projectId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .firstOrNull()
            ?.get(Projects.workspaceId)

    /** Returns (projectId, workspaceId) for a service in [orgId], or null. */
    private fun serviceContext(serviceId: UUID, orgId: UUID): Pair<UUID, UUID>? =
        Services
            .join(Projects, JoinType.INNER, Services.projectId, Projects.id)
            .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
            .selectAll()
            .where { (Services.id eq serviceId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .firstOrNull()
            ?.let { it[Services.projectId] to it[Projects.workspaceId] }
}
