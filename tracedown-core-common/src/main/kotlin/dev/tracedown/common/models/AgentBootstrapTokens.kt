package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AgentBootstrapTokens : Table("agent_bootstrap_tokens") {
    val id = uuid("id")
    val slug = varchar("slug", 64)
    val label = varchar("label", 64)
    val tokenHash = varchar("token_hash", 255)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val usedAt = timestamp("used_at").nullable()
    val createdBy = uuid("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
