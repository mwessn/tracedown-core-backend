package dev.tracedown.worker.jobs

import dev.tracedown.common.models.OutboxStream
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.OutboxPurgeJob")

/**
 * Trims the transactional outbox once consumers have processed its rows.
 *
 * Two consumer styles share the table and both must be respected before a row
 * is removed:
 *
 *  - The fast-path consumer flips `published = true` on the rows it handles
 *    (only its own event type). A row it cares about is kept until published.
 *  - Cursor consumers record their offset in `outbox_cursors`. A row is kept
 *    until every cursor has advanced past its `seq` — the floor is the minimum
 *    offset across all cursors.
 *
 * A row is deleted only when it is past the retention window AND at or below the
 * cursor floor (when any cursor exists) AND either already published or not of
 * the fast-path event type. When no cursor rows exist the floor is absent and
 * behavior collapses to the published/retention rule.
 */
class OutboxPurgeJob(
    private val retentionDays: Int = 7,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "OutboxPurgeJob"

    override suspend fun execute() {
        if (retentionDays <= 0) return

        val floor = OutboxStream.minCursor()

        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            // Never delete above the slowest cursor; when no cursors exist the
            // floor is absent and this clause is dropped entirely. retentionDays
            // and floor are numeric values under our control — safe to inline.
            val cursorClause = if (floor != null) "AND seq <= $floor" else ""
            val sql = """
                DELETE FROM outbox
                WHERE created_at < now() - make_interval(days => $retentionDays)
                  $cursorClause
                  AND (published = true OR event_type <> 'probe_result.created')
            """.trimIndent()
            val stmt = connection.prepareStatement(sql, false)
            stmt.executeUpdate().toLong()
        }

        if (deleted > 0) {
            log.info("Outbox purge: deleted {} rows (retention={}d, cursorFloor={})", deleted, retentionDays, floor)
        }
    }
}
