package dev.tracedown.scheduler.scheduling

import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Quartz job that enqueues a service for probe dispatch.
 *
 * The actual work (lock acquisition, variable resolution, agent HTTP dispatch,
 * result publishing) is handled by the [DispatchQueue] worker pool. This job
 * simply enqueues the service ID and returns immediately, keeping the Quartz
 * thread pool free for timing accuracy even under high load.
 */
class ProbeJob : Job {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(context: JobExecutionContext) {
        val serviceId = UUID.fromString(context.mergedJobDataMap.getString("serviceId"))
        val enqueued = ProbeJobContext.dispatchQueue.enqueue(serviceId)
        if (!enqueued) {
            log.debug("dispatch queue full — dropped service {}", serviceId)
        }
    }
}
