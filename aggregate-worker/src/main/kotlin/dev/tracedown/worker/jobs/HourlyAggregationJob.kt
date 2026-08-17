package dev.tracedown.worker.jobs

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.HourlyAggregationJob")

/**
 * Rolls up raw probe_results into hourly buckets in probe_aggregates.
 *
 * Runs every 15 minutes, looking back 3 hours to capture late-arriving results.
 * Produces per-agent rows and an all-agents rollup (probe_agent_id IS NULL).
 * Uses idempotent upserts — safe to re-run or overlap.
 *
 * After aggregation, pushes response time percentiles to Redis B so the
 * API gateway can serve them from cache without querying the database.
 */
class HourlyAggregationJob(
    override val intervalSeconds: Long = 900L,
    private val redisB: () -> RedisCommands<String, String>,
) : ScheduledJob {

    override val name = "HourlyAggregationJob"

    override suspend fun execute() {
        val windowEnd = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val windowStart = windowEnd.minus(3, ChronoUnit.HOURS)
        val tsStart = java.sql.Timestamp.from(windowStart)
        val tsEnd = java.sql.Timestamp.from(windowEnd)

        newSuspendedTransaction(Dispatchers.IO) {
            val conn = this.connection.connection as java.sql.Connection

            // Per-agent aggregation — ON CONFLICT works because probe_agent_id is NOT NULL
            conn.prepareStatement(PER_AGENT_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            // All-agents rollup — probe_agent_id is NULL, so ON CONFLICT won't match.
            // Delete existing rollup rows in the window, then re-insert.
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

            // Push percentiles to Redis B cache
            try {
                updatePercentilesCache(conn)
            } catch (e: Exception) {
                log.warn("Failed to update percentiles cache in Redis B", e)
            }
        }

        log.info("Hourly aggregation completed for window [{}, {})", windowStart, windowEnd)
    }

    /**
     * Reads the latest all-agents rollup percentiles from probe_aggregates
     * and writes them to Redis B per service.
     */
    private fun updatePercentilesCache(conn: java.sql.Connection) {
        val redis = redisB()
        conn.prepareStatement(PERCENTILES_SQL).use { stmt ->
            val rs = stmt.executeQuery()
            var count = 0
            while (rs.next()) {
                val serviceId = rs.getString("service_id")
                val p50 = rs.getLong("p50")
                val p95 = rs.getLong("p95")
                val p99 = rs.getLong("p99")
                val key = "metrics:svc:$serviceId:percentiles"
                redis.hset(key, mapOf(
                    "p50" to p50.toString(),
                    "p95" to p95.toString(),
                    "p99" to p99.toString(),
                ))
                redis.expire(key, 86400)
                count++
            }
            if (count > 0) {
                log.info("Updated percentiles cache for {} services", count)
            }
        }
    }

    companion object {
        private val PER_AGENT_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                probe_agent_id,
                date_trunc('hour', started_at) AS bucket_start,
                'hourly',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, probe_agent_id, date_trunc('hour', started_at)
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
              AND bucket_type = 'hourly'
              AND bucket_start >= ?
              AND bucket_start < ?
        """.trimIndent()

        /** Computes weighted-average percentiles across all hourly rollup buckets per service. */
        private val PERCENTILES_SQL = """
            SELECT
                service_id,
                (SUM(p50_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p50,
                (SUM(p95_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p95,
                (SUM(p99_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p99
            FROM probe_aggregates
            WHERE probe_agent_id IS NULL
              AND bucket_type = 'hourly'
              AND p50_ms IS NOT NULL
            GROUP BY service_id
        """.trimIndent()

        private val INSERT_ROLLUP_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                NULL,
                date_trunc('hour', started_at) AS bucket_start,
                'hourly',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, date_trunc('hour', started_at)
        """.trimIndent()
    }
}
