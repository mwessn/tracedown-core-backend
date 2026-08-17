package dev.tracedown.common.auth

import java.util.Collections

/**
 * Registry of org-level permission section keys.
 *
 * The [BUILTIN] sections are backed by dedicated SMALLINT columns on org_users
 * and org_groups. Additional modules can register further sections at startup
 * via [register]; those are stored in the open `org_extra_perms` JSONB map and
 * flow through the permission cache and API exactly like the built-in sections.
 *
 * Registration is expected during startup wiring. The backing set is thread-safe
 * so concurrent registration and reads are well-defined.
 */
object PermissionSections {

    /** Sections with dedicated columns, always present. */
    val BUILTIN: List<String> = listOf(
        "users", "settings", "domains", "webhooks", "notifications", "admin", "workspaces",
    )

    private val extra: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    /** Registers additional section keys stored in the extension map. */
    fun register(vararg keys: String) {
        for (key in keys) {
            if (key !in BUILTIN) extra.add(key)
        }
    }

    /** The section keys registered by additional modules (excludes built-ins). */
    fun registered(): Set<String> = synchronized(extra) { LinkedHashSet(extra) }

    /** All known section keys: built-ins followed by registered extension sections. */
    fun all(): List<String> = BUILTIN + registered()
}
