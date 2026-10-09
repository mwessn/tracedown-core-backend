package dev.tracedown.gateway.util

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * How many event reads may be open at once: [PER_KEY] for one key,
 * [PER_USER] for all the keys of one user, [PER_ORG] for all the keys of one
 * organization — each across every gateway process — and [PER_PROCESS] in
 * one process for everyone together.
 *
 * A long-poll costs nothing while it waits, but it does keep a request open,
 * and a client that opened them in a loop (or minted keys to open more) would
 * hold an unbounded number. The shared bounds live in Redis A: a sorted set
 * per key, user and organization of the reads open there, each scored with
 * the moment it must have ended by, so a read whose process died stops
 * counting at that moment; one script checks and takes all three at once.
 * When Redis does not answer, each process holds a key to [PER_KEY] on its
 * own instead — never no bound at all.
 */
object EventPollSlots {

    const val PER_KEY = 2
    const val PER_USER = 6
    const val PER_ORG = 12
    const val PER_PROCESS = 512

    private val log = LoggerFactory.getLogger(EventPollSlots::class.java)

    private val process = Semaphore(PER_PROCESS)

    /** Reads open per key in this process, for when Redis is not there to count them. */
    private val local = ConcurrentHashMap<UUID, Int>()

    @Volatile
    private var redis: (() -> RedisCommands<String, String>)? = null

    /** Injects Redis A. Unset, the bounds are this process's own. */
    fun init(redis: (() -> RedisCommands<String, String>)?) {
        this.redis = redis
    }

    /**
     * For each of KEYS (key, user, organization) with its bound in ARGV[3..5]:
     * drop the reads past their deadline, and refuse when it is full. Only
     * when none is full is the read added to all three. Atomic in Redis.
     */
    private const val ACQUIRE = """
        local now = ARGV[1]
        for i = 1, 3 do
            redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
            if redis.call('ZCARD', KEYS[i]) >= tonumber(ARGV[2 + i]) then return 0 end
        end
        for i = 1, 3 do
            redis.call('ZADD', KEYS[i], ARGV[2], ARGV[6])
            local last = redis.call('ZRANGE', KEYS[i], -1, -1, 'WITHSCORES')
            redis.call('PEXPIREAT', KEYS[i], last[2])
        end
        return 1
    """

    /** A held slot. [close] gives it back. */
    class Slot internal constructor(
        private val keys: List<String>,
        private val token: String?,
        private val localKey: UUID?,
    ) : AutoCloseable {
        override fun close() {
            process.release()
            localKey?.let { key -> local.computeIfPresent(key) { _, n -> (n - 1).takeIf { it > 0 } } }
            val commands = redis ?: return
            if (token == null) return
            try {
                val sync = commands()
                keys.forEach { sync.zrem(it, token) }
            } catch (e: Exception) {
                // Each expires on its own at the score it was given.
                log.debug("event read slot not given back: {}", e.message)
            }
        }
    }

    /**
     * A slot for one read of [keyId] (acting as [userId] in [orgId]) that will
     * be over within [holdMillis], or null when any of its bounds is reached.
     */
    fun tryAcquire(keyId: UUID, userId: UUID, orgId: UUID, holdMillis: Long): Slot? {
        if (!process.tryAcquire()) return null
        val commands = redis
        if (commands != null) {
            val keys = listOf("events:polls:key:$keyId", "events:polls:user:$userId", "events:polls:org:$orgId")
            val now = System.currentTimeMillis()
            val token = UUID.randomUUID().toString()
            val admitted = try {
                commands().eval<Long>(
                    ACQUIRE, ScriptOutputType.INTEGER, keys.toTypedArray(),
                    now.toString(), (now + holdMillis).toString(),
                    PER_KEY.toString(), PER_USER.toString(), PER_ORG.toString(), token,
                ) == 1L
            } catch (e: Exception) {
                log.debug("event read slots unavailable in Redis, bounding key {} in this process: {}", keyId, e.message)
                null
            }
            when (admitted) {
                true -> return Slot(keys, token, null)
                false -> {
                    process.release()
                    return null
                }
                null -> Unit
            }
        }
        // Redis is not there to count: this process bounds the key alone.
        var taken = false
        local.compute(keyId) { _, n ->
            val open = n ?: 0
            if (open >= PER_KEY) open else (open + 1).also { taken = true }
        }
        if (!taken) {
            process.release()
            return null
        }
        return Slot(emptyList(), null, keyId)
    }
}
