package dev.tracedown.common.realtime

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Publishes events to the `rt:{channel}` Redis pub/sub namespace.
 *
 * Events are consumed by the realtime-service and routed to WebSocket clients.
 * Each message includes the `orgId` so the realtime-service can enforce org-level isolation.
 *
 * Initialize with a Redis A command provider in each service that needs to publish.
 * If not initialized, publish calls are silently dropped (service is optional).
 */
object RealtimePublisher {

    private val log = LoggerFactory.getLogger(RealtimePublisher::class.java)
    private var redisProvider: (() -> RedisCommands<String, String>)? = null

    /** Injects the Redis A connection provider. Call once at service startup. */
    fun init(redis: () -> RedisCommands<String, String>) {
        this.redisProvider = redis
    }

    /**
     * Publishes an event to `rt:{channel}`.
     *
     * @param channel the channel name (e.g., "service:abc123", "project:def456")
     * @param orgId the organization that owns the resource — used for routing isolation
     * @param event the event type (e.g., "metrics", "service.created", "session.revoked")
     * @param data the event payload
     */
    fun publish(channel: String, orgId: UUID, event: String, data: JsonObject = buildJsonObject {}) {
        val redis = redisProvider ?: return
        try {
            val payload = buildJsonObject {
                put("orgId", orgId.toString())
                put("event", event)
                put("data", data)
            }
            redis().publish("rt:$channel", payload.toString())
        } catch (e: Exception) {
            log.warn("failed to publish rt:{}: {}", channel, e.message)
        }
    }
}
