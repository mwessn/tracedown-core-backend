package dev.tracedown.gateway.context

import io.ktor.util.AttributeKey
import java.util.UUID

data class AuthPrincipal(
    val userId: UUID,
    val sessionId: UUID,
    val email: String,
    val organizationId: UUID?,
) {
    companion object {
        val attributeKey = AttributeKey<AuthPrincipal>("AuthPrincipal")
    }
}
