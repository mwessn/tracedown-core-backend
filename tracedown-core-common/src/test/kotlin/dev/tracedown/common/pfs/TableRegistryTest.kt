package dev.tracedown.common.pfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The PFS per-table column allowlist is the fix for the extraction oracle: a
 * client must never be able to filter/sort on a secret column of a joined
 * table (e.g. users.password_hash), because several list endpoints apply
 * filters at the SQL level before any per-row permission check.
 */
class TableRegistryTest {

    @Test
    fun `sensitive columns are not resolvable`() {
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("users", "password_hash")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("users", "totp_secret_encrypted")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("sessions", "session_token_hash")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("service_variables", "value")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("service_variables", "value_iv")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("org_users", "invite_token")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("org_domains", "challenge")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("services", "script")
        }
    }

    @Test
    fun `unknown table or column is rejected`() {
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("admin_secrets", "id")
        }
        assertFailsWith<PfsValidationException> {
            TableRegistry.resolveColumn("users", "no_such_column")
        }
    }

    @Test
    fun `allowlisted columns still resolve`() {
        assertEquals("email", TableRegistry.resolveColumn("users", "email").name)
        assertEquals("display_name", TableRegistry.resolveColumn("users", "display_name").name)
        assertEquals("name", TableRegistry.resolveColumn("services", "name").name)
        assertEquals("started_at", TableRegistry.resolveColumn("probe_results", "started_at").name)
        assertEquals("created_at", TableRegistry.resolveColumn("sessions", "created_at").name)
    }
}
