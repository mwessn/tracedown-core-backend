package dev.tracedown.scheduler.scheduling

import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.UUID

/**
 * Manages the Quartz scheduler with a RAM job store.
 *
 * All job definitions are derived from the database — the RAM store
 * is rebuilt on startup and kept in sync via Redis pub/sub + periodic
 * consistency sweep.
 */
class QuartzManager(threadPoolSize: Int) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler: Scheduler

    init {
        val props = Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", "ProbeScheduler")
            setProperty("org.quartz.threadPool.threadCount", threadPoolSize.toString())
            setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
        }
        scheduler = StdSchedulerFactory(props).scheduler
    }

    /** Starts the Quartz scheduler. */
    fun start() {
        scheduler.start()
        log.info("Quartz scheduler started")
    }

    /** Shuts down the Quartz scheduler gracefully. */
    fun shutdown() {
        scheduler.shutdown(true)
        log.info("Quartz scheduler shut down")
    }

    /** Schedules a recurring system job (e.g. health challenges). */
    fun scheduleSystemJob(jobClass: Class<out Job>, name: String, cronExpression: String) {
        val jobKey = JobKey(name, "system")
        if (scheduler.checkExists(jobKey)) return

        val job = JobBuilder.newJob(jobClass)
            .withIdentity(jobKey)
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey(name, "system"))
            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
            .build()

        scheduler.scheduleJob(job, trigger)
        log.info("scheduled system job '{}' with cron '{}'", name, cronExpression)
    }

    /**
     * Schedules a service for probing. Replaces any existing job for this service.
     *
     * @param serviceId the service UUID
     * @param cronExpression 5-field cron (e.g. "star-slash-5 * * * *")
     */
    fun scheduleService(serviceId: UUID, cronExpression: String) {
        val jobKey = jobKey(serviceId)
        val triggerKey = triggerKey(serviceId)

        // Remove existing job if any
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey)
        }

        val quartzCron = toQuartzCron(cronExpression)

        val job = JobBuilder.newJob(ProbeJob::class.java)
            .withIdentity(jobKey)
            .usingJobData("serviceId", serviceId.toString())
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKey)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(quartzCron)
                    .withMisfireHandlingInstructionDoNothing()
            )
            .build()

        scheduler.scheduleJob(job, trigger)
        log.debug("scheduled service {} with cron '{}'", serviceId, cronExpression)
    }

    /** Removes a service's probe job. */
    fun unscheduleService(serviceId: UUID) {
        val jobKey = jobKey(serviceId)
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey)
            log.debug("unscheduled service {}", serviceId)
        }
    }

    /** Returns the set of currently scheduled service IDs. */
    fun getScheduledServiceIds(): Set<UUID> {
        return scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("probes"))
            .mapNotNull { key ->
                val raw = key.name.removePrefix("probe-")
                try { UUID.fromString(raw) } catch (_: Exception) { null }
            }
            .toSet()
    }

    /**
     * Converts a 5-field cron (minute-level) to Quartz's 6-field format.
     * Prepends ``0`` for seconds.
     */
    private fun toQuartzCron(cron: String): String {
        val fields = cron.trim().split("\\s+".toRegex())
        require(fields.size == 5) { "Expected 5-field cron, got ${fields.size}: $cron" }
        // Quartz cron: seconds minute hour dayOfMonth month dayOfWeek
        // Input cron:  minute hour dayOfMonth month dayOfWeek
        // Quartz does not support both dayOfMonth and dayOfWeek — use '?' for unset
        val dayOfWeek = if (fields[4] == "*") "?" else fields[4]
        val dayOfMonth = if (dayOfWeek != "?" && fields[2] == "*") "?" else fields[2]
        return "0 ${fields[0]} ${fields[1]} $dayOfMonth ${fields[3]} $dayOfWeek"
    }

    private fun jobKey(serviceId: UUID) = JobKey("probe-$serviceId", "probes")
    private fun triggerKey(serviceId: UUID) = TriggerKey("trigger-$serviceId", "probes")
}
