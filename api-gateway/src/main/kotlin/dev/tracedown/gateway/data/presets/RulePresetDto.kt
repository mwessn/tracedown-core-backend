package dev.tracedown.gateway.data.presets

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

/** One script preset as offered by the editor's template picker. */
@Serializable
data class RulePresetSummary(
    val id: String,
    val name: String,
    val script: String,
    /** "org" (org-wide) or "workspace" (scoped). */
    val scope: String,
    /** The workspace a scoped preset belongs to; null for an org-wide one. */
    val workspaceId: String? = null,
)

/** Creates an org preset; `workspaceId` scopes it to one workspace. */
@Serializable
data class CreateRulePresetRequest(
    val name: String,
    val script: String,
    val workspaceId: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.notBlank("script", script)?.let(::add)
        Validators.maxLen("script", script, 16384)?.let(::add)
        Validators.uuid("workspaceId", workspaceId)?.let(::add)
    }
}

/** Renames a preset or replaces its script. Fields left out are unchanged; its scope does not move. */
@Serializable
data class UpdateRulePresetRequest(
    val name: String? = null,
    val script: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.maxLen("script", script, 16384)?.let(::add)
    }
}
