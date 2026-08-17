package dev.tracedown.metrics.scrape

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrometheusFormatterTest {

    @Test
    fun `formats counter metrics with status labels`() {
        val info = ServiceInfo(
            id = java.util.UUID.randomUUID(),
            name = "my-api",
            projectName = "core",
            workspaceName = "prod",
        )
        val metrics = ServiceMetrics(
            counters = mapOf(
                "probes_total" to "100",
                "probes_success" to "95",
                "probes_failure" to "3",
                "probes_timeout" to "2",
            ),
            state = mapOf(
                "last_status" to "success",
                "last_response_ms" to "142",
                "last_consecutive" to "47",
            ),
        )

        val output = PrometheusFormatter.format(listOf(info to metrics), emptyMap())

        assertTrue(output.contains("tracedown_probes_total{service=\"my-api\",project=\"core\",workspace=\"prod\",status=\"success\"} 95"))
        assertTrue(output.contains("tracedown_probes_total{service=\"my-api\",project=\"core\",workspace=\"prod\",status=\"failure\"} 3"))
        assertTrue(output.contains("tracedown_probes_total{service=\"my-api\",project=\"core\",workspace=\"prod\",status=\"timeout\"} 2"))
        assertTrue(output.contains("tracedown_service_up{service=\"my-api\",project=\"core\",workspace=\"prod\"} 1"))
        assertTrue(output.contains("tracedown_last_response_ms{service=\"my-api\",project=\"core\",workspace=\"prod\"} 142"))
        assertTrue(output.contains("tracedown_consecutive_count{service=\"my-api\",project=\"core\",workspace=\"prod\",status=\"success\"} 47"))
    }

    @Test
    fun `includes custom labels`() {
        val info = ServiceInfo(
            id = java.util.UUID.randomUUID(),
            name = "api",
            projectName = "proj",
            workspaceName = "ws",
        )
        val metrics = ServiceMetrics(
            counters = mapOf("probes_success" to "10"),
            state = mapOf("last_status" to "success", "last_response_ms" to "50"),
        )

        val output = PrometheusFormatter.format(
            listOf(info to metrics),
            mapOf("env" to "prod", "region" to "eu"),
        )

        assertTrue(output.contains("env=\"prod\""))
        assertTrue(output.contains("region=\"eu\""))
    }

    @Test
    fun `service_up is 0 for non-success status`() {
        val info = ServiceInfo(
            id = java.util.UUID.randomUUID(),
            name = "api",
            projectName = "proj",
            workspaceName = "ws",
        )
        val metrics = ServiceMetrics(
            counters = mapOf("probes_failure" to "5"),
            state = mapOf("last_status" to "failure"),
        )

        val output = PrometheusFormatter.format(listOf(info to metrics), emptyMap())

        assertTrue(output.contains("tracedown_service_up{service=\"api\",project=\"proj\",workspace=\"ws\"} 0"))
    }

    @Test
    fun `formats multiple services`() {
        val info1 = ServiceInfo(java.util.UUID.randomUUID(), "api-1", "proj", "ws")
        val info2 = ServiceInfo(java.util.UUID.randomUUID(), "api-2", "proj", "ws")
        val metrics1 = ServiceMetrics(mapOf("probes_success" to "50"), mapOf("last_status" to "success", "last_response_ms" to "100"))
        val metrics2 = ServiceMetrics(mapOf("probes_success" to "30"), mapOf("last_status" to "failure", "last_response_ms" to "500"))

        val output = PrometheusFormatter.format(
            listOf(info1 to metrics1, info2 to metrics2),
            emptyMap(),
        )

        assertTrue(output.contains("service=\"api-1\""))
        assertTrue(output.contains("service=\"api-2\""))
    }

    @Test
    fun `empty entries returns empty string`() {
        assertEquals("", PrometheusFormatter.format(emptyList(), emptyMap()))
    }

    @Test
    fun `escapes label values`() {
        val info = ServiceInfo(
            id = java.util.UUID.randomUUID(),
            name = "api with \"quotes\"",
            projectName = "proj",
            workspaceName = "ws",
        )
        val metrics = ServiceMetrics(
            counters = mapOf("probes_success" to "1"),
            state = mapOf("last_status" to "success"),
        )

        val output = PrometheusFormatter.format(listOf(info to metrics), emptyMap())

        assertTrue(output.contains("service=\"api with \\\"quotes\\\"\""))
    }

    @Test
    fun `skips zero-count statuses`() {
        val info = ServiceInfo(java.util.UUID.randomUUID(), "api", "proj", "ws")
        val metrics = ServiceMetrics(
            counters = mapOf("probes_success" to "10", "probes_failure" to "0"),
            state = mapOf("last_status" to "success"),
        )

        val output = PrometheusFormatter.format(listOf(info to metrics), emptyMap())

        assertFalse(output.contains("status=\"failure\""))
    }

    @Test
    fun `includes TYPE and HELP headers`() {
        val info = ServiceInfo(java.util.UUID.randomUUID(), "api", "proj", "ws")
        val metrics = ServiceMetrics(
            counters = mapOf("probes_success" to "1"),
            state = mapOf("last_status" to "success"),
        )

        val output = PrometheusFormatter.format(listOf(info to metrics), emptyMap())

        assertTrue(output.contains("# TYPE tracedown_probes_total counter"))
        assertTrue(output.contains("# HELP tracedown_probes_total"))
        assertTrue(output.contains("# TYPE tracedown_service_up gauge"))
        assertTrue(output.contains("# TYPE tracedown_last_response_ms gauge"))
        assertTrue(output.contains("# TYPE tracedown_consecutive_count gauge"))
    }
}
