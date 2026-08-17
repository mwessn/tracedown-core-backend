package dev.tracedown.common.onboarding

/**
 * Definition of a default group created for every new organization. The
 * permission fields are [dev.tracedown.common.auth.AccessLevel] values.
 *
 * [extraPerms] carries access levels for extension permission sections (see
 * [dev.tracedown.common.auth.PermissionSections]) keyed by section key — these
 * are written to the group's open `org_extra_perms` map. Empty by default so a
 * host that registers no extra sections is unaffected.
 */
data class DefaultGroupConfig(
    val name: String,
    val users: Short,
    val settings: Short,
    val domains: Short,
    val webhooks: Short,
    val notifications: Short,
    val admin: Short,
    val workspaces: Short,
    val extraPerms: Map<String, Short> = emptyMap(),
)
