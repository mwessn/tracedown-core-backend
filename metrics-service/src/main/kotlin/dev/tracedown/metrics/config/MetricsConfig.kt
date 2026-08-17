package dev.tracedown.metrics.config

import io.ktor.server.application.ApplicationEnvironment

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Typed configuration for the metrics-service.
 */
data class MetricsConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val redisBUrl: String,
    val metricsTtlSeconds: Long,
    val hourlyBucketTtlSeconds: Long,
    /** Lifetime of usage buckets — the max queryable window (default 7 days). */
    val usageBucketTtlSeconds: Long,
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): MetricsConfig {
            val config = env.config
            return MetricsConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                redisBUrl = config.property("redis.b.url").getString(),
                metricsTtlSeconds = config.propertyOrNull("metrics.metricsTtlSeconds")
                    ?.getString()?.toLong() ?: 86400L,
                hourlyBucketTtlSeconds = config.propertyOrNull("metrics.hourlyBucketTtlSeconds")
                    ?.getString()?.toLong() ?: 90000L,
                usageBucketTtlSeconds = config.propertyOrNull("metrics.usageBucketTtlSeconds")
                    ?.getString()?.toLong() ?: 604800L,
            )
        }
    }
}
