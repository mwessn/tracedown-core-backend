package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * User-defined Lace script presets. `workspaceId` null = visible across the
 * org; set = visible only within that workspace.
 */
object OrgRulePresets : Table("org_rule_presets") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val workspaceId = uuid("workspace_id").references(Workspaces.id).nullable()
    // Provenance only — cleared (ON DELETE SET NULL) when the creator's account is erased.
    val createdBy = uuid("created_by").references(Users.id).nullable()
    val displayName = varchar("display_name", 128)
    val script = text("script")
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
