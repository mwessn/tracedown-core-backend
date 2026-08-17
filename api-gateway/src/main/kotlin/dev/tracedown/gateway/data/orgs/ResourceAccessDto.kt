package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

/** One principal holding a grant on a resource. */
@Serializable
data class ResourceAccessEntry(
    /** "user" or "group". */
    val principalType: String,
    /** userId for users, groupId for groups. */
    val principalId: String,
    val name: String,
    val email: String? = null,
    val permissions: Short,
)

/** Grants or updates one principal's level (1 read / 2 write) on a resource. */
@Serializable
data class UpsertAccessRequest(
    val principalType: String,
    val principalId: String,
    val permissions: Short,
) : Validatable {
    override fun validate() = buildList {
        Validators.oneOf("principalType", principalType, setOf("user", "group"))?.let(::add)
        Validators.notBlank("principalId", principalId)?.let(::add)
        Validators.uuid("principalId", principalId)?.let(::add)
        Validators.inRange("permissions", permissions.toInt(), 1..2)?.let(::add)
    }
}
