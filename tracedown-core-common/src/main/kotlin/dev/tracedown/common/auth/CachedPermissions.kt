package dev.tracedown.common.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Deserialized view of the permission_cache JSONB on org_users.
 *
 * Structure:
 * ```json
 * {
 *   "org": { "users": 2, "settings": 1, "domains": 0, "webhooks": 0, "notifications": 0, "admin": 0, "workspaces": 2 },
 *   "resources": { "workspace::uuid": 2, "project::uuid": 1, "service::uuid": 1 },
 *   "totp_required": true
 * }
 * ```
 */
data class CachedPermissions(
    val org: OrgPermissions,
    val resources: Map<String, Short>,
    val totpRequired: Boolean,
) {
    /** Checks resource-level access. Key format: "workspace::uuid", "project::uuid", "service::uuid". */
    fun resourceAccess(resourceType: String, resourceId: String): Short {
        return resources["$resourceType::$resourceId"] ?: AccessLevel.NONE
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("org", buildJsonObject {
            put("users", org.users.toInt())
            put("settings", org.settings.toInt())
            put("domains", org.domains.toInt())
            put("webhooks", org.webhooks.toInt())
            put("notifications", org.notifications.toInt())
            put("admin", org.admin.toInt())
            put("workspaces", org.workspaces.toInt())
            // Registered extension sections live as flat siblings of the
            // built-in keys, keeping the wire/cache format uniform.
            for ((key, value) in org.extra) {
                put(key, value.toInt())
            }
        })
        put("resources", buildJsonObject {
            for ((key, value) in resources) {
                put(key, value.toInt())
            }
        })
        put("totp_required", totpRequired)
    }

    companion object {
        fun fromJsonObject(json: JsonObject): CachedPermissions {
            val orgJson = json["org"]?.jsonObject
            val resourcesJson = json["resources"]?.jsonObject
            val totpRequired = json["totp_required"]?.jsonPrimitive?.content?.toBoolean() ?: false

            val org = if (orgJson != null) OrgPermissions(
                users = orgJson["users"]?.jsonPrimitive?.int?.toShort() ?: 0,
                settings = orgJson["settings"]?.jsonPrimitive?.int?.toShort() ?: 0,
                domains = orgJson["domains"]?.jsonPrimitive?.int?.toShort() ?: 0,
                webhooks = orgJson["webhooks"]?.jsonPrimitive?.int?.toShort() ?: 0,
                notifications = orgJson["notifications"]?.jsonPrimitive?.int?.toShort() ?: 0,
                admin = orgJson["admin"]?.jsonPrimitive?.int?.toShort() ?: 0,
                workspaces = orgJson["workspaces"]?.jsonPrimitive?.int?.toShort() ?: 0,
                // Any remaining flat keys are registered extension sections.
                extra = orgJson.entries
                    .filter { it.key !in PermissionSections.BUILTIN }
                    .associate { it.key to it.value.jsonPrimitive.int.toShort() },
            ) else OrgPermissions(0, 0, 0, 0, 0, 0, 0)

            val resources = resourcesJson?.entries?.associate { (key, value) ->
                key to (value.jsonPrimitive.int.toShort())
            } ?: emptyMap()

            return CachedPermissions(org, resources, totpRequired)
        }
    }
}
