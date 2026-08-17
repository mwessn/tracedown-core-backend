package dev.tracedown.notifications

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.notifications.config.DispatcherConfig
import dev.tracedown.notifications.consumers.EmailStatusConsumer
import dev.tracedown.notifications.consumers.OutboxConsumer
import dev.tracedown.notifications.delivery.EmailDeliveryService
import dev.tracedown.notifications.delivery.WebhookDeliveryService
import dev.tracedown.notifications.processing.NotificationProcessor
import dev.tracedown.notifications.recipients.RecipientCooldown
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.notifications.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB, Redis, and the outbox consumer. */
fun Application.module() {
    val config = DispatcherConfig.load(environment)

    // Database
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
    )

    // Decrypts org variables referenced from webhook URLs at delivery time.
    VariableCrypto.init(config.aesKey)

    // Redis: sync connection for publishing, pub/sub for nudge subscription
    val redisConn = RedisFactory.createConnection(config.redisAUrl)
    val redis = redisConn.sync()
    val pubSubConnection = RedisFactory.createPubSubConnection(config.redisAUrl)

    // Services
    val emailPublisher = EmailPublisher(redis)
    val emailDeliveryService = EmailDeliveryService(emailPublisher)
    val webhookDeliveryService = WebhookDeliveryService(
        retryBaseSeconds = config.webhookRetryBaseSeconds,
    )
    val recipientCooldown = RecipientCooldown(redis, config.recipientCooldownSeconds)
    val processor = NotificationProcessor(emailDeliveryService, webhookDeliveryService, recipientCooldown)

    // Outbox consumer
    val consumer = OutboxConsumer(processor, pubSubConnection, config.pollIntervalMs, config.batchSize)
    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    consumer.start(consumerScope)

    // Email status consumer — dedicated connection because BRPOP blocks it
    val statusRedisConn = RedisFactory.createConnection(config.redisAUrl)
    val statusConsumer = EmailStatusConsumer(statusRedisConn.sync(), config.statusPopTimeoutSeconds)
    statusConsumer.start(consumerScope)

    log.info("notification-dispatcher started")

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        consumer.stop()
        statusConsumer.stop()
        pubSubConnection.close()
        statusRedisConn.close()
        redisConn.close()
        dataSource.close()
        log.info("notification-dispatcher shut down")
    }
}
