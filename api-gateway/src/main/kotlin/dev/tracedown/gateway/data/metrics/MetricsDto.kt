package dev.tracedown.gateway.data.metrics

import kotlinx.serialization.Serializable

@Serializable
data class ServiceMetricsDto(
    val counters: MetricsCounters,
    val state: MetricsState,
    val percentiles: ResponsePercentiles? = null,
    /** Accessible-set totals — present only on aggregate (workspace/project) responses. */
    val projectCount: Int? = null,
    val serviceCount: Int? = null,
)

@Serializable
data class MetricsCounters(
    val probesTotal: Long,
    val probesSuccess: Long,
    val probesFailure: Long,
    val probesTimeout: Long,
)

@Serializable
data class MetricsState(
    val lastStatus: String?,
    val lastConsecutive: Long,
    val lastResponseMs: Long,
    val lastRunAt: Long?,
)

@Serializable
data class ResponsePercentiles(
    val p50: Long,
    val p95: Long,
    val p99: Long,
)

@Serializable
data class HourlyBucket(
    val hour: String,
    val total: Long,
    val success: Long,
    val failure: Long,
    val timeout: Long,
    val sumMs: Long,
    val callCount: Long = 0,
)

/** One aggregate bucket from `probe_aggregates`, as a statistics time-series point. */
@Serializable
data class StatBucket(
    /** ISO-8601 bucket start (UTC). */
    val bucketStart: String,
    val p50Ms: Int?,
    val p95Ms: Int?,
    val p99Ms: Int?,
    /** Percentage 0..100 (the stored 0..1 fraction ×100); null when the bucket had no runs. */
    val uptimePct: Double?,
    val errorRatePct: Double?,
    val probeCount: Int,
)

/** Per-region (per probe agent) statistics series for a service. */
@Serializable
data class RegionSeries(
    val agentId: Long,
    val agentLabel: String,
    val buckets: List<StatBucket>,
)

/**
 * Deep statistics for a service over a window, read straight from `probe_aggregates`:
 * the all-agents [overall] trend plus a per-region breakdown. Bucket granularity is
 * hourly for short windows, daily for long ones.
 */
@Serializable
data class ServiceStatisticsDto(
    val window: String,
    /** "hourly" | "daily". */
    val bucketType: String,
    val overall: List<StatBucket>,
    val regions: List<RegionSeries>,
)
