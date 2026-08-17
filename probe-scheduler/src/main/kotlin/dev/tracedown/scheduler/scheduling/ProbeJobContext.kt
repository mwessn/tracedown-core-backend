package dev.tracedown.scheduler.scheduling

import dev.tracedown.scheduler.dispatch.DispatchQueue

/**
 * Static holder for ProbeJob dependencies.
 *
 * Quartz creates Job instances via reflection, so we can't use
 * constructor injection. This is initialized once at startup and
 * read by ProbeJob.execute().
 */
object ProbeJobContext {

    lateinit var dispatchQueue: DispatchQueue
        private set

    fun init(dispatchQueue: DispatchQueue) {
        this.dispatchQueue = dispatchQueue
    }
}
