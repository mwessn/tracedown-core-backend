package dev.tracedown.realtime.auth

import dev.tracedown.common.auth.SessionAuthenticator
import dev.tracedown.common.auth.SessionResult
import java.util.UUID

/**
 * Result of a successful session validation.
 */
data class AuthenticatedSession(
    val userId: UUID,
    val sessionId: UUID,
    val orgId: UUID,
)

/**
 * Validates session tokens for the WebSocket handshake.
 *
 * Delegates to the shared [SessionAuthenticator] so session validity is defined
 * in exactly one place across the gateway and realtime-service. Realtime requires
 * an org-scoped session, so a valid session with no org resolves to null.
 */
object SessionValidator {

    /**
     * Returns the authenticated session, or null if the token is invalid, expired,
     * revoked, the user is inactive, or the session has no organization.
     */
    fun validate(token: String): AuthenticatedSession? {
        val result = SessionAuthenticator.authenticate(token)
        if (result !is SessionResult.Valid) return null

        val orgId = result.context.organizationId ?: return null
        return AuthenticatedSession(
            userId = result.context.userId,
            sessionId = result.context.sessionId,
            orgId = orgId,
        )
    }
}
