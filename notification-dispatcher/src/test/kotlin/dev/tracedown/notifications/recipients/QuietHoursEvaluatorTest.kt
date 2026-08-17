package dev.tracedown.notifications.recipients

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quiet hours use the same `RRULE[/durationMinutes[/timezone]]` recurrence format
 * as the service maintenance window.
 *   - 09:00–17:00 daily  → FREQ=DAILY;BYHOUR=9;BYMINUTE=0/480/<tz>   (8h = 480m)
 *   - 22:00–07:00 daily  → FREQ=DAILY;BYHOUR=22;BYMINUTE=0/540/<tz>  (9h = 540m, wraps)
 */
class QuietHoursEvaluatorTest {

    private val dayWindow = "FREQ=DAILY;BYHOUR=9;BYMINUTE=0/480/Europe/Amsterdam"
    private val nightWindow = "FREQ=DAILY;BYHOUR=22;BYMINUTE=0/540/Europe/Amsterdam"

    private fun amsterdam(date: LocalDate, time: LocalTime) =
        ZonedDateTime.of(date, time, ZoneId.of("Europe/Amsterdam")).toInstant()

    @Test
    fun `null quiet hours returns false`() {
        assertFalse(QuietHoursEvaluator.isInQuietHours(null))
    }

    @Test
    fun `blank quiet hours returns false`() {
        assertFalse(QuietHoursEvaluator.isInQuietHours(""))
        assertFalse(QuietHoursEvaluator.isInQuietHours("   "))
    }

    @Test
    fun `same-day window - inside`() {
        val now = amsterdam(LocalDate.of(2026, 5, 5), LocalTime.of(12, 0))
        assertTrue(QuietHoursEvaluator.isInQuietHours(dayWindow, now))
    }

    @Test
    fun `same-day window - outside`() {
        val now = amsterdam(LocalDate.of(2026, 5, 5), LocalTime.of(20, 0))
        assertFalse(QuietHoursEvaluator.isInQuietHours(dayWindow, now))
    }

    @Test
    fun `overnight window - inside late evening`() {
        val now = amsterdam(LocalDate.of(2026, 5, 5), LocalTime.of(23, 30))
        assertTrue(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }

    @Test
    fun `overnight window - inside early morning`() {
        val now = amsterdam(LocalDate.of(2026, 5, 6), LocalTime.of(5, 0))
        assertTrue(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }

    @Test
    fun `overnight window - outside midday`() {
        val now = amsterdam(LocalDate.of(2026, 5, 5), LocalTime.of(12, 0))
        assertFalse(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }

    @Test
    fun `respects timezone - UTC vs Amsterdam`() {
        // 21:00 UTC = 23:00 Amsterdam (UTC+2 in summer) → inside quiet hours
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 5, 5),
            LocalTime.of(21, 0),
            ZoneId.of("UTC"),
        ).toInstant()
        assertTrue(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }

    @Test
    fun `invalid format returns false`() {
        assertFalse(QuietHoursEvaluator.isInQuietHours("garbage"))
        assertFalse(QuietHoursEvaluator.isInQuietHours("FREQ=DAILY;BYHOUR=22/9999/Europe/Amsterdam"))
        assertFalse(QuietHoursEvaluator.isInQuietHours("FREQ=DAILY;BYHOUR=22;BYMINUTE=0/540/Invalid/Zone"))
    }

    @Test
    fun `boundary - exactly at start is inside`() {
        val now = amsterdam(LocalDate.of(2026, 5, 5), LocalTime.of(22, 0))
        assertTrue(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }

    @Test
    fun `boundary - exactly at end is outside`() {
        val now = amsterdam(LocalDate.of(2026, 5, 6), LocalTime.of(7, 0))
        assertFalse(QuietHoursEvaluator.isInQuietHours(nightWindow, now))
    }
}
