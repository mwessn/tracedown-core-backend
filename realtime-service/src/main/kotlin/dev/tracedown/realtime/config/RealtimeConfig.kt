package dev.tracedown.realtime.config

import io.ktor.server.application.ApplicationEnvironment

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Typed configuration for the realtime-service.
 */
data class RealtimeConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val pingIntervalMs: Long,
    val pingTimeoutMs: Long,
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): RealtimeConfig {
            val config = env.config
            return RealtimeConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                pingIntervalMs = config.propertyOrNull("realtime.pingIntervalMs")
                    ?.getString()?.toLong() ?: 5000L,
                pingTimeoutMs = config.propertyOrNull("realtime.pingTimeoutMs")
                    ?.getString()?.toLong() ?: 10000L,
            )
        }
    }
}
