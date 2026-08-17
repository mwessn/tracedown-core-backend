package dev.tracedown.gateway.data

import kotlinx.serialization.Serializable

/**
 * A platform-computed, read-only variable (e.g. `$s.name`). Shown in the editor
 * so users can see every value in scope, but never editable or deletable.
 */
@Serializable
data class LockedVariable(
    val key: String,
    val value: String,
    val description: String,
)

/**
 * One scope layer of a resource's variable hierarchy: the resource's own stored
 * variables plus its locked computed variables. Ordered most-specific first
 * (the requested resource), then each ancestor up to the org.
 */
@Serializable
data class VariableScope(
    val scope: String,          // "service" | "project" | "workspace" | "org"
    val prefix: String,         // "$s." | "$p." | "$w." | "$o."
    val resourceId: String,
    val resourceName: String,
    /** True only for the requested resource — ancestors are shown read-only. */
    val editable: Boolean,
    val variables: List<VariableSummary>,
    val locked: List<LockedVariable>,
)

/**
 * The full inherited variable hierarchy for a resource, from the resource itself
 * up through its ancestors to the org. Drives the collapsible variables editor.
 */
@Serializable
data class VariableHierarchyResponse(
    val scopes: List<VariableScope>,
)
