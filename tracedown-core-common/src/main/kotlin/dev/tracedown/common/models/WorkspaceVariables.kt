package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object WorkspaceVariables : Table("workspace_variables") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id").references(Workspaces.id)
    val createdBy = uuid("created_by").references(Users.id).nullable()
    val key = varchar("key", 64)
    val value = text("value")
    val secret = bool("secret")
    val encrypted = bool("encrypted").default(true)
    val valueIv = varchar("value_iv", 64).nullable()
    val systemType = varchar("system_type", 8).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
