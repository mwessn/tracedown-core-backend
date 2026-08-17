package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table

object ProjectNotificationTemplates : Table("project_notification_templates") {
    val id = uuid("id")
    val notificationTemplateId = uuid("notification_template_id").references(NotificationTemplates.id)
    val projectId = uuid("project_id").references(Projects.id)

    override val primaryKey = PrimaryKey(id)
}
