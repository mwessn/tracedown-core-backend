package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object TotpRecoveryCodes : Table("totp_recovery_codes") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id)
    val codeHash = varchar("code_hash", 255)
    val used = bool("used").default(false)
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
