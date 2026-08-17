package dev.tracedown.worker.jobs

import dev.tracedown.common.storage.BodyStorageClient
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.PurgeJob")

/** Executes raw SQL and returns the update count. */
private fun Transaction.execCount(sql: String): Long =
    connection.prepareStatement(sql, false).executeUpdate().toLong()

/**
 * Physically deletes soft-deleted rows whose purge_after timestamp has passed.
 *
 * Runs every 5 minutes. Each entity group (services, projects, workspaces,
 * organizations, users, and the individual leaf tables) is purged in its **own
 * transaction**: a failure in one group is logged and skipped, the remaining
 * groups still purge, and the failed group is retried on the next run. One bad
 * row must never stall erasure platform-wide.
 *
 * Within a group, deletes cascade leaf-first so FK constraints hold even where
 * children carry no purge_after of their own. Data-preserving links (audit-log
 * actor, created_by provenance, session/user org selection, last-run pointer,
 * notification-log sources) are cleared automatically by ON DELETE SET NULL
 * actions declared in the schema.
 *
 * Stored response bodies referenced by probe_steps rows are deleted from body
 * storage *before* the rows are purged, mirroring [RetentionJob]. A failing
 * storage backend never blocks the database purge — failures are logged loudly
 * and the orphaned objects remain in the bucket.
 */
class PurgeJob(
    private val storageClient: BodyStorageClient = BodyStorageClient(),
    override val intervalSeconds: Long = 300L,
) : ScheduledJob {

    override val name = "PurgeJob"

    override suspend fun execute() {
        var totalDeleted = 0L
        var failedGroups = 0

        for (unit in purgeUnits) {
            try {
                totalDeleted += newSuspendedTransaction(Dispatchers.IO) { unit.purge(this) }
            } catch (e: Exception) {
                failedGroups++
                log.error(
                    "Purge of {} failed — other entities are unaffected; this group is retried next run",
                    unit.entity, e,
                )
            }
        }

        if (totalDeleted > 0 || failedGroups > 0) {
            log.info("Purge job completed: {} rows deleted, {} entity group(s) failed", totalDeleted, failedGroups)
        }
    }

    /** One independently-purged entity group. Runs inside its own transaction. */
    private class PurgeUnit(val entity: String, val purge: Transaction.() -> Long)

    private val purgeUnits: List<PurgeUnit> = buildList {
        // ── Crypto-shredding: purging orgs lose their data-encryption key FIRST ──
        // Deleting the org_encryption_keys row destroys the only copy of the
        // org's DEK, which renders every secret-variable ciphertext of the org
        // permanently undecryptable. Doing it in its own transaction, before
        // any data rows are touched, means the secrets are already unreadable
        // even if a later cascade step fails and leaves rows behind until the
        // next run. (The FK's ON DELETE CASCADE would remove the row anyway
        // when the organization hard-deletes — the explicit early delete is
        // the point.)
        add(PurgeUnit("org_encryption_keys") {
            execCount("DELETE FROM org_encryption_keys WHERE org_id IN ($PURGING_ORGS)")
        })

        // ── Leaf tables with their own purge_after (no dependents) ──
        for (table in listOf(
            "service_variables", "project_variables", "workspace_variables", "org_variables",
            "org_domains", "api_keys", "org_rule_presets", "grafana_integrations",
        )) {
            add(PurgeUnit(table) { execCount(PURGE_OWN.format(table)) })
        }

        // Webhook delivery configs: resource bindings die with the delivery.
        add(PurgeUnit("webhook_deliveries") {
            execCount(
                "DELETE FROM resource_webhook_access WHERE webhook_delivery_id IN " +
                    "(SELECT id FROM webhook_deliveries WHERE $PURGE_DUE)"
            ) + execCount(PURGE_OWN.format("webhook_deliveries"))
        })

        // Notification templates: project bindings die with the template.
        add(PurgeUnit("notification_templates") {
            execCount(
                "DELETE FROM project_notification_templates WHERE notification_template_id IN " +
                    "(SELECT id FROM notification_templates WHERE $PURGE_DUE)"
            ) + execCount(PURGE_OWN.format("notification_templates"))
        })

        // ── Container entities, each cascading its dependents ──
        add(PurgeUnit("services") {
            deleteStoredBodies(RESULTS_OF_PURGING_SERVICES)
            CASCADE_SERVICES.sumOf { execCount(it) }
        })
        add(PurgeUnit("projects") {
            deleteStoredBodies(RESULTS_OF_PURGING_PROJECTS)
            CASCADE_PROJECTS.sumOf { execCount(it) }
        })
        add(PurgeUnit("workspaces") {
            deleteStoredBodies(RESULTS_OF_PURGING_WORKSPACES)
            CASCADE_WORKSPACES.sumOf { execCount(it) }
        })
        add(PurgeUnit("organizations") {
            deleteStoredBodies(RESULTS_OF_PURGING_ORGS)
            CASCADE_ORGANIZATIONS.sumOf { execCount(it) }
        })

        // Users last: an organization purging in the same run is gone by now,
        // so its (also-purging) owner no longer trips the ownership guard.
        add(PurgeUnit("users") { purgeUsers() })
    }

    /**
     * Deletes the stored response bodies of every probe_steps row about to be
     * purged (steps of [purgingResults]), so storage objects never outlive the
     * rows pointing at them. Storage failures are logged and tolerated — a
     * broken bucket must not stop the database purge.
     */
    private fun Transaction.deleteStoredBodies(purgingResults: String) {
        val uris = mutableListOf<String>()
        exec(
            "SELECT response_body_storage_url FROM probe_steps " +
                "WHERE response_body_storage_url IS NOT NULL AND probe_result_id IN ($purgingResults)"
        ) { rs ->
            while (rs.next()) uris.add(rs.getString(1))
        }

        var failed = 0
        for (uri in uris) {
            try {
                storageClient.delete(uri)
            } catch (e: Exception) {
                failed++
                log.error("Failed to delete stored response body {} — object is orphaned in storage: {}", uri, e.message)
            }
        }
        if (failed > 0) {
            log.error("Purge: {} of {} stored bodies could not be deleted and are now orphaned", failed, uris.size)
        }
    }

    /**
     * Purges soft-deleted user accounts, skipping (and loudly reporting) any
     * account still recorded as an organization's owner — erasing it would
     * orphan the organization. Ownership must be transferred or the
     * organization deleted first; the account stays until then.
     */
    private fun Transaction.purgeUsers(): Long {
        val blockedOwners = mutableListOf<String>()
        exec("SELECT id FROM users WHERE $PURGE_DUE AND id IN (SELECT owner_id FROM organizations)") { rs ->
            while (rs.next()) blockedOwners.add(rs.getString(1))
        }
        if (blockedOwners.isNotEmpty()) {
            log.error(
                "User purge skipped for {} account(s) that still own an organization: {} — " +
                    "transfer ownership or delete the organization first",
                blockedOwners.size, blockedOwners,
            )
        }

        return CASCADE_USERS.sumOf { execCount(it) }
    }

    companion object {
        private const val PURGE_DUE = "purge_after IS NOT NULL AND purge_after < now()"
        private const val PURGE_OWN = "DELETE FROM %s WHERE $PURGE_DUE"

        // ── Purge-scope subqueries ──
        private const val PURGING_SERVICES = "SELECT id FROM services WHERE $PURGE_DUE"
        private const val PURGING_PROJECTS = "SELECT id FROM projects WHERE $PURGE_DUE"
        private const val PURGING_WORKSPACES = "SELECT id FROM workspaces WHERE $PURGE_DUE"
        private const val PURGING_ORGS = "SELECT id FROM organizations WHERE $PURGE_DUE"

        private const val SERVICES_OF_PURGING_PROJECTS =
            "SELECT id FROM services WHERE project_id IN ($PURGING_PROJECTS)"
        private const val SERVICES_OF_PURGING_WORKSPACES =
            "SELECT s.id FROM services s JOIN projects p ON s.project_id = p.id " +
                "WHERE p.workspace_id IN ($PURGING_WORKSPACES)"
        private const val SERVICES_OF_PURGING_ORGS =
            "SELECT s.id FROM services s JOIN projects p ON s.project_id = p.id " +
                "JOIN workspaces w ON p.workspace_id = w.id WHERE w.organization_id IN ($PURGING_ORGS)"

        private const val RESULTS_OF_PURGING_SERVICES =
            "SELECT id FROM probe_results WHERE service_id IN ($PURGING_SERVICES)"
        private const val RESULTS_OF_PURGING_PROJECTS =
            "SELECT id FROM probe_results WHERE service_id IN ($SERVICES_OF_PURGING_PROJECTS)"
        private const val RESULTS_OF_PURGING_WORKSPACES =
            "SELECT id FROM probe_results WHERE service_id IN ($SERVICES_OF_PURGING_WORKSPACES)"
        private const val RESULTS_OF_PURGING_ORGS =
            "SELECT id FROM probe_results WHERE organization_id IN ($PURGING_ORGS)"

        /** Service-level dependents, leaf-first, for the given service/result scope. */
        private fun serviceLevelCascade(services: String, results: String) = listOf(
            "DELETE FROM probe_steps WHERE probe_result_id IN ($results)",
            "DELETE FROM probe_results WHERE id IN ($results)",
            "DELETE FROM probe_aggregates WHERE service_id IN ($services)",
            "DELETE FROM service_allowed_agents WHERE service_id IN ($services)",
            "DELETE FROM notification_silences WHERE service_id IN ($services)",
            "DELETE FROM service_variables WHERE service_id IN ($services)",
            "DELETE FROM services WHERE id IN ($services)",
        )

        // ── Service cascade ──
        private val CASCADE_SERVICES =
            serviceLevelCascade(PURGING_SERVICES, RESULTS_OF_PURGING_SERVICES)

        // ── Project cascade ──
        private val CASCADE_PROJECTS =
            serviceLevelCascade(SERVICES_OF_PURGING_PROJECTS, RESULTS_OF_PURGING_PROJECTS) + listOf(
                "DELETE FROM notification_silences WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM project_variables WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM grafana_integrations WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM projects WHERE id IN ($PURGING_PROJECTS)",
            )

        // ── Workspace cascade ──
        private val CASCADE_WORKSPACES =
            serviceLevelCascade(SERVICES_OF_PURGING_WORKSPACES, RESULTS_OF_PURGING_WORKSPACES) + listOf(
                """DELETE FROM notification_silences WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                """DELETE FROM project_variables WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                """DELETE FROM grafana_integrations WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                "DELETE FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM notification_silences WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM workspace_variables WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM workspaces WHERE id IN ($PURGING_WORKSPACES)",
            )

        // ── Organization cascade ──
        // sessions.organization_id and users.selected_org_id are cleared by
        // ON DELETE SET NULL when the organization row goes — sessions and
        // accounts belong to users, not to the organization.
        private val CASCADE_ORGANIZATIONS = listOf(
            // Delivery history is org-scoped and dies with the org. Removed
            // before results/services so their FK SET NULL actions don't churn
            // rows that are about to disappear anyway.
            "DELETE FROM notification_log WHERE organization_id IN ($PURGING_ORGS)",
            // Every silence hangs off a membership of the org; removing them
            // here also clears the ones scoped to the org's services, projects
            // and workspaces before those rows are deleted below.
            """DELETE FROM notification_silences WHERE org_user_id IN (
                SELECT id FROM org_users WHERE organization_id IN ($PURGING_ORGS)
            )""",
        ) + serviceLevelCascade(SERVICES_OF_PURGING_ORGS, RESULTS_OF_PURGING_ORGS) + listOf(
            """DELETE FROM project_variables WHERE project_id IN (
                SELECT p.id FROM projects p JOIN workspaces w ON p.workspace_id = w.id
                WHERE w.organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM grafana_integrations WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM projects WHERE workspace_id IN (
                SELECT id FROM workspaces WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM workspace_variables WHERE workspace_id IN (SELECT id FROM workspaces WHERE organization_id IN ($PURGING_ORGS))",
            "DELETE FROM workspaces WHERE organization_id IN ($PURGING_ORGS)",
            // Resource bindings before the delivery configs they point at.
            "DELETE FROM resource_webhook_access WHERE org_id IN ($PURGING_ORGS)",
            "DELETE FROM webhook_deliveries WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_variables WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_domains WHERE organization_id IN ($PURGING_ORGS)",
            // Group assignments before memberships and groups (either side blocks).
            """DELETE FROM org_user_groups WHERE
                org_user_id IN (SELECT id FROM org_users WHERE organization_id IN ($PURGING_ORGS))
                OR org_group_id IN (SELECT id FROM org_groups WHERE organization_id IN ($PURGING_ORGS))""",
            "DELETE FROM org_users WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_groups WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM resource_permissions WHERE org_id IN ($PURGING_ORGS)",
            "DELETE FROM api_keys WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_rule_presets WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM system_alert_dismissals WHERE alert_id IN (
                SELECT id FROM system_alerts WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM system_alerts WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM project_notification_templates WHERE notification_template_id IN (
                SELECT id FROM notification_templates WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM notification_templates WHERE organization_id IN ($PURGING_ORGS)",
            // Org-scoped audit history dies with the org.
            "DELETE FROM org_audit_log WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM organizations WHERE id IN ($PURGING_ORGS)",
        )

        // ── User cascade ──
        // Accounts still owning an organization are excluded (see purgeUsers).
        // Data-preserving links — org_audit_log.user_id (audit history is kept,
        // actor anonymized), org_users.invited_by and the created_by provenance
        // columns (resources outlive their creator) — are cleared by
        // ON DELETE SET NULL declared in the schema.
        private const val PURGEABLE_USERS =
            "SELECT id FROM users WHERE $PURGE_DUE AND id NOT IN (SELECT owner_id FROM organizations)"
        private const val MEMBERSHIPS_OF_PURGEABLE_USERS =
            "SELECT id FROM org_users WHERE user_id IN ($PURGEABLE_USERS)"

        private val CASCADE_USERS = listOf(
            // Strictly-owned children of the account's memberships.
            "DELETE FROM notification_silences WHERE org_user_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            "DELETE FROM org_user_groups WHERE org_user_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            // Direct resource grants keyed to the account's memberships. The only
            // person-shaped principal is 'org_user' (principal_id = membership id);
            // these are not FK-linked, so they would otherwise dangle when only the
            // user purges but its orgs live on. Runs before org_users so
            // MEMBERSHIPS_OF_PURGEABLE_USERS still resolves. Org-scoped purges clear
            // these via the org cascade instead.
            "DELETE FROM resource_permissions WHERE principal_type = 'org_user' AND principal_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            "DELETE FROM org_users WHERE user_id IN ($PURGEABLE_USERS)",
            // Strictly-owned credential and session material.
            "DELETE FROM sessions WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM password_reset_tokens WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM totp_recovery_codes WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM users WHERE id IN ($PURGEABLE_USERS)",
        )
    }
}
