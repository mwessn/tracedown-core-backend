package dev.tracedown.gateway.util

import dev.tracedown.common.runs.RunTrigger
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Tells the scheduler about a service that just changed, over Redis A.
 *
 * `schedule:nudge` re-syncs one service's Quartz job immediately; without it
 * the change waits for the scheduler's consistency sweep. [trigger] asks for
 * one immediate dispatch, filed under the run's id (see [RunTrigger]).
 *
 * Lifted out of the service controller because deleting a *container* stops
 * services too: a project or workspace delete has to nudge every service it
 * carried down, or those probes keep firing until the next sweep.
 *
 * Publishing is best-effort — the sweep is the backstop — so a Redis failure is
 * logged and swallowed, and an uninitialised publisher is silently a no-op.
 */
object ScheduleNudge {

    private val log = LoggerFactory.getLogger(ScheduleNudge::class.java)
    private var redisProvider: (() -> RedisCommands<String, String>)? = null

    /** Injects the Redis A connection provider. Call once at startup. */
    fun init(redis: () -> RedisCommands<String, String>) {
        this.redisProvider = redis
    }

    /** Publishes a schedule nudge so the scheduler picks up the change immediately. */
    fun publish(serviceId: UUID) = send("schedule:nudge", serviceId)

    /** Publishes each id in turn; one failure never stops the rest. */
    fun publishAll(serviceIds: Collection<UUID>) = serviceIds.forEach(::publish)

    /**
     * Asks the scheduler for one immediate run of [serviceId], filed under
     * [runId]: on [RunTrigger.RUN_CHANNEL], and — when no scheduler heard it,
     * which is what a scheduler from before run handles looks like — as the
     * bare id on [RunTrigger.TRIGGER_CHANNEL], which every scheduler reads.
     * Never both to a scheduler that heard the first: that would be two runs.
     *
     * Returns how many schedulers heard the request (0 when Redis is away or
     * nothing is subscribed: the request is lost, and its handle expires).
     */
    fun trigger(serviceId: UUID, runId: UUID): Long {
        val redis = redisProvider ?: return 0
        return try {
            val commands = redis()
            val heard = commands.publish(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(serviceId, runId)) ?: 0L
            if (heard > 0) heard else commands.publish(RunTrigger.TRIGGER_CHANNEL, serviceId.toString()) ?: 0L
        } catch (e: Exception) {
            log.warn("failed to publish run {} of {}: {}", runId, serviceId, e.message)
            0
        }
    }

    private fun send(channel: String, serviceId: UUID) {
        try {
            redisProvider?.invoke()?.publish(channel, serviceId.toString())
        } catch (e: Exception) {
            log.warn("failed to publish {} for {}: {}", channel, serviceId, e.message)
        }
    }
}
