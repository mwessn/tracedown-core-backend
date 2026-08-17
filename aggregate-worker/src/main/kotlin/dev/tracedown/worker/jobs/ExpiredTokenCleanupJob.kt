package dev.tracedown.worker.jobs

import dev.tracedown.common.models.PasswordResetTokens
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.ExpiredTokenCleanupJob")

/**
 * Deletes expired password-reset tokens. Expired tokens are dead credential
 * material with no operational or historical value — they only linger as
 * hashes tied to an account. Used-but-unexpired tokens are kept until expiry
 * (the `used` flag is what blocks replay); they fall out here shortly after.
 *
 * No retention knob: there is no reason to keep an expired token, ever.
 */
class ExpiredTokenCleanupJob(
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "ExpiredTokenCleanupJob"

    override suspend fun execute() {
        val now = Instant.now()
        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            PasswordResetTokens.deleteWhere { expiresAt less now }
        }
        if (deleted > 0) {
            log.info("Expired token cleanup: {} password reset tokens deleted", deleted)
        }
    }
}
