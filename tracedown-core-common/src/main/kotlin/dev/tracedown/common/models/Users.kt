package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val id = uuid("id")
    val email = varchar("email", 256)
    val passwordHash = varchar("password_hash", 256)
    val displayName = varchar("display_name", 128)
    val totpSecretEncrypted = varchar("totp_secret_encrypted", 512).nullable()
    val totpSecretIv = varchar("totp_secret_iv", 32).nullable()
    val totpEnrolledAt = timestamp("totp_enrolled_at").nullable()
    val totpLastUsedAt = timestamp("totp_last_used_at").nullable()
    val totpEnabled = bool("totp_enabled").default(false)
    val selectedOrgId = uuid("selected_org_id").references(Organizations.id).nullable()
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
