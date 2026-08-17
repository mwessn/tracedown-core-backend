package dev.tracedown.scheduler.results

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Publishes probe results to the Redis queue for the result-ingestor.
 */
class ResultPublisher(private val redis: RedisCommands<String, String>) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val QUEUE_KEY = "probe_results_queue"
    }

    /**
     * Publishes a probe result to the Redis queue.
     *
     * @param jobId unique job identifier for this probe run
     * @param serviceId the service that was probed
     * @param agentId the agent that executed the probe (null for skipped probes)
     * @param projectId the service's project
     * @param workspaceId the service's workspace
     * @param organizationId the service's organization
     * @param rawResult the raw ProbeResult from the agent (or synthetic error/skip)
     * @param agentEgressBytes UTF-8 bytes of the request body sent to the agent
     *   for this dispatch (0 when nothing was dispatched)
     */
    fun publish(
        jobId: UUID,
        serviceId: UUID,
        agentId: Long?,
        projectId: UUID,
        workspaceId: UUID,
        organizationId: UUID,
        rawResult: JsonObject,
        agentEgressBytes: Long = 0L,
    ) {
        val envelope = buildJsonObject {
            put("jobId", jobId.toString())
            put("serviceId", serviceId.toString())
            if (agentId != null) put("probeAgentId", agentId)
            put("projectId", projectId.toString())
            put("workspaceId", workspaceId.toString())
            put("organizationId", organizationId.toString())
            put("rawResult", rawResult)
            put("agentEgressBytes", agentEgressBytes)
        }

        redis.lpush(QUEUE_KEY, envelope.toString())
        log.debug("published result for service {} agent {}", serviceId, agentId)
    }
}
