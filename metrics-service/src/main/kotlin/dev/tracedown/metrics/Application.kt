package dev.tracedown.metrics

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.metrics.cache.MetricsWriter
import dev.tracedown.metrics.config.MetricsConfig
import dev.tracedown.metrics.listeners.NudgeListener
import dev.tracedown.metrics.routes.metricsRoutes
import dev.tracedown.metrics.scrape.MetricsReader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.metrics.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB, Redis A (pub/sub), Redis B (metrics), and routes. */
fun Application.module() {
    val config = MetricsConfig.load(environment)

    // Database (for integration/scope lookups)
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
        maximumPoolSize = 5,
    )

    // Redis B (metric storage)
    val redisBConn = RedisFactory.createConnection(config.redisBUrl)
    val redisB = redisBConn.sync()

    // Redis A (pub/sub for nudge subscription + sync for realtime publishing)
    val redisAConn = RedisFactory.createConnection(config.redisAUrl)
    val pubSubConnection = RedisFactory.createPubSubConnection(config.redisAUrl)
    RealtimePublisher.init { redisAConn.sync() }

    // Metrics writer + reader
    val metricsWriter = MetricsWriter(redisB, config.metricsTtlSeconds, config.hourlyBucketTtlSeconds, config.usageBucketTtlSeconds)
    val metricsReader = MetricsReader(redisB, config.metricsTtlSeconds)

    // Nudge listener (Redis A → MetricsWriter → Redis B)
    val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val nudgeListener = NudgeListener(pubSubConnection, metricsWriter, listenerScope)
    nudgeListener.start()

    // Routes
    routing {
        metricsRoutes(metricsReader)
    }

    log.info("metrics-service started (metricsTtl={}s, hourlyBucketTtl={}s)", config.metricsTtlSeconds, config.hourlyBucketTtlSeconds)

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        nudgeListener.stop()
        listenerScope.cancel()
        pubSubConnection.close()
        redisAConn.close()
        redisBConn.close()
        dataSource.close()
        log.info("metrics-service shut down")
    }
}
