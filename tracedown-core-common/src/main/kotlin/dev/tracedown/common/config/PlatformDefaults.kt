package dev.tracedown.common.config

import java.util.UUID

/**
 * Platform-wide configurable defaults.
 *
 * Sensible defaults are provided via the [Default] objects. External modules
 * may replace these at startup to apply their own limits, filtering, etc.
 */
object PlatformDefaults {
    var orgConfig: OrgConfig = OrgConfig.Default
    var retentionConfig: RetentionConfig = RetentionConfig.Default
    var deliveryConfig: DeliveryConfig = DeliveryConfig.Default
}

/** Organization configuration — controls default group creation and similar org-level behavior. */
interface OrgConfig {

    /** Filters which default groups should be created for a new org. */
    fun filterDefaultGroups(groups: List<GroupDef>): List<GroupDef>

    data class GroupDef(
        val name: String,
        val users: Short,
        val settings: Short,
        val domains: Short,
        val webhooks: Short,
        val notifications: Short,
        val admin: Short,
        val workspaces: Short,
        /** Access levels for extension permission sections, keyed by section key. */
        val extraPerms: Map<String, Short> = emptyMap(),
    )

    /** Default: returns all groups unchanged. */
    object Default : OrgConfig {
        override fun filterDefaultGroups(groups: List<GroupDef>): List<GroupDef> = groups
    }
}

/** Data retention configuration — controls how long probe results are kept. */
interface RetentionConfig {

    /** Returns the result retention period in days for an organization, or -1 to use the global default. */
    fun resultRetentionDays(orgId: UUID): Int

    /** Default: -1 (use global config value). */
    object Default : RetentionConfig {
        override fun resultRetentionDays(orgId: UUID): Int = -1
    }
}

/** External delivery configuration — controls which delivery channels are available. */
interface DeliveryConfig {

    /** Whether the org can use external delivery channels (mobile push, etc.). */
    fun canUseExternalDelivery(orgId: UUID): Boolean

    /** Default: external delivery disabled. */
    object Default : DeliveryConfig {
        override fun canUseExternalDelivery(orgId: UUID): Boolean = false
    }
}
