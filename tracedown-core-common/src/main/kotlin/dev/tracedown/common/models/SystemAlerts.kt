package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object SystemAlerts : Table("system_alerts") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val alertType = varchar("alert_type", 64)
    val subject = varchar("subject", 128).default("")
    val severity = varchar("severity", 16).default("warning")
    val data = jsonb<JsonObject>("data", Json.Default).nullable()
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")

    override val primaryKey = PrimaryKey(id)
}

object SystemAlertDismissals : Table("system_alert_dismissals") {
    val id = uuid("id")
    val alertId = uuid("alert_id").references(SystemAlerts.id)
    val userId = uuid("user_id").references(Users.id)
    val dismissedAt = timestamp("dismissed_at")

    override val primaryKey = PrimaryKey(id)
}
