package dev.tracedown.ingestor.consumers

import dev.tracedown.ingestor.services.ResultPersistenceService
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Consumes probe results from the Redis queue via blocking pop.
 *
 * Runs a coroutine loop that BRPOP's from `probe_results_queue`,
 * deserializes each envelope, and delegates to ResultPersistenceService.
 */
class ProbeResultConsumer(
    private val redis: RedisCommands<String, String>,
    private val popTimeoutSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null

    companion object {
        const val QUEUE_KEY = "probe_results_queue"
    }

    /** Starts the consumer loop in the given coroutine scope. */
    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            log.info("consumer started, BRPOP timeout={}s", popTimeoutSeconds)
            while (isActive) {
                try {
                    consumeOne()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("consumer error: {}", e.message, e)
                    delay(1000)
                }
            }
        }
    }

    /** Stops the consumer loop. */
    fun stop() {
        job?.cancel()
    }

    /**
     * Blocks until a message is available, then persists it.
     * Returns without action if the BRPOP times out.
     */
    private fun consumeOne() {
        val result = redis.brpop(popTimeoutSeconds.toDouble(), QUEUE_KEY)
            ?: return // timeout, no message

        val raw = result.value
        try {
            val envelope = Json.parseToJsonElement(raw).jsonObject
            ResultPersistenceService.persist(envelope)

            // Nudge downstream services (notification-dispatcher, metrics-service)
            val rawResult = envelope["rawResult"]?.jsonObject
            val outcome = rawResult?.get("outcome")?.jsonPrimitive?.content ?: "error"
            val status = when (outcome) {
                "success" -> "success"
                "failure" -> "failure"
                "timeout" -> "timeout"
                "skipped" -> "skipped"
                else -> "error"
            }

            // Skip nudge for executor errors and skipped probes — a skipped
            // probe is history-only: no metrics, no notifications.
            if (status != "error" && status != "skipped") {
                val calls = rawResult?.get("calls")?.jsonArray
                val nudgePayload = buildJsonObject {
                    put("orgId", envelope["organizationId"]?.jsonPrimitive?.content ?: "")
                    put("workspaceId", envelope["workspaceId"]?.jsonPrimitive?.content ?: "")
                    put("projectId", envelope["projectId"]?.jsonPrimitive?.content ?: "")
                    put("serviceId", envelope["serviceId"]?.jsonPrimitive?.content ?: "")
                    put("status", status)
                    put("totalResponseMs", calls
                        ?.sumOf { call ->
                            val resp = call.jsonObject["response"]
                            if (resp is JsonObject) resp["responseTimeMs"]?.jsonPrimitive?.intOrNull ?: 0 else 0
                        } ?: 0)
                    put("callCount", calls?.size ?: 0)
                    // Measured HTTP-layer usage (agent-supplied) for usage counters.
                    put("ingressBytes", rawResult?.get("ingressBytes")?.jsonPrimitive?.longOrNull ?: 0L)
                    put("egressBytes", rawResult?.get("egressBytes")?.jsonPrimitive?.longOrNull ?: 0L)
                    // Bytes dispatched to the agent for this run — measured by the
                    // scheduler and carried on the envelope, not inside rawResult.
                    put("agentEgressBytes", envelope["agentEgressBytes"]?.jsonPrimitive?.longOrNull ?: 0L)
                    // A call is failed when an assertion failed OR it errored
                    // before assertions could run (DNS/connect/timeout).
                    put("failedCalls", calls?.count { call ->
                        val obj = call.jsonObject
                        val errored = obj["error"] != null && obj["error"] !is JsonNull
                        errored || obj["assertions"]?.jsonArray?.any { a ->
                            a.jsonObject["outcome"]?.jsonPrimitive?.content == "failed"
                        } == true
                    } ?: 0)
                    // Failed-assertion details so live clients can update the
                    // service's failure preview without a refetch (capped).
                    val failedAssertions = buildJsonArray {
                        var added = 0
                        for (call in calls ?: emptyList()) {
                            val assertions = call.jsonObject["assertions"]?.jsonArray ?: continue
                            for (assertion in assertions) {
                                if (added >= 5) break
                                val obj = assertion.jsonObject
                                if (obj["outcome"]?.jsonPrimitive?.contentOrNull != "failed") continue
                                add(buildJsonObject {
                                    put("scope", obj["scope"]?.jsonPrimitive?.contentOrNull ?: "unknown")
                                    put("expected", obj["expected"]?.jsonPrimitive?.contentOrNull)
                                    put("actual", obj["actual"]?.jsonPrimitive?.contentOrNull)
                                })
                                added++
                            }
                        }
                    }
                    if (failedAssertions.isNotEmpty()) put("failedAssertions", failedAssertions)
                }
                redis.publish("notify:nudge", nudgePayload.toString())
            }
        } catch (e: Exception) {
            log.error("failed to process result: {}", e.message, e)
            // Message is already popped — log and discard to avoid poison pill loops.
            // In production, this could push to a dead-letter queue.
        }
    }
}
