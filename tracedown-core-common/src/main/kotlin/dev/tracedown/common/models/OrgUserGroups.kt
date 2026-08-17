package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table

object OrgUserGroups : Table("org_user_groups") {
    val id = uuid("id")
    val orgUserId = uuid("org_user_id").references(OrgUsers.id)
    val orgGroupId = uuid("org_group_id").references(OrgGroups.id)

    override val primaryKey = PrimaryKey(id)
}
