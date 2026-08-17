package dev.tracedown.scheduler.window

import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.DateTime
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.TimeZone

/**
 * Evaluates whether the current time falls within a service's maintenance
 * window, defined as `RRULE[/durationMinutes[/timezone]]` — each occurrence
 * of the rule opens a window of the given length (default 60 minutes). The
 * rule's clock fields (BYHOUR/BYMINUTE) are interpreted in the spec's
 * timezone segment when present, else in the caller-provided default (the
 * org's default timezone). Timezone names contain slashes, so the spec is
 * split as: rrule / duration / everything-after.
 *
 * When the service window is active, the probe should be skipped.
 */
object ServiceWindowEvaluator {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns true if the current time is within the service window.
     *
     * @param spec `RRULE[/durationMinutes[/timezone]]` defining when NOT to probe, or null
     * @param defaultZone IANA zone used when the spec has no timezone segment
     * @param now current time (injectable for testing)
     */
    fun isInWindow(spec: String?, defaultZone: String = "UTC", now: Instant = Instant.now()): Boolean {
        if (spec.isNullOrBlank()) return false

        val parts = spec.split('/')
        val rrulePart = parts[0]
        val durationMinutes = if (parts.size > 1) {
            parts[1].toLongOrNull()?.takeIf { it in 1..1440 } ?: return false
        } else {
            60L
        }
        val zoneName = if (parts.size > 2) parts.drop(2).joinToString("/") else defaultZone
        val zone = TimeZone.getTimeZone(zoneName)
        val windowMs = durationMinutes * 60_000L

        return try {
            val rule = RecurrenceRule(rrulePart)
            // Any occurrence covering `now` must start within the last window
            // length — iterate from there and stop once past `now`.
            val iterator = rule.iterator(
                DateTime(zone, now.toEpochMilli() - windowMs)
            )
            var steps = 0
            while (iterator.hasNext() && steps < 1000) {
                val startMs = iterator.nextDateTime().timestamp
                if (startMs > now.toEpochMilli()) return false
                if (now.toEpochMilli() < startMs + windowMs) return true
                steps++
            }
            false
        } catch (e: Exception) {
            log.warn("failed to parse service window '{}': {}", spec, e.message)
            false
        }
    }

}
