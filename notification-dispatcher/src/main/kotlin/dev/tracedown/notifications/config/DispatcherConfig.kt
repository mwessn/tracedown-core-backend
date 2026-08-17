package dev.tracedown.notifications.config

import io.ktor.server.application.ApplicationEnvironment

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Typed configuration for the notification-dispatcher service.
 */
data class DispatcherConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    /** Platform AES key — decrypts org variables referenced from webhook URLs. */
    val aesKey: String,
    val pollIntervalMs: Long,
    val batchSize: Int,
    val statusPopTimeoutSeconds: Long,
    /** Base backoff (seconds) between webhook retries; grows ×4 each retry. */
    val webhookRetryBaseSeconds: Long,
    /** Per-recipient anti-storm cooldown (seconds) between notifications on a channel. */
    val recipientCooldownSeconds: Long,
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): DispatcherConfig {
            val config = env.config
            return DispatcherConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                aesKey = config.property("platform.aesKey").getString(),
                pollIntervalMs = config.propertyOrNull("dispatcher.pollIntervalMs")
                    ?.getString()?.toLong() ?: 5000L,
                batchSize = config.propertyOrNull("dispatcher.batchSize")
                    ?.getString()?.toInt() ?: 50,
                statusPopTimeoutSeconds = config.propertyOrNull("dispatcher.statusPopTimeoutSeconds")
                    ?.getString()?.toLong() ?: 5L,
                webhookRetryBaseSeconds = config.propertyOrNull("dispatcher.webhookRetryBaseSeconds")
                    ?.getString()?.toLong() ?: 2L,
                recipientCooldownSeconds = config.propertyOrNull("dispatcher.recipientCooldownSeconds")
                    ?.getString()?.toLong() ?: 300L,
            )
        }
    }
}
