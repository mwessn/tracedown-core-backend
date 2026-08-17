package dev.tracedown.realtime.ws

import io.ktor.websocket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an authenticated WebSocket client connection.
 *
 * Tracks the user's identity, org membership, and active channel subscriptions.
 * The orgId is used to filter events — only events matching the client's org are delivered.
 *
 * [connectionId] is unique per socket. The auth [sessionId] must NOT be used
 * as the registry key: the browser reconnects with the same session token, and
 * the dying socket's cleanup would then unregister the fresh connection,
 * leaving an open socket that receives nothing.
 */
data class ClientSession(
    val connectionId: UUID,
    val wsSession: WebSocketSession,
    val userId: UUID,
    val sessionId: UUID,
    val orgId: UUID,
    val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    /** Events buffered for the next batch flush (pre-serialized JSON). */
    val pendingEvents: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
)
