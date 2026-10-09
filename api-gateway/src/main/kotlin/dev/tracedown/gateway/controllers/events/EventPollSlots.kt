package dev.tracedown.gateway.controllers.events

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * How many event reads may be open at once: [PER_KEY] for one key across
 * every gateway process, and [PER_PROCESS] in one process for all keys
 * together.
 *
 * A long-poll costs nothing while it waits, but it does keep a request open,
 * and a client that opened them in a loop would hold an unbounded number. The
 * per-key bound is shared through Redis A — a sorted set per key of the reads
 * it has open, each scored with the moment it must have ended by, so a read
 * whose process died stops counting at that moment. When Redis does not
 * answer, the per-key bound is not applied (as the request budget is not)
 * and the per-process one still is.
 */
object EventPollSlots {

    const val PER_KEY = 2
    const val PER_PROCESS = 512

    private val log = LoggerFactory.getLogger(EventPollSlots::class.java)

    private val process = Semaphore(PER_PROCESS)

    @Volatile
    private var redis: (() -> RedisCommands<String, String>)? = null

    /** Injects Redis A. Unset, only the per-process bound applies. */
    fun init(redis: (() -> RedisCommands<String, String>)?) {
        this.redis = redis
    }

    /** Drops expired reads, then admits one if fewer than the bound are open. Atomic in Redis. */
    private const val ACQUIRE = """
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
        redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
        local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
        redis.call('PEXPIREAT', KEYS[1], last[2])
        return 1
    """

    /** A held slot. [close] gives it back. */
    class Slot internal constructor(private val keyId: UUID, private val token: String?) : AutoCloseable {
        override fun close() {
            process.release()
            val commands = redis ?: return
            if (token == null) return
            try {
                commands().zrem(redisKey(keyId), token)
            } catch (e: Exception) {
                // It expires on its own at the score it was given.
                log.debug("event read slot of key {} not given back: {}", keyId, e.message)
            }
        }
    }

    /**
     * A slot for one read of [keyId] that will be over within [holdMillis],
     * or null when the key or the process is at its bound.
     */
    fun tryAcquire(keyId: UUID, holdMillis: Long): Slot? {
        if (!process.tryAcquire()) return null
        val commands = redis ?: return Slot(keyId, null)
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        val admitted = try {
            commands().eval<Long>(
                ACQUIRE, ScriptOutputType.INTEGER, arrayOf(redisKey(keyId)),
                now.toString(), PER_KEY.toString(), (now + holdMillis).toString(), token,
            ) == 1L
        } catch (e: Exception) {
            log.debug("event read slots unavailable, admitting key {}: {}", keyId, e.message)
            return Slot(keyId, null)
        }
        if (!admitted) {
            process.release()
            return null
        }
        return Slot(keyId, token)
    }

    private fun redisKey(keyId: UUID) = "events:polls:$keyId"
}
