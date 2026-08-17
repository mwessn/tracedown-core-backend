package dev.tracedown.gateway.data.apikeys

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateApiKeyRequest(
    val name: String,
    val expiresInDays: Int? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.inRange("expiresInDays", expiresInDays, 1..3650)?.let(::add)
    }
}

@Serializable
data class ApiKeySummary(
    val id: String,
    val name: String,
    val key: String? = null,
    val lastUsedAt: String?,
    val expiresAt: String?,
    val revoked: Boolean,
    val createdBy: String,
    val createdAt: String,
)
