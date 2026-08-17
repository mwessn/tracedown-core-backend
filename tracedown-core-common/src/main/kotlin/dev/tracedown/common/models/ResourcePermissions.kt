package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table

object ResourcePermissions : Table("resource_permissions") {
    val id = uuid("id")
    val orgId = uuid("org_id").references(Organizations.id)
    val principalType = varchar("principal_type", 16)
    val principalId = uuid("principal_id")
    val resourceType = varchar("resource_type", 16)
    val resourceId = uuid("resource_id")
    val permissions = short("permissions").default(0)

    override val primaryKey = PrimaryKey(id)
}
