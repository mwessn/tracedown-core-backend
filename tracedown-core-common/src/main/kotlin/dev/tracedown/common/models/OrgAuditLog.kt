package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object OrgAuditLog : Table("org_audit_log") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val userId = uuid("user_id").references(Users.id).nullable()
    val action = varchar("action", 64)
    val entityType = varchar("entity_type", 64).nullable()
    val entityId = varchar("entity_id", 64).nullable()
    val entityDisplayName = varchar("entity_display_name", 256).nullable()
    val diff = jsonb<JsonElement>("diff", Json.Default).nullable()
    val comment = text("comment").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
