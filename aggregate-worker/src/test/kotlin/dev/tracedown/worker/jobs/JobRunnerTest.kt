package dev.tracedown.worker.jobs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JobRunnerTest {

    @Test
    fun `launchJob executes the job at least once before first delay`() = runTest {
        val tracker = TrackingJob(intervalSeconds = 60L)
        val job = launchJob(tracker)

        // Advance past the first execution (immediate) + one interval
        advanceTimeBy(1)
        assertTrue(tracker.executionCount >= 1, "job should execute at least once immediately")

        job.cancelAndJoin()
    }

    @Test
    fun `launchJob executes multiple times across intervals`() = runTest {
        val tracker = TrackingJob(intervalSeconds = 10L)
        val job = launchJob(tracker)

        // First execution is immediate, then every 10 seconds
        advanceTimeBy(1) // execute once
        val afterFirst = tracker.executionCount
        assertTrue(afterFirst >= 1)

        advanceTimeBy(10_001) // execute second time after delay
        assertTrue(tracker.executionCount >= 2)

        advanceTimeBy(10_001) // execute third time
        assertTrue(tracker.executionCount >= 3)

        job.cancelAndJoin()
    }

    @Test
    fun `launchJob continues after execute throws`() = runTest {
        val failOnce = FailOnceJob(intervalSeconds = 5L)
        val job = launchJob(failOnce)

        advanceTimeBy(1) // first execution — throws
        assertEquals(1, failOnce.executionCount)

        advanceTimeBy(5_001) // second execution — succeeds
        assertEquals(2, failOnce.executionCount)

        job.cancelAndJoin()
    }

    @Test
    fun `launchJob stops when cancelled`() = runTest {
        val tracker = TrackingJob(intervalSeconds = 5L)
        val job = launchJob(tracker)

        advanceTimeBy(1)
        job.cancelAndJoin()

        val countAfterCancel = tracker.executionCount
        advanceTimeBy(20_000)
        assertEquals(countAfterCancel, tracker.executionCount, "no more executions after cancel")
    }

    /** Simple job that counts executions. */
    private class TrackingJob(
        override val intervalSeconds: Long,
    ) : ScheduledJob {
        override val name = "TrackingJob"
        var executionCount = 0
        override suspend fun execute() { executionCount++ }
    }

    /** Job that throws on the first execution. */
    private class FailOnceJob(
        override val intervalSeconds: Long,
    ) : ScheduledJob {
        override val name = "FailOnceJob"
        var executionCount = 0
        override suspend fun execute() {
            executionCount++
            if (executionCount == 1) throw RuntimeException("simulated failure")
        }
    }
}
