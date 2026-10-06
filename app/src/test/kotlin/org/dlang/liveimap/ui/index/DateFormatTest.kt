package org.dlang.liveimap.ui.index

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.dlang.liveimap.settings.DateFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormatTest {
    private val utc = ZoneId.of("UTC")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val marchFifteen = epochUtc("2024-03-15T14:30:00Z")
    private val sameDayLater = epochUtc("2024-03-15T18:00:00Z")
    private val sameYear = epochUtc("2024-01-02T09:05:00Z")
    private val earlierYear = epochUtc("2023-11-04T08:00:00Z")
    private val laterYear = epochUtc("2025-01-01T00:00:00Z")
    private val noon = epochUtc("2024-06-15T12:00:00Z")

    @Test
    fun zeroIsEmpty() {
        for (format in DateFormat.entries) {
            assertEquals("", formatIndexDate(0L, format, "yyyy", noon, utc))
        }
    }

    @Test
    fun localClockHasNoWeekdayOrZone() {
        assertEquals(
            "2024-03-15 14:30",
            formatIndexDate(marchFifteen, DateFormat.Local, "", sameDayLater, utc),
        )
    }

    @Test
    fun shortUsesLocalDayAndYear() {
        assertEquals(
            "14:30",
            formatIndexDate(marchFifteen, DateFormat.Short, "", sameDayLater, utc),
        )
        assertEquals(
            monthDay(sameYear, utc),
            formatIndexDate(sameYear, DateFormat.Short, "", marchFifteen, utc),
        )
        assertEquals(
            monthYear(earlierYear, utc),
            formatIndexDate(earlierYear, DateFormat.Short, "", marchFifteen, utc),
        )
        assertEquals(
            monthYear(laterYear, utc),
            formatIndexDate(laterYear, DateFormat.Short, "", marchFifteen, utc),
        )

        val message = zonedEpoch(2024, 3, 15, 18, 0, losAngeles)
        val now = zonedEpoch(2024, 3, 15, 16, 0, losAngeles)
        assertEquals(
            "18:00",
            formatIndexDate(message, DateFormat.Short, "", now, losAngeles),
        )
        val nextLocalMorning = zonedEpoch(2024, 3, 16, 1, 0, losAngeles)
        assertEquals(
            monthDay(message, losAngeles),
            formatIndexDate(message, DateFormat.Short, "", nextLocalMorning, losAngeles),
        )
    }

    @Test
    fun relativeUnitsAndShortFallback() {
        assertEquals("now", formatIndexDate(noon - 44L, DateFormat.Relative, "", noon, utc))
        assertEquals("now", formatIndexDate(noon + 44L, DateFormat.Relative, "", noon, utc))
        assertEquals("1 min ago", formatIndexDate(noon - 45L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 1 min", formatIndexDate(noon + 45L, DateFormat.Relative, "", noon, utc))
        assertEquals("1 min ago", formatIndexDate(noon - 119L, DateFormat.Relative, "", noon, utc))
        assertEquals("2 min ago", formatIndexDate(noon - 120L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 2 min", formatIndexDate(noon + 120L, DateFormat.Relative, "", noon, utc))
        assertEquals("1 hour ago", formatIndexDate(noon - 3600L, DateFormat.Relative, "", noon, utc))
        assertEquals("1 hour ago", formatIndexDate(noon - 7199L, DateFormat.Relative, "", noon, utc))
        assertEquals("2 hours ago", formatIndexDate(noon - 7200L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 1 hour", formatIndexDate(noon + 3600L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 2 hours", formatIndexDate(noon + 7200L, DateFormat.Relative, "", noon, utc))
        assertEquals("1 day ago", formatIndexDate(noon - 86400L, DateFormat.Relative, "", noon, utc))
        assertEquals("6 days ago", formatIndexDate(noon - 6L * 86400L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 1 day", formatIndexDate(noon + 86400L, DateFormat.Relative, "", noon, utc))
        assertEquals("in 6 days", formatIndexDate(noon + 6L * 86400L, DateFormat.Relative, "", noon, utc))
        assertEquals(
            "6 days ago",
            formatIndexDate(noon - 7L * 86400L + 1L, DateFormat.Relative, "", noon, utc),
        )

        val weekAgo = noon - 7L * 86400L
        assertEquals(
            formatIndexDate(weekAgo, DateFormat.Short, "", noon, utc),
            formatIndexDate(weekAgo, DateFormat.Relative, "", noon, utc),
        )
        val weekAhead = noon + 7L * 86400L
        assertEquals(
            formatIndexDate(weekAhead, DateFormat.Short, "", noon, utc),
            formatIndexDate(weekAhead, DateFormat.Relative, "", noon, utc),
        )
    }

    @Test
    fun customPatternOrBadText() {
        assertEquals(
            "2024",
            formatIndexDate(marchFifteen, DateFormat.Custom, "yyyy", sameDayLater, utc),
        )
        assertEquals(
            "bad date pattern",
            formatIndexDate(marchFifteen, DateFormat.Custom, "", sameDayLater, utc),
        )
        assertEquals(
            "bad date pattern",
            formatIndexDate(marchFifteen, DateFormat.Custom, "'", sameDayLater, utc),
        )
    }

    private fun formatIndexDate(
        epochSeconds: Long,
        format: DateFormat,
        pattern: String,
        nowEpoch: Long,
        zone: ZoneId,
    ): String = org.dlang.liveimap.ui.index.formatIndexDate(
        epochSeconds = epochSeconds,
        format = format,
        pattern = pattern,
        nowEpoch = nowEpoch,
        zone = zone,
        nowWord = "now",
        minWord = "min",
        hourWord = "hour",
        hoursWord = "hours",
        dayWord = "day",
        daysWord = "days",
        agoPhrase = "%1\$d %2\$s ago",
        aheadPhrase = "in %1\$d %2\$s",
        badPattern = "bad date pattern",
    )

    private fun epochUtc(text: String): Long = Instant.parse(text).epochSecond

    private fun zonedEpoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()

    private fun monthDay(epochSeconds: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MMM d").format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    private fun monthYear(epochSeconds: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MMM yyyy").format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
}
