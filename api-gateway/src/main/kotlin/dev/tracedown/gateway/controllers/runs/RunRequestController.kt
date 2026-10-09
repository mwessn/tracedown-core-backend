package dev.tracedown.gateway.controllers.runs

import dev.tracedown.common.audit.AuditActor
import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.RunRequests
import dev.tracedown.common.models.RunState
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.data.results.RunStatus
import dev.tracedown.gateway.util.NotFoundException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Runs somebody asked for, by the id they were handed for each.
 *
 * The gateway mints the id when the run is asked for and records it here
 * ([record], inside the request's own transaction); the scheduler files the run
 * — its result, or the skipped row saying why it was not made — under the same
 * id in `probe_results`, and the ingestor marks the request settled beside it.
 * [status] reads the two together.
 */
object RunRequestController {

    /** How long a request may go without a result before it reads `expired`. */
    private var expiry: Duration = Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS)

    /** The installation's result window, for an organization with none of its own. */
    private var resultRetentionDays: Int = 90

    const val DEFAULT_EXPIRY_SECONDS = 600L

    /** Set once at startup. */
    fun init(expirySeconds: Long, resultRetentionDays: Int) {
        this.expiry = Duration.ofSeconds(expirySeconds)
        this.resultRetentionDays = resultRetentionDays
    }

    /**
     * Records a request for a run of [serviceId] under [runId], asked for by
     * [userId] (through the API key making the call, if one is). Must be
     * called inside the transaction that authorized it.
     *
     * The row is kept as long as the run's result would be: until
     * [requestedAt] plus the organization's result window, or for good while
     * results are.
     */
    internal fun record(runId: UUID, serviceId: UUID, orgId: UUID, userId: UUID, requestedAt: Instant) {
        val days = PlatformDefaults.retentionConfig.resultRetentionDays(orgId) ?: resultRetentionDays
        RunRequests.insert {
            it[id] = runId
            it[RunRequests.serviceId] = serviceId
            it[organizationId] = orgId
            it[requestedBy] = userId
            it[apiKeyId] = AuditActor.currentApiKeyId()
            it[RunRequests.requestedAt] = requestedAt
            it[state] = RunState.PENDING
            // Zero is not a window (the worker reads it as "never"), and neither
            // is a negative one.
            it[purgeAfter] = if (days > 0) requestedAt.plus(days.toLong(), ChronoUnit.DAYS) else null
        }
    }

    /**
     * Where the run [runId] of [serviceId] stands, for [userId]: read access
     * to the service's results is what it takes, and an id that is not a run
     * of that service in [orgId] is 404 — as is one asked for on a service the
     * caller may not see.
     */
    fun status(orgId: UUID, serviceId: UUID, runId: UUID, userId: UUID, now: Instant = Instant.now()): RunStatus = transaction {
        ProbeResultController.requireResultsRead(orgId, serviceId, userId)
        val request = RunRequests.selectAll()
            .where { (RunRequests.id eq runId) and (RunRequests.serviceId eq serviceId) and (RunRequests.organizationId eq orgId) }
            .firstOrNull() ?: throw NotFoundException()

        // The result is read by the request's own id. An ingestor that predates
        // run requests files the result without settling the request, so the
        // result, not the stored state, is what decides.
        val result = ProbeResults
            .join(ProbeAgents, JoinType.LEFT, ProbeResults.probeAgentId, ProbeAgents.id)
            .select(ProbeResults.columns + ProbeAgents.slug)
            .where { (ProbeResults.id eq runId) and (ProbeResults.serviceId eq serviceId) and (ProbeResults.organizationId eq orgId) }
            .firstOrNull()

        val requestedAt = request[RunRequests.requestedAt]
        val stored = request[RunRequests.state]
        val state = when {
            result != null -> if (result[ProbeResults.status] == "skipped") RunState.SKIPPED else RunState.DONE
            // Settled, and the result has since gone with its retention window.
            stored != RunState.PENDING -> stored
            Duration.between(requestedAt, now) > expiry -> RunState.EXPIRED
            else -> RunState.PENDING
        }
        val reason = result?.takeIf { state == RunState.SKIPPED }
            ?.let { it[ProbeResults.rawResult]["reason"]?.jsonPrimitive?.contentOrNull }
        RunStatus(
            runId = runId.toString(),
            state = state,
            requestedAt = requestedAt.toString(),
            result = result?.let(ProbeResultController::summaryOf),
            reason = reason,
        )
    }
}
