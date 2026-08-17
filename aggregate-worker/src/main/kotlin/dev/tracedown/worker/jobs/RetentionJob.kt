package dev.tracedown.worker.jobs

import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.RetentionJob")

/**
 * Purges raw probe data (results, steps, snapshots) older than the retention period.
 *
 * Runs every 1 hour. Per-org retention is resolved via [PlatformDefaults.retentionConfig],
 * which defaults to the global value from config unless an external module overrides it.
 * Deletes stored response bodies via [BodyStorageClient], then removes
 * DB rows in leaf-first order to respect FK constraints.
 */
class RetentionJob(
    private val defaultRetentionDays: Int,
    private val storageClient: BodyStorageClient,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "RetentionJob"

    override suspend fun execute() {
        if (defaultRetentionDays <= 0) {
            log.debug("Retention disabled (resultRetentionDays={})", defaultRetentionDays)
            return
        }

        // Find distinct orgs with results older than the shortest possible retention (1 day)
        val cutoffScan = Instant.now().minus(1, ChronoUnit.DAYS)
        val orgIds = newSuspendedTransaction(Dispatchers.IO) {
            ProbeResults.selectAll()
                .where { ProbeResults.startedAt less cutoffScan }
                .withDistinct()
                .map { it[ProbeResults.organizationId] }
                .distinct()
        }

        if (orgIds.isEmpty()) return

        var totalDeleted = 0L

        for (orgId in orgIds) {
            val retentionDays = PlatformDefaults.retentionConfig.resultRetentionDays(orgId)
                .let { if (it <= 0) defaultRetentionDays else it }
            val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)

            val deleted = newSuspendedTransaction(Dispatchers.IO) {
                // Find result IDs to delete for this org
                val resultIds = ProbeResults.selectAll()
                    .where { (ProbeResults.organizationId eq orgId) and (ProbeResults.startedAt less cutoff) }
                    .map { it[ProbeResults.id] }

                if (resultIds.isEmpty()) return@newSuspendedTransaction 0L

                // Delete stored response bodies before removing DB rows
                val bodyUris = ProbeSteps.selectAll()
                    .where { ProbeSteps.probeResultId inList resultIds }
                    .mapNotNull { it[ProbeSteps.responseBodyStorageUrl] }

                for (uri in bodyUris) {
                    try {
                        storageClient.delete(uri)
                    } catch (e: Exception) {
                        log.warn("Failed to delete body at {}: {}", uri, e.message)
                    }
                }

                // Leaf-first: steps, then results
                ProbeSteps.deleteWhere { probeResultId inList resultIds }
                val count = ProbeResults.deleteWhere { id inList resultIds }
                count.toLong()
            }

            if (deleted > 0) {
                log.info("Retention: deleted {} results for org {} (retention={}d)", deleted, orgId, retentionDays)
            }
            totalDeleted += deleted
        }

        if (totalDeleted > 0) {
            log.info("Retention job completed: {} total results purged across {} orgs", totalDeleted, orgIds.size)
        }
    }
}
