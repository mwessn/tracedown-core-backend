package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Organizations : Table("organizations") {
    val id = uuid("id")
    val name = varchar("name", 128)
    val ownerId = uuid("owner_id").references(Users.id)
    val totpRequired = bool("totp_required").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val defaultTimezone = varchar("default_timezone", 64).default("UTC")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
