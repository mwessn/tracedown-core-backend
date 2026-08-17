package dev.tracedown.gateway.cli

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.util.VariableCrypto
import kotlin.system.exitProcess

/**
 * CLI entry point for `--rewrap-org-keys` — platform-key (KEK) rotation for
 * the per-org data-encryption keys.
 *
 * Re-wraps every row of `org_encryption_keys` from the old platform key to
 * the new one. The per-org DEKs themselves do not change, so secret-variable
 * ciphertexts stay valid — only their key wrapping is rotated. Idempotent:
 * rows already wrapped with the new key are skipped, so the command is safe
 * to re-run after a partial failure.
 *
 * Note the platform key is also used outside the org-key envelope (TOTP
 * secrets, the CA root key, non-secret encrypted variables); this command
 * rotates only the org DEK wrapping.
 *
 * Usage:
 *   PLATFORM_AES_KEY=<new key> PLATFORM_AES_KEY_OLD=<old key> \
 *     java -jar api-gateway.jar --rewrap-org-keys
 */
object RewrapOrgKeys {

    /** Parses CLI args and runs the re-wrap. Returns true if handled. */
    fun handle(args: Array<String>): Boolean {
        if (!args.contains("--rewrap-org-keys")) return false
        run()
        return true
    }

    private fun run() {
        val dbUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/tracedown"
        val dbUser = System.getenv("DATABASE_USER") ?: "tracedown"
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""

        val newKey = System.getenv("PLATFORM_AES_KEY") ?: run {
            System.err.println("ERROR: PLATFORM_AES_KEY (the new key) is not set")
            exitProcess(1)
        }
        val oldKey = System.getenv("PLATFORM_AES_KEY_OLD") ?: run {
            System.err.println("ERROR: PLATFORM_AES_KEY_OLD (the key being rotated out) is not set")
            exitProcess(1)
        }

        VariableCrypto.init(newKey)
        val ds = DatabaseFactory.init(dbUrl, dbUser, dbPassword, maximumPoolSize = 2)

        try {
            val result = VariableCrypto.rewrapOrgKeys(oldKey)
            println()
            println("Org data-encryption keys re-wrapped.")
            println("  Re-wrapped:      ${result.rewrapped}")
            println("  Already current: ${result.alreadyCurrent}")
            println("  Failed:          ${result.failed.size}")
            if (result.failed.isNotEmpty()) {
                System.err.println("ERROR: the following orgs could not be re-wrapped (wrong old key?):")
                result.failed.forEach { System.err.println("  $it") }
                exitProcess(1)
            }
        } finally {
            ds.close()
        }
    }
}
