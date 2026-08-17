package dev.tracedown.worker.jobs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AggregationJobTest {

    // ── HourlyAggregationJob ──

    @Test
    fun `HourlyAggregationJob default interval is 900 seconds`() {
        val job = HourlyAggregationJob(redisB = { throw UnsupportedOperationException() })
        assertEquals(900L, job.intervalSeconds)
    }

    @Test
    fun `HourlyAggregationJob custom interval is respected`() {
        val job = HourlyAggregationJob(intervalSeconds = 120L, redisB = { throw UnsupportedOperationException() })
        assertEquals(120L, job.intervalSeconds)
    }

    @Test
    fun `HourlyAggregationJob name is correct`() {
        val job = HourlyAggregationJob(redisB = { throw UnsupportedOperationException() })
        assertEquals("HourlyAggregationJob", job.name)
    }

    @Test
    fun `HourlyAggregationJob implements ScheduledJob`() {
        assertIs<ScheduledJob>(HourlyAggregationJob(redisB = { throw UnsupportedOperationException() }))
    }

    // ── DailyAggregationJob ──

    @Test
    fun `DailyAggregationJob default interval is 3600 seconds`() {
        val job = DailyAggregationJob()
        assertEquals(3600L, job.intervalSeconds)
    }

    @Test
    fun `DailyAggregationJob custom interval is respected`() {
        val job = DailyAggregationJob(intervalSeconds = 1800L)
        assertEquals(1800L, job.intervalSeconds)
    }

    @Test
    fun `DailyAggregationJob name is correct`() {
        val job = DailyAggregationJob()
        assertEquals("DailyAggregationJob", job.name)
    }

    @Test
    fun `DailyAggregationJob implements ScheduledJob`() {
        assertIs<ScheduledJob>(DailyAggregationJob())
    }

    // ── SessionCleanupJob ──

    @Test
    fun `SessionCleanupJob default interval is 900 seconds`() {
        val job = SessionCleanupJob()
        assertEquals(900L, job.intervalSeconds)
    }

    @Test
    fun `SessionCleanupJob name is correct`() {
        val job = SessionCleanupJob()
        assertEquals("SessionCleanupJob", job.name)
    }

    @Test
    fun `SessionCleanupJob implements ScheduledJob`() {
        assertIs<ScheduledJob>(SessionCleanupJob())
    }
}
