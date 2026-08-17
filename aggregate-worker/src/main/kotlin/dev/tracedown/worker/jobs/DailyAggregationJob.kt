package dev.tracedown.worker.jobs

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.DailyAggregationJob")

/**
 * Rolls up raw probe_results into daily buckets in probe_aggregates.
 *
 * Runs every 1 hour, looking back 3 days to capture late-arriving results.
 * Produces per-agent rows and an all-agents rollup (probe_agent_id IS NULL).
 */
class DailyAggregationJob(
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "DailyAggregationJob"

    override suspend fun execute() {
        val windowEnd = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val windowStart = windowEnd.minus(3, ChronoUnit.DAYS)
        val tsStart = java.sql.Timestamp.from(windowStart)
        val tsEnd = java.sql.Timestamp.from(windowEnd)

        newSuspendedTransaction(Dispatchers.IO) {
            val conn = this.connection.connection as java.sql.Connection

            // Per-agent aggregation
            conn.prepareStatement(PER_AGENT_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            // All-agents rollup (delete + insert for NULL probe_agent_id)
            conn.prepareStatement(DELETE_ROLLUP_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }
            conn.prepareStatement(INSERT_ROLLUP_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }
        }

        log.info("Daily aggregation completed for window [{}, {})", windowStart, windowEnd)
    }

    companion object {
        private val PER_AGENT_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                probe_agent_id,
                date_trunc('day', started_at) AS bucket_start,
                'daily',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, probe_agent_id, date_trunc('day', started_at)
            ON CONFLICT (service_id, probe_agent_id, bucket_start, bucket_type)
            DO UPDATE SET
                p50_ms = EXCLUDED.p50_ms,
                p95_ms = EXCLUDED.p95_ms,
                p99_ms = EXCLUDED.p99_ms,
                error_rate = EXCLUDED.error_rate,
                uptime_pct = EXCLUDED.uptime_pct,
                probe_count = EXCLUDED.probe_count
        """.trimIndent()

        private val DELETE_ROLLUP_SQL = """
            DELETE FROM probe_aggregates
            WHERE probe_agent_id IS NULL
              AND bucket_type = 'daily'
              AND bucket_start >= ?
              AND bucket_start < ?
        """.trimIndent()

        private val INSERT_ROLLUP_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                NULL,
                date_trunc('day', started_at) AS bucket_start,
                'daily',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, date_trunc('day', started_at)
        """.trimIndent()
    }
}
