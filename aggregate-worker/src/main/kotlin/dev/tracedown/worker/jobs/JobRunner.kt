package dev.tracedown.worker.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** A recurring background job executed on a fixed interval. */
interface ScheduledJob {
    /** Human-readable name for logging. */
    val name: String
    /** Interval between executions in seconds. */
    val intervalSeconds: Long
    /** Executes one iteration of the job. */
    suspend fun execute()
}

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.JobRunner")

/** Launches a [ScheduledJob] as a coroutine that loops until cancelled. */
fun CoroutineScope.launchJob(job: ScheduledJob): Job = launch {
    log.info("{}: started (interval={}s)", job.name, job.intervalSeconds)
    while (isActive) {
        try {
            job.execute()
        } catch (e: Exception) {
            log.error("{}: execution failed", job.name, e)
        }
        delay(job.intervalSeconds * 1000)
    }
}
