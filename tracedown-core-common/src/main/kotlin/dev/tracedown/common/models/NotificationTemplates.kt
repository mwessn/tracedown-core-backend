package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object NotificationTemplates : Table("notification_templates") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val name = varchar("name", 64)
    val text = text("text")
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
