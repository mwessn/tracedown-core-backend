package dev.tracedown.notifications.recipients

import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Anti-storm cooldown for notification recipients.
 *
 * Same-run coalescing (one dispatch per channel per run) already collapses the
 * events of a single failing run. This adds a cross-run guard: once a recipient
 * has been notified on a channel for a service, further notifications for that
 * service+channel are suppressed until the cooldown expires — so a service that
 * keeps failing run after run does not storm the recipient.
 *
 * Backed by a Redis key with a TTL: `cooldown:{orgUserId}:{serviceId}:{channel}`.
 * Suppression is silent (no notification_log row), matching silenced/quiet-hours.
 */
class RecipientCooldown(
    private val redis: RedisCommands<String, String>,
    private val ttlSeconds: Long = 300L,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** True if this recipient is still within cooldown for the service+channel. */
    fun isOnCooldown(orgUserId: UUID, serviceId: UUID, channel: String): Boolean =
        try {
            redis.exists(key(orgUserId, serviceId, channel)) > 0
        } catch (e: Exception) {
            // Redis unavailable — fail open (deliver) rather than drop notifications.
            log.warn("cooldown check failed for {} / {} / {}: {}", orgUserId, serviceId, channel, e.message)
            false
        }

    /** Records a successful dispatch, starting the cooldown window. */
    fun markDispatched(orgUserId: UUID, serviceId: UUID, channel: String) {
        try {
            redis.set(key(orgUserId, serviceId, channel), "1", SetArgs.Builder.ex(ttlSeconds))
        } catch (e: Exception) {
            log.warn("cooldown set failed for {} / {} / {}: {}", orgUserId, serviceId, channel, e.message)
        }
    }

    private fun key(orgUserId: UUID, serviceId: UUID, channel: String): String =
        "cooldown:$orgUserId:$serviceId:$channel"
}
