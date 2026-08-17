package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ProbeAgents : Table("probe_agents") {
    val id = long("id").autoIncrement()
    val slug = varchar("slug", 64).uniqueIndex()
    val label = varchar("label", 64)
    val agentUri = varchar("agent_uri", 255)
    val publicKey = text("public_key")
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val lastPing = timestamp("last_ping")
    val lastStatus = varchar("last_status", 8)
    val lastPingDelayMs = integer("last_ping_delay_ms")
    val lastPongDeltaMs = integer("last_pong_delta_ms")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
