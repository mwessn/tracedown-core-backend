package dev.tracedown.realtime.listeners

import dev.tracedown.realtime.ws.ConnectionManager
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Subscribes to Redis A pub/sub pattern `rt:*` and routes events to WebSocket clients.
 *
 * When a message arrives on `rt:{channel}`, parses the JSON to extract orgId and event type,
 * then delegates to [ConnectionManager.broadcast] which handles org filtering and delivery.
 */
class EventRouter(
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Subscribes to the `rt:*` pattern on Redis A. */
    fun start() {
        pubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(pattern: String?, channel: String?, message: String?) {
                if (channel.isNullOrBlank() || message.isNullOrBlank()) return
                scope.launch { handleMessage(channel, message) }
            }
        })
        pubSubConnection.sync().psubscribe("rt:*")
        log.info("event router subscribed to rt:*")
    }

    /** Unsubscribes from the pattern. */
    fun stop() {
        try {
            pubSubConnection.sync().punsubscribe("rt:*")
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    private suspend fun handleMessage(redisChannel: String, message: String) {
        try {
            // Redis channel is "rt:{channel}", strip the "rt:" prefix
            val channel = redisChannel.removePrefix("rt:")
            val json = Json.parseToJsonElement(message).jsonObject
            val orgId = UUID.fromString(json["orgId"]?.jsonPrimitive?.content ?: return)
            val event = json["event"]?.jsonPrimitive?.content ?: return
            val data = json["data"]?.jsonObject

            // Build the wire message for WebSocket clients
            val wireMessage = buildJsonObject {
                put("type", "event")
                put("channel", channel)
                put("event", event)
                if (data != null) put("data", data)
            }.toString()

            log.debug("routing {} to channel={}", event, channel)
            connectionManager.broadcast(channel, orgId, wireMessage)
        } catch (e: Exception) {
            log.warn("failed to route event from {}: {}", redisChannel, e.message)
        }
    }
}
