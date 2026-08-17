package dev.tracedown.gateway.routes.v1.agents

import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class AgentStatus(
    val agentSlug: String,
    val status: String,
    val lastCheck: String?,
    val lastResponseMs: Int?,
)

@Serializable
data class AgentHealthResponse(
    val statuses: List<AgentStatus>,
)

/**
 * @OpenAPITag Agents
 * Probe agent health status.
 */
@Resource("/api/v1/agents")
class Agents {
    @Resource("health")
    class Health(val parent: Agents = Agents())
}

/** Registers public agent health routes. Available to any authenticated user. */
fun Route.agentRoutes() {
    /** Returns health status of all registered probe agents. No pagination. */
    get<Agents.Health> {
        requireAuth(call)

        val statuses = transaction {
            ProbeAgents.selectAll()
                .where { (ProbeAgents.isActive eq true) and (ProbeAgents.deleted eq false) }
                .map { row ->
                    AgentStatus(
                        agentSlug = row[ProbeAgents.slug],
                        status = row[ProbeAgents.lastStatus],
                        lastCheck = row[ProbeAgents.lastPing].toString(),
                        lastResponseMs = row[ProbeAgents.lastPongDeltaMs],
                    )
                }
        }

        call.respond(AgentHealthResponse(statuses = statuses))
    }
}
