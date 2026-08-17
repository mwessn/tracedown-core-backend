package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ResourceWebhookAccess : Table("resource_webhook_access") {
    val id = uuid("id")
    val orgId = uuid("org_id").references(Organizations.id)
    val resourceType = varchar("resource_type", 16)
    val resourceId = uuid("resource_id")
    val webhookDeliveryId = uuid("webhook_delivery_id").references(WebhookDeliveries.id)
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
