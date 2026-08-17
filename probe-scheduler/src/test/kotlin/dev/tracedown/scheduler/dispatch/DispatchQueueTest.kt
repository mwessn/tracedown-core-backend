package dev.tracedown.scheduler.dispatch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for dispatch queue backpressure behavior.
 *
 * Verifies that:
 * 1. Jobs are queued when workers are busy (not dropped)
 * 2. Jobs are dropped when the queue capacity is exceeded
 * 3. Concurrency is bounded by worker count
 */
class DispatchQueueTest {

    @Test
    fun `jobs are queued when workers are busy`() = runBlocking {
        val queueSize = 100
        val workers = 2
        val dispatched = CopyOnWriteArrayList<UUID>()
        val blockLatch = CountDownLatch(1)

        val channel = Channel<UUID>(queueSize)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Start workers that block until we release the latch (simulating slow dispatch)
        val workersStarted = AtomicInteger(0)
        repeat(workers) {
            scope.launch {
                for (serviceId in channel) {
                    workersStarted.incrementAndGet()
                    blockLatch.await() // Block until released
                    dispatched.add(serviceId)
                }
            }
        }

        // Enqueue more jobs than workers — they should queue, not drop
        val jobCount = 20
        val ids = (1..jobCount).map { UUID.randomUUID() }
        for (id in ids) {
            val sent = channel.trySend(id)
            assertTrue(sent.isSuccess, "Job should be queued (capacity=$queueSize, sent=$id)")
        }

        // Workers should be blocked — only 'workers' count should have started
        delay(200)
        assertEquals(workers, workersStarted.get(), "Only $workers workers should be processing")
        assertTrue(dispatched.isEmpty(), "No jobs should be completed while workers are blocked")

        // Release the latch — all jobs should complete
        blockLatch.countDown()
        delay(500)

        assertEquals(jobCount, dispatched.size, "All $jobCount jobs should have been dispatched")
        scope.cancel()
    }

    @Test
    fun `jobs are dropped when queue is full`() {
        val queueSize = 5
        val channel = Channel<UUID>(queueSize)

        // Fill the queue without any consumers draining it
        val enqueued = mutableListOf<UUID>()
        val dropped = mutableListOf<UUID>()

        for (i in 1..queueSize + 10) {
            val id = UUID.randomUUID()
            val result = channel.trySend(id)
            if (result.isSuccess) {
                enqueued.add(id)
            } else {
                dropped.add(id)
            }
        }

        assertEquals(queueSize, enqueued.size, "Exactly $queueSize jobs should be enqueued")
        assertEquals(10, dropped.size, "10 jobs should be dropped when queue is full")

        channel.close()
    }

    @Test
    fun `concurrency is bounded by worker count`() = runBlocking {
        val queueSize = 100
        val workers = 5
        val concurrentCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val channel = Channel<UUID>(queueSize)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Workers track maximum concurrency observed
        repeat(workers) {
            scope.launch {
                for (serviceId in channel) {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(50) // Simulate dispatch work
                    concurrentCount.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
        }

        // Enqueue 20 jobs
        repeat(20) { channel.trySend(UUID.randomUUID()) }
        channel.close()

        // Wait for all to complete
        delay(1000)

        assertEquals(20, completed.get(), "All 20 jobs should complete")
        assertTrue(maxConcurrent.get() <= workers, "Max concurrency ($maxConcurrent) must not exceed $workers workers")
        assertTrue(maxConcurrent.get() >= 2, "Should observe some concurrency (got ${maxConcurrent.get()})")

        scope.cancel()
    }

    @Test
    fun `queue drains in order after burst`() = runBlocking {
        val queueSize = 50
        val workers = 1  // Single worker to guarantee ordering
        val processed = CopyOnWriteArrayList<Int>()

        val channel = Channel<Int>(queueSize)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        scope.launch {
            for (item in channel) {
                processed.add(item)
            }
        }

        // Burst-enqueue 30 items
        for (i in 1..30) {
            channel.trySend(i)
        }
        channel.close()

        delay(200)

        assertEquals(30, processed.size, "All 30 items should be processed")
        assertEquals((1..30).toList(), processed.toList(), "Items should be processed in FIFO order")

        scope.cancel()
    }
}
