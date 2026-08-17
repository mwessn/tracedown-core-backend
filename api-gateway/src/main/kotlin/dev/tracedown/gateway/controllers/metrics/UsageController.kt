package dev.tracedown.gateway.controllers.metrics

import dev.tracedown.gateway.data.UsageResponse
import io.lettuce.core.api.sync.RedisCommands
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Serves resource usage (requests + measured ingress/egress bytes + bytes
 * dispatched to probe agents) by summing
 * the per-level hourly usage buckets the metrics-service writes to Redis B
 * (`metrics:usage:{svc|proj|ws|org}:{id}:h:{yyyyMMddHH}`). Buckets are
 * immutable once their hour passes; the window is capped to the shorter of the
 * request, 7 days, and the probe-result retention period.
 */
object UsageController {

    const val MAX_WINDOW_HOURS = 7 * 24
    const val MIN_WINDOW_HOURS = 2

    private lateinit var redisProvider: () -> RedisCommands<String, String>
    private var retentionHours: Int = MAX_WINDOW_HOURS
    private val redis get() = redisProvider()

    private val hourFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

    fun init(redis: () -> RedisCommands<String, String>, resultRetentionDays: Int) {
        this.redisProvider = redis
        this.retentionHours = if (resultRetentionDays > 0) resultRetentionDays * 24 else MAX_WINDOW_HOURS
    }

    fun forService(serviceId: UUID, requestedHours: Int): UsageResponse = usage("svc", serviceId, requestedHours)
    fun forProject(projectId: UUID, requestedHours: Int): UsageResponse = usage("proj", projectId, requestedHours)
    fun forWorkspace(workspaceId: UUID, requestedHours: Int): UsageResponse = usage("ws", workspaceId, requestedHours)
    fun forOrg(orgId: UUID, requestedHours: Int): UsageResponse = usage("org", orgId, requestedHours)

    private fun usage(level: String, id: UUID, requestedHours: Int): UsageResponse {
        val hours = requestedHours
            .coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)
            .coerceAtMost(retentionHours)
            .coerceAtLeast(1)

        val now = Instant.now()
        // Pipeline the per-hour HGETALLs via the async view — a 7-day window is
        // 168 keys, and the async commands batch on one round trip.
        val async = redis.statefulConnection.async()
        val futures = (hours - 1 downTo 0).map { back ->
            val bucket = hourFormatter.format(now.minusSeconds(back * 3600L))
            async.hgetall("metrics:usage:$level:$id:h:$bucket")
        }

        var requests = 0L
        var ingress = 0L
        var egress = 0L
        var agentEgress = 0L
        for (future in futures) {
            val fields = future.get()
            requests += fields["requests"]?.toLongOrNull() ?: 0L
            ingress += fields["ingress"]?.toLongOrNull() ?: 0L
            egress += fields["egress"]?.toLongOrNull() ?: 0L
            agentEgress += fields["agent_egress"]?.toLongOrNull() ?: 0L
        }
        return UsageResponse(
            windowHours = hours,
            requests = requests,
            ingressBytes = ingress,
            egressBytes = egress,
            agentEgressBytes = agentEgress,
        )
    }
}
