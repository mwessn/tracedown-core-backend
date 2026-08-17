package dev.tracedown.gateway.data.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntry(
    val id: String,
    val userId: String?,
    /** Actor display name/email, resolved server-side (null for system actions). */
    val actorName: String? = null,
    val actorEmail: String? = null,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    /** What the entity was called at the time of the change (null for system-wide actions). */
    val entityDisplayName: String? = null,
    val diff: String?,
    val comment: String?,
    val createdAt: String,
)

