package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AgentHealthChecks : Table("agent_health_checks") {
    val id = uuid("id")
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id)
    val challengeId = varchar("challenge_id", 64).uniqueIndex()
    val challengedAt = timestamp("challenged_at")
    val respondedAt = timestamp("responded_at").nullable()
    val roundTripMs = integer("round_trip_ms").nullable()
    val result = varchar("result", 16)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
