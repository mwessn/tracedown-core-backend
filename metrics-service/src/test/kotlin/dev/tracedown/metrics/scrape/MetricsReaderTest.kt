package dev.tracedown.metrics.scrape

import dev.tracedown.metrics.cache.MetricsWriterTest.FakeRedis
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MetricsReaderTest {

    private lateinit var redis: FakeRedis
    private lateinit var reader: MetricsReader

    companion object {
        private const val METRICS_TTL = 86400L
    }

    @BeforeEach
    fun setUp() {
        redis = FakeRedis()
        reader = MetricsReader(redis.commands(), METRICS_TTL)
    }

    @Test
    fun `read returns counters and state from Redis`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"

        redis.hashes[counterKey] = mutableMapOf(
            "probes_total" to "50",
            "probes_success" to "45",
            "probes_failure" to "5",
        )
        redis.hashes[stateKey] = mutableMapOf(
            "last_status" to "success",
            "last_response_ms" to "200",
            "last_consecutive" to "12",
        )

        val result = reader.read(serviceId)

        assertNotNull(result)
        assertEquals("50", result!!.counters["probes_total"])
        assertEquals("45", result.counters["probes_success"])
        assertEquals("5", result.counters["probes_failure"])
        assertEquals("success", result.state["last_status"])
        assertEquals("200", result.state["last_response_ms"])
        assertEquals("12", result.state["last_consecutive"])
    }

    @Test
    fun `read returns null when no data exists`() = runBlocking {
        val serviceId = UUID.randomUUID()

        val result = reader.read(serviceId)

        assertNull(result)
    }

    @Test
    fun `read calls expire to reset TTL on read`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"

        redis.hashes[counterKey] = mutableMapOf("probes_total" to "10")
        redis.hashes[stateKey] = mutableMapOf("last_status" to "success")

        // Set TTLs to something lower to verify they get reset
        redis.ttls[counterKey] = 1000L
        redis.ttls[stateKey] = 1000L

        reader.read(serviceId)

        assertEquals(METRICS_TTL, redis.ttls[counterKey])
        assertEquals(METRICS_TTL, redis.ttls[stateKey])
    }

    @Test
    fun `read does not call expire when counters hash is empty`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"

        // Only state, no counters
        redis.hashes[stateKey] = mutableMapOf("last_status" to "failure")

        reader.read(serviceId)

        assertNull(redis.ttls[counterKey], "expire should not be called for empty counters")
        assertEquals(METRICS_TTL, redis.ttls[stateKey])
    }
}
