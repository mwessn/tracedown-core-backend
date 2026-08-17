package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object GrafanaIntegrations : Table("grafana_integrations") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val projectId = uuid("project_id").references(Projects.id)
    val name = varchar("name", 64)
    val config = jsonb<JsonObject>("config", Json.Default)
    val enabled = bool("enabled").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
