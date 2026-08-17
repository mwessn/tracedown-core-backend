package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class InviteRequest(
    val email: String,
    /** Groups the member is placed into immediately (pre-provisioning). */
    val groupIds: List<String>? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("email", email)?.let(::add)
        Validators.email("email", email)?.let(::add)
        Validators.maxLen("email", email, 256)?.let(::add)
        Validators.each(groupIds) { Validators.uuid("groupIds", it) }?.let(::add)
    }
}

@Serializable
data class InviteResponse(val ok: Boolean)

@Serializable
data class InviteInfo(
    val orgName: String,
    val email: String,
    /**
     * True when the invited email already has an account (belongs to another org).
     * Such a user must be signed in to accept — the client never collects a new
     * password/name for them. False = a genuinely new user who sets both here.
     */
    val userExists: Boolean = false,
)

/**
 * Accept body. [password]/[displayName] are only read (and required) for a
 * genuinely new user; an existing account accepts by being signed in, so it sends
 * neither and they stay null.
 */
@Serializable
data class AcceptInviteRequest(
    val password: String? = null,
    val displayName: String? = null,
) : Validatable {
    override fun validate() = buildList {
        // Presence is enforced per-path in the controller; here only cap length.
        Validators.maxLen("password", password, 255)?.let(::add)
        Validators.maxLen("displayName", displayName, 128)?.let(::add)
    }
}

/** Outcome of accepting an invite. */
@Serializable
data class AcceptInviteResponse(
    /** "accepted_new" (session issued) | "accepted_existing" | "login_required". */
    val status: String,
    /** A fresh session token scoped to the joined org (both accepted paths). */
    val token: String? = null,
    /** The invited email (login_required) — so the client can prefill the login prompt. */
    val email: String? = null,
)

/** Accept-invite outcome codes, shared with the frontend. */
object AcceptInviteStatus {
    const val ACCEPTED_NEW = "accepted_new"
    const val ACCEPTED_EXISTING = "accepted_existing"
    const val LOGIN_REQUIRED = "login_required"
}

@Serializable
data class PendingInvite(
    val id: String,
    /** The stub user's id — group membership calls key off it. */
    val userId: String,
    val email: String,
    val invitedAt: String,
    val expiresAt: String,
    /** Pre-assigned groups the member joins on acceptance. */
    val groupIds: List<String> = emptyList(),
    /** Individual org-section levels, pre-configurable like an active member's. */
    val org: OrgSectionPermissions? = null,
)
