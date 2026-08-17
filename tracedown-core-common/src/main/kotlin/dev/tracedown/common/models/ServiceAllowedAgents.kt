package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table

object ServiceAllowedAgents : Table("service_allowed_agents") {
    val id = uuid("id")
    val serviceId = uuid("service_id").references(Services.id)
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id)

    override val primaryKey = PrimaryKey(id)
}
