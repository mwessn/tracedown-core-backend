package dev.tracedown.notifications.consumers

import dev.tracedown.common.models.Outbox
import dev.tracedown.notifications.processing.NotificationProcessor
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Consumes outbox events for notification dispatch.
 *
 * Polls the outbox table on interval and subscribes to Redis pub/sub
 * "notify:nudge" for immediate pickup when new events are written.
 */
class OutboxConsumer(
    private val processor: NotificationProcessor,
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val pollIntervalMs: Long,
    private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null
    private val pollMutex = kotlinx.coroutines.sync.Mutex()

    /** Starts the consumer loop and Redis nudge subscription. */
    fun start(scope: CoroutineScope) {
        // Subscribe to nudge channel for immediate pickup
        pubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String?, message: String?) {
                log.debug("received nudge on channel={}", channel)
                job?.let { scope.launch { pollAndProcess() } }
            }
        })
        pubSubConnection.sync().subscribe("notify:nudge")

        // Start poll loop
        job = scope.launch {
            log.info("outbox consumer started (poll={}ms, batch={})", pollIntervalMs, batchSize)
            while (isActive) {
                try {
                    pollAndProcess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("outbox consumer error: {}", e.message, e)
                }
                delay(pollIntervalMs)
            }
        }
    }

    /** Stops the consumer loop and unsubscribes from Redis. */
    fun stop() {
        job?.cancel()
        try {
            pubSubConnection.sync().unsubscribe("notify:nudge")
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    private suspend fun pollAndProcess() {
        if (!pollMutex.tryLock()) return // skip if another poll is already running
        try {
            doPollAndProcess()
        } finally {
            pollMutex.unlock()
        }
    }

    private suspend fun doPollAndProcess() {
        val events = poll()
        if (events.isEmpty()) return

        log.debug("processing {} outbox events", events.size)

        val processedIds = mutableListOf<UUID>()
        for ((id, payload) in events) {
            try {
                processor.process(payload)
                processedIds.add(id)
            } catch (e: Exception) {
                log.error("failed to process outbox event {}: {}", id, e.message, e)
            }
        }

        if (processedIds.isNotEmpty()) {
            markPublished(processedIds)
        }
    }

    private suspend fun poll(): List<Pair<UUID, JsonObject>> {
        return newSuspendedTransaction(Dispatchers.IO) {
            Outbox.selectAll()
                .where {
                    (Outbox.published eq false) and
                        (Outbox.eventType eq "probe_result.created")
                }
                .orderBy(Outbox.createdAt)
                .limit(batchSize)
                .map { row ->
                    row[Outbox.id] to row[Outbox.payload]
                }
        }
    }

    private suspend fun markPublished(ids: List<UUID>) {
        newSuspendedTransaction(Dispatchers.IO) {
            Outbox.update({ Outbox.id inList ids }) {
                it[published] = true
            }
        }
        log.debug("marked {} outbox events as published", ids.size)
    }
}
