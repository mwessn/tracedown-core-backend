package dev.tracedown.metrics.scrape

import java.util.UUID

/**
 * Formats raw service metrics into Prometheus exposition format text.
 *
 * All metrics are prefixed with `tracedown_` and include service/project/workspace
 * labels plus any custom labels from the integration config.
 */
object PrometheusFormatter {

    /**
     * Formats a list of service metrics into Prometheus exposition format.
     *
     * @param entries service info paired with their raw metric data
     * @param customLabels additional labels from the integration config
     * @return Prometheus exposition format text
     */
    fun format(
        entries: List<Pair<ServiceInfo, ServiceMetrics>>,
        customLabels: Map<String, String>,
        aggregates: Map<UUID, ServiceAggregate> = emptyMap(),
    ): String {
        if (entries.isEmpty()) return ""

        val sb = StringBuilder()

        // Counter: tracedown_probes_total
        sb.appendLine("# HELP tracedown_probes_total Total probe executions")
        sb.appendLine("# TYPE tracedown_probes_total counter")
        for ((info, metrics) in entries) {
            val base = buildLabels(info, customLabels)
            for (status in listOf("success", "failure", "timeout", "error")) {
                val count = metrics.counters["probes_$status"] ?: "0"
                if (count != "0") {
                    sb.appendLine("tracedown_probes_total{${base},status=\"$status\"} $count")
                }
            }
        }

        // Gauge: tracedown_service_up
        sb.appendLine("# HELP tracedown_service_up Current service status (1=up, 0=down)")
        sb.appendLine("# TYPE tracedown_service_up gauge")
        for ((info, metrics) in entries) {
            val labels = buildLabels(info, customLabels)
            val lastStatus = metrics.state["last_status"] ?: "unknown"
            val up = if (lastStatus == "success") "1" else "0"
            sb.appendLine("tracedown_service_up{$labels} $up")
        }

        // Gauge: tracedown_last_response_ms
        sb.appendLine("# HELP tracedown_last_response_ms Last probe response time in milliseconds")
        sb.appendLine("# TYPE tracedown_last_response_ms gauge")
        for ((info, metrics) in entries) {
            val ms = metrics.state["last_response_ms"]
            if (ms != null) {
                val labels = buildLabels(info, customLabels)
                sb.appendLine("tracedown_last_response_ms{$labels} $ms")
            }
        }

        // Gauge: tracedown_consecutive_count
        sb.appendLine("# HELP tracedown_consecutive_count Consecutive probes with current status")
        sb.appendLine("# TYPE tracedown_consecutive_count gauge")
        for ((info, metrics) in entries) {
            val consecutive = metrics.state["last_consecutive"]
            val status = metrics.state["last_status"]
            if (consecutive != null && status != null) {
                val labels = buildLabels(info, customLabels)
                sb.appendLine("tracedown_consecutive_count{${labels},status=\"$status\"} $consecutive")
            }
        }

        // Gauge: tracedown_uptime_pct — fraction of successful probes (0..1),
        // from the latest probe_aggregates all-agents rollup (spec §13.4).
        sb.appendLine("# HELP tracedown_uptime_pct Fraction of successful probes in the latest aggregate bucket (0-1)")
        sb.appendLine("# TYPE tracedown_uptime_pct gauge")
        for ((info, _) in entries) {
            val value = aggregates[info.id]?.uptimePct ?: continue
            sb.appendLine("tracedown_uptime_pct{${buildLabels(info, customLabels)}} $value")
        }

        // Gauge: tracedown_error_rate — fraction of failed probes (0..1),
        // from the latest probe_aggregates all-agents rollup (spec §13.4).
        sb.appendLine("# HELP tracedown_error_rate Fraction of failed probes in the latest aggregate bucket (0-1)")
        sb.appendLine("# TYPE tracedown_error_rate gauge")
        for ((info, _) in entries) {
            val value = aggregates[info.id]?.errorRate ?: continue
            sb.appendLine("tracedown_error_rate{${buildLabels(info, customLabels)}} $value")
        }

        return sb.toString().trimEnd()
    }

    private fun buildLabels(info: ServiceInfo, customLabels: Map<String, String>): String {
        val labels = mutableListOf(
            "service" to info.name,
            "project" to info.projectName,
            "workspace" to info.workspaceName,
        )
        for ((key, value) in customLabels) {
            labels.add(key to value)
        }
        return labels.joinToString(",") { (k, v) -> "$k=\"${escapeLabel(v)}\"" }
    }

    private fun escapeLabel(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
