package dev.tracedown.email.consumers

import dev.tracedown.email.processing.EmailProcessor
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * Consumes email jobs from the Redis queue via blocking pop.
 *
 * Runs a coroutine loop that BRPOP's from `email_queue`,
 * deserializes each envelope, and delegates to EmailProcessor.
 */
class EmailQueueConsumer(
    private val redis: RedisCommands<String, String>,
    private val processor: EmailProcessor,
    private val popTimeoutSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null

    companion object {
        const val QUEUE_KEY = "email_queue"
    }

    /** Starts the consumer loop in the given coroutine scope. */
    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            log.info("email queue consumer started, BRPOP timeout={}s", popTimeoutSeconds)
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

    private fun consumeOne() {
        val result = redis.brpop(popTimeoutSeconds.toDouble(), QUEUE_KEY)
            ?: return // timeout, no message

        val raw = result.value
        try {
            val envelope = Json.parseToJsonElement(raw).jsonObject
            processor.process(envelope)
        } catch (e: Exception) {
            log.error("failed to process email job: {}", e.message, e)
        }
    }
}
