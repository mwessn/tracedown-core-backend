package dev.tracedown.ingestor.services

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.logging.LogContext
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Persists probe results from the Redis queue into the database.
 *
 * In a single transaction: inserts the probe_results row, inserts
 * probe_steps rows, updates the service's status tracking columns,
 * and writes an outbox event for downstream consumers.
 */
object ResultPersistenceService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Relocates agent-uploaded bodies to server-derived, tenant-scoped keys.
     * Injected at startup. When null (no storage configured), response bodies are
     * recorded without a storage URL rather than trusting the agent-chosen path.
     */
    @Volatile
    private var bodyRelocator: BodyRelocator? = null

    /** Injects the body relocator. Called once at startup. */
    fun init(relocator: BodyRelocator) {
        this.bodyRelocator = relocator
    }

    /**
     * Agent writeback (`.store()`) may only write METRIC variables — those with
     * secret=false AND encrypted=false. A writeback key that collides with a
     * secret or encrypted ("variable"-type) row must be skipped, never overwritten
     * with attacker-influenced plaintext (which would defeat crypto-shredding and
     * enable variable hijacking).
     */
    fun writebackMayOverwrite(existingSecret: Boolean, existingEncrypted: Boolean): Boolean =
        !existingSecret && !existingEncrypted

    /**
     * Persists a single probe result envelope.
     *
     * @param envelope the JSON envelope as published by ResultPublisher
     */
    fun persist(envelope: JsonObject) {
        val resultId = UUID.randomUUID()
        val serviceId = UUID.fromString(envelope["serviceId"]!!.jsonPrimitive.content)
        // Absent for skipped probes — they never reached an agent.
        val agentId = envelope["probeAgentId"]?.jsonPrimitive?.longOrNull
        val projectId = UUID.fromString(envelope["projectId"]!!.jsonPrimitive.content)
        val workspaceId = UUID.fromString(envelope["workspaceId"]!!.jsonPrimitive.content)
        val organizationId = UUID.fromString(envelope["organizationId"]!!.jsonPrimitive.content)
        val rawResult = envelope["rawResult"]!!.jsonObject

        // Attribute every log line from this persistence pass to its org (and
        // the finer ids), so per-org log files capture the ingest trail too.
        LogContext.scoped(
            org = organizationId,
            workspace = workspaceId,
            project = projectId,
            service = serviceId,
        ) {

        val outcome = rawResult["outcome"]?.jsonPrimitive?.content ?: "error"
        val status = normalizeStatus(outcome)

        // Drop executor errors — only persist valid probe outcomes
        if (status == "error") {
            log.warn("dropping result with error outcome for service {}", serviceId)
            return
        }

        // Extract timing from rawResult (Lace ProbeResult uses "elapsedMs")
        val elapsedMs = rawResult["elapsedMs"]?.jsonPrimitive?.intOrNull ?: 0
        val calls = rawResult["calls"]?.jsonArray
        val totalResponseMs = calls
            ?.sumOf { call ->
                val resp = call.jsonObject["response"]
                if (resp is JsonObject) resp["responseTimeMs"]?.jsonPrimitive?.intOrNull ?: 0 else 0
            } ?: 0
        val startedAt = Instant.now()

        // Take ownership of every stored body BEFORE persisting: relocate the
        // agent-uploaded bytes to a server-derived, tenant-scoped key and record
        // only that URI. The agent's own path is never persisted (it would collide
        // across tenants and could point at arbitrary files). Indices that had a
        // body but could not be relocated are remembered so the step is recorded
        // as body-unavailable instead of silently pointing nowhere.
        val relocatedBodies = HashMap<Int, String>()
        val bodyRelocationFailed = HashSet<Int>()
        if (calls != null) {
            val relocator = bodyRelocator
            for ((index, callElement) in calls.withIndex()) {
                val resp = callElement.jsonObject["response"] as? JsonObject ?: continue
                val agentPath = resp["bodyPath"]?.jsonPrimitive?.contentOrNull ?: continue
                if (agentPath.isBlank()) continue
                val relocated = relocator?.relocate(
                    agentBodyPath = agentPath,
                    organizationId = organizationId,
                    serviceId = serviceId,
                    resultId = resultId,
                    callIndex = index,
                )
                if (relocated != null) relocatedBodies[index] = relocated else bodyRelocationFailed.add(index)
            }
        }

        transaction {
            // 1. Insert probe_results
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = serviceId
                it[probeAgentId] = agentId
                it[ProbeResults.startedAt] = startedAt
                it[ProbeResults.status] = status
                it[runDurationMs] = elapsedMs
                it[ProbeResults.totalResponseMs] = totalResponseMs
                it[ProbeResults.ingressBytes] = rawResult["ingressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                it[ProbeResults.egressBytes] = rawResult["egressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                // Scheduler-measured dispatch bytes, carried on the envelope.
                it[ProbeResults.agentEgressBytes] = envelope["agentEgressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                it[ProbeResults.requestCount] = calls?.size ?: 0
                it[ProbeResults.rawResult] = rawResult
                it[ProbeResults.projectId] = projectId
                it[ProbeResults.workspaceId] = workspaceId
                it[ProbeResults.organizationId] = organizationId
            }

            // 2. Insert probe_steps from rawResult.calls[]
            if (calls != null) {
                for ((index, callElement) in calls.withIndex()) {
                    val call = callElement.jsonObject
                    val request = call["request"].let { if (it is JsonObject) it else null }
                    val response = call["response"].let { if (it is JsonObject) it else null }
                    val responseHeaders = response?.get("headers") as? JsonObject

                    ProbeSteps.insert {
                        it[id] = UUID.randomUUID()
                        it[probeResultId] = resultId
                        it[stepNum] = (index + 1).toShort()
                        it[requestUrl] = request?.get("url")?.jsonPrimitive?.content ?: ""
                        it[statusCode] = response?.get("status")?.jsonPrimitive?.intOrNull?.toShort()
                        it[responseTimeMs] = response?.get("responseTimeMs")?.jsonPrimitive?.intOrNull
                        it[dnsMs] = response?.get("dnsMs")?.jsonPrimitive?.intOrNull
                        it[connectMs] = response?.get("connectMs")?.jsonPrimitive?.intOrNull
                        it[tlsMs] = response?.get("tlsMs")?.jsonPrimitive?.intOrNull
                        it[ttfbMs] = response?.get("ttfbMs")?.jsonPrimitive?.intOrNull
                        it[transferMs] = response?.get("transferMs")?.jsonPrimitive?.intOrNull
                        it[responseSizeBytes] = response?.get("sizeBytes")?.jsonPrimitive?.intOrNull
                        it[assertionResults] = call["assertions"]
                        // `.store()` writeback is a whole-run flat map (rawResult.actions.variables,
                        // persisted to service_variables in step 4). The ProbeResult wire format
                        // (spec §9) does not attribute stored variables to individual calls, so there
                        // is no per-step value to record — leave null rather than fabricate one.
                        it[extractedVariables] = null
                        it[headers] = responseHeaders
                        // The response Set-Cookie header is the only per-call cookie data the
                        // ProbeResult exposes (the executor's cookie jar itself is not emitted).
                        // Header names are lower-cased per spec §9. Null when the call set no cookies.
                        it[cookies] = responseHeaders?.get("set-cookie")
                        // Server-derived, tenant-scoped URI from the relocation
                        // pre-pass — never the agent-reported path.
                        it[responseBodyStorageUrl] = relocatedBodies[index]
                        // Present exactly when the body was not captured/stored: `notRequested`
                        // (body saving disabled), `bodyTooLarge`, or `timeout` (no body received)
                        // — spec §9 response.bodyNotCapturedReason. A body that was captured but
                        // could not be taken into server-owned storage is recorded as unavailable.
                        it[bodyNotStoredReason] = response?.get("bodyNotCapturedReason")?.jsonPrimitive?.contentOrNull
                            ?: if (index in bodyRelocationFailed) "storageUnavailable" else null
                        it[error] = call["error"]?.jsonPrimitive?.contentOrNull
                        it[createdAt] = startedAt
                    }
                }
            }

            // 3. Update service status tracking. Skipped probes don't touch
            // it: last_status stays the last real outcome, and last_run_id
            // must keep pointing at a real result (it feeds `prev` writeback).
            val service = if (status == "skipped") null else Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()

            // Captured BEFORE the status update below overwrites it. On a
            // recovery this is when the outage began — used just below to compute
            // downtime, since the row's value is gone once we update it.
            val previousStatusSince = service?.get(Services.lastStatusSince)

            if (service != null) {
                val previousStatus = service[Services.lastStatus]
                val statusChanged = previousStatus != status

                Services.update({ Services.id eq serviceId }) {
                    it[lastRunId] = resultId
                    it[lastStatus] = status
                    if (statusChanged) {
                        it[lastStatusSince] = startedAt
                        it[lastStatusConsecutive] = 1
                    } else {
                        it[lastStatusConsecutive] = service[Services.lastStatusConsecutive] + 1
                    }
                }
            }

            // 4. Write back actions.variables to service_variables
            val actions = rawResult["actions"]?.jsonObject
            val writebackVars = actions?.get("variables")?.jsonObject
            if (writebackVars != null && writebackVars.isNotEmpty()) {
                for ((varKey, varValue) in writebackVars) {
                    val valueStr = if (varValue is JsonPrimitive) varValue.content else varValue.toString()

                    val existing = ServiceVariables.selectAll()
                        .where {
                            (ServiceVariables.serviceId eq serviceId) and
                            (ServiceVariables.key eq varKey) and
                            (ServiceVariables.deleted eq false)
                        }
                        .firstOrNull()

                    if (existing != null) {
                        // Agent writeback (`.store()`) may only touch METRIC variables
                        // (secret=false AND encrypted=false). A writeback key that
                        // collides with a secret or encrypted ("variable"-type) row is
                        // skipped — never overwritten with attacker-influenced plaintext
                        // (which would defeat crypto-shredding and enable variable
                        // hijacking), never decrypted, never bricked.
                        val isMetric = writebackMayOverwrite(
                            existing[ServiceVariables.secret],
                            existing[ServiceVariables.encrypted],
                        )
                        if (isMetric) {
                            ServiceVariables.update({
                                ServiceVariables.id eq existing[ServiceVariables.id]
                            }) {
                                it[value] = valueStr
                                it[updatedAt] = startedAt
                            }
                        } else {
                            log.warn(
                                "writeback for service {} key '{}' skipped: target is a secret/encrypted variable, not a metric",
                                serviceId, varKey,
                            )
                        }
                    } else {
                        ServiceVariables.insert {
                            it[id] = UUID.randomUUID()
                            it[ServiceVariables.serviceId] = serviceId
                            it[key] = varKey
                            it[value] = valueStr
                            it[secret] = false
                            it[encrypted] = false
                            it[deleted] = false
                            it[createdAt] = startedAt
                            it[updatedAt] = startedAt
                        }
                    }
                }
            }

            // Downtime for a recovery notification — computed here, the one place
            // that still has both the pre-update last_status_since (outage start)
            // and this run's time (recovery), and only when a recovery actually
            // fired (laceEmitRecovery emits the "recovered" trigger only if
            // notifyRecovery is on and the service came back up). We carry the
            // seconds, not the raw timestamp, and nothing at all otherwise.
            val recoveryFired = actions?.get("notifications")?.jsonArray
                ?.any { it.jsonObject["trigger"]?.jsonPrimitive?.contentOrNull == "recovered" } == true
            val downtimeSeconds = if (recoveryFired && previousStatusSince != null) {
                Duration.between(previousStatusSince, startedAt).seconds.coerceAtLeast(0)
            } else {
                null
            }

            // 5. Write outbox event for downstream consumers (notification-
            // dispatcher, etc.). Skipped probes are history-only — no events.
            if (status != "skipped") Outbox.insert {
                it[id] = UUID.randomUUID()
                it[aggregateType] = "probe_result"
                it[aggregateId] = resultId
                it[eventType] = "probe_result.created"
                it[payload] = buildJsonObject {
                    put("resultId", resultId.toString())
                    put("serviceId", serviceId.toString())
                    put("projectId", projectId.toString())
                    put("workspaceId", workspaceId.toString())
                    put("organizationId", organizationId.toString())
                    put("status", status)
                    put("runDurationMs", elapsedMs)
                    // Present only on a recovery — the dispatcher formats it into
                    // the recovery message. Absent for every other result.
                    downtimeSeconds?.let { put("downtimeSeconds", it) }
                }
                it[published] = false
                it[createdAt] = startedAt
            }
        }

        log.debug("persisted result {} for service {} status={}", resultId, serviceId, status)

        // Shed probes mean the platform is over dispatch capacity — surface it to
        // the org as a banner (throttled inside the service). This one is org-scoped:
        // a skipped probe is that org's own outcome, so it goes to them even where a
        // host redirects shared-infra alerts. It is offered to the routing seam all
        // the same, so a host could reroute it too if it chose.
        if (status == "skipped") {
            val data = buildJsonObject {
                put("reason", rawResult["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown")
            }
            val handled = SystemAlertRouting.handled(
                AlertContext(
                    alertType = SystemAlertService.DISPATCH_CAPACITY,
                    subject = "",
                    orgId = organizationId,
                    orgScoped = true,
                    severity = "warning",
                    data = data,
                )
            )
            if (!handled) {
                SystemAlertService.raise(
                    orgId = organizationId,
                    alertType = SystemAlertService.DISPATCH_CAPACITY,
                    severity = "warning",
                    data = data,
                )
            }
        }
        } // LogContext.scoped
    }

    /** Maps ProbeResult outcome to DB status enum. */
    private fun normalizeStatus(outcome: String): String = when (outcome) {
        "success" -> "success"
        "failure" -> "failure"
        "timeout" -> "timeout"
        "skipped" -> "skipped"
        else -> "error"
    }
}
