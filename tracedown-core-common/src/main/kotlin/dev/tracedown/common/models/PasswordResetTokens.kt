package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object PasswordResetTokens : Table("password_reset_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 255)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
