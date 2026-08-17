package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.Table

object NotificationSilences : Table("notification_silences") {
    val id = uuid("id")
    val orgUserId = uuid("org_user_id").references(OrgUsers.id)
    val workspaceId = uuid("workspace_id").references(Workspaces.id).nullable()
    val projectId = uuid("project_id").references(Projects.id).nullable()
    val serviceId = uuid("service_id").references(Services.id).nullable()
    val channel = varchar("channel", 16)
    val config = jsonb<JsonElement>("config", Json.Default).nullable()
    val quietHours = varchar("quiet_hours", 256).nullable()

    override val primaryKey = PrimaryKey(id)
}
