package dev.tracedown.gateway.controllers.agents

import dev.tracedown.common.agents.AgentVisibility
import dev.tracedown.common.agents.DegradationRule
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.gateway.data.agents.PublicAgentSummary
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * The agents a member may name in a service's agent list, by slug.
 *
 * The same gate as the dashboard's agent health roster: membership, re-resolved
 * per call, and the deployment's [AgentVisibility] narrowing — and less than it
 * shows: a slug, a label and a health verdict, no round-trip figures, no
 * address.
 */
object AgentDirectory {

    /** The active agents [userId] can see in [orgId], ordered by slug. */
    fun list(orgId: UUID, userId: UUID): List<PublicAgentSummary> = transaction {
        // 403 not_org_member when the membership is gone.
        requireCachedPermissions(orgId, userId)
        val rows = ProbeAgents.select(ProbeAgents.id, ProbeAgents.slug, ProbeAgents.label, ProbeAgents.lastStatus, ProbeAgents.lastPing)
            .where { (ProbeAgents.isActive eq true) and (ProbeAgents.deleted eq false) }
            .orderBy(ProbeAgents.slug)
            .toList()
        val visible = AgentVisibility.visible(orgId, userId, rows.map { it[ProbeAgents.slug] })
        val shown = rows.filter { it[ProbeAgents.slug] in visible }
        // The verdict the dashboard's roster serialises, from the same rule
        // and the same rounds, in one statement.
        val verdicts = DegradationRule.verdicts(shown.map { it[ProbeAgents.id] })
        shown.map {
            PublicAgentSummary(
                slug = it[ProbeAgents.slug],
                label = it[ProbeAgents.label],
                status = health(it[ProbeAgents.lastStatus], verdicts[it[ProbeAgents.id]]?.degraded == true),
                lastCheckAt = it[ProbeAgents.lastPing].toString(),
            )
        }
    }

    /**
     * One word for an agent's health, from its last verdict ([lastStatus], as
     * the health challenge writes it) and whether its recent passing rounds
     * were slow ([degraded]): the reading the dashboard gives the roster.
     * Anything the challenge has not concluded is `unknown`.
     */
    internal fun health(lastStatus: String?, degraded: Boolean): String = when (lastStatus) {
        "success" -> if (degraded) "degraded" else "healthy"
        "failure", "timeout" -> "down"
        else -> "unknown"
    }
}
