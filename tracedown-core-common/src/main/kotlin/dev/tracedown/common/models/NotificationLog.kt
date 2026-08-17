package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object NotificationLog : Table("notification_log") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val serviceId = uuid("service_id").references(Services.id).nullable()
    val probeResultId = uuid("probe_result_id").references(ProbeResults.id).nullable()
    val channel = varchar("channel", 8)
    val recipient = varchar("recipient", 255)
    val status = varchar("status", 16)
    val attemptCount = short("attempt_count").default(1)
    val error = text("error").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
