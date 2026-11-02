package com.warden.blocker.ui.times

import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimesFormatTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Monday 2 Nov 2026, 12:00. */
    private val now = at(2026, 11, 2, 12)

    @Test fun labelsByDistance() {
        assertEquals("today 18:00", dateTimeLabel(at(2026, 11, 2, 18), now, zone, true))
        assertEquals("Sun 18:00", dateTimeLabel(at(2026, 11, 8, 18), now, zone, true))
        assertEquals("Mon 9 Nov 07:00", dateTimeLabel(at(2026, 11, 9, 7), now, zone, true))
        assertEquals("Sun 3 Jan 2027 6:00 PM", dateTimeLabel(at(2027, 1, 3, 18), now, zone, false))
    }

    @Test fun quickChoicesBeforeTheEvening() {
        val q = quickUntilChoices(now, zone, true)
        assertEquals(listOf("Until 22:00", "Until tomorrow 07:00", "Until Sun 18:00"), q.map { it.label })
        assertEquals(at(2026, 11, 2, 22), q[0].at)
        assertEquals(at(2026, 11, 3, 7), q[1].at)
        assertEquals(at(2026, 11, 8, 18), q[2].at)
    }

    @Test fun quickChoicesLateOnSunday() {
        // Sunday 8 Nov 21:50: tonight 22:00 is too close; Sunday 18:00 means next week.
        val q = quickUntilChoices(at(2026, 11, 8, 21, 50), zone, false)
        assertEquals(listOf("Until tomorrow 7:00 AM", "Until Sun 15 Nov 6:00 PM"), q.map { it.label })
    }

    @Test fun quickChoiceOnSundayAfternoonIsToday() {
        val q = quickUntilChoices(at(2026, 11, 8, 14), zone, true)
        assertEquals("Until today 18:00", q.last().label)
    }

    @Test fun scheduleLines() {
        val once = Schedule(name = "Trip", daysMask = 0, startMinute = 0, endMinute = 0, onceStartAt = at(2026, 11, 6, 18), onceEndAt = at(2026, 11, 9, 8))
        assertEquals("One-time · Fri 18:00 – Mon 9 Nov 08:00", scheduleLine(once, now, zone, true, ""))
        assertEquals("One-time · ended · Fri 6 Nov 18:00 – today 08:00", scheduleLine(once, at(2026, 11, 9, 12), zone, true, ""))

        val ranged = Schedule(
            name = "Advent", daysMask = 0b0011111, startMinute = 9 * 60, endMinute = 17 * 60,
            validFromDay = LocalDate.of(2026, 12, 1).toEpochDay(), validUntilDay = LocalDate.of(2026, 12, 24).toEpochDay(),
        )
        assertEquals("09:00–17:00  •  Mon Tue  •  Tue 1 Dec – Thu 24 Dec", scheduleLine(ranged, now, zone, true, "Mon Tue"))
    }

    @Test fun spanLabels() {
        assertEquals("until Sun 18:00", spanLabel(null, at(2026, 11, 8, 18), now, zone, true))
        assertEquals("from today 20:00 until Sun 18:00", spanLabel(at(2026, 11, 2, 20), at(2026, 11, 8, 18), now, zone, true))
        assertEquals("ended today 11:00", spanLabel(null, at(2026, 11, 2, 11), now, zone, true))
        assertNull(spanLabel(null, null, now, zone, true))
        assertNull(spanLabel(at(2026, 11, 1, 8), null, now, zone, true)) // already started: nothing to say
    }

    @Test fun typedDates() {
        assertEquals(LocalDate.of(2026, 12, 24), parseDate("24", "12", "2026"))
        assertNull(parseDate("31", "2", "2027"))
        assertNull(parseDate("", "2", "2027"))
        assertNull(parseDate("1", "1", "26")) // two-digit years are too ambiguous
    }

    @Test fun rangeAndSpanValidity() {
        assertTrue(dateRangeValid(null, 5))
        assertTrue(dateRangeValid(5, 5))
        assertFalse(dateRangeValid(6, 5))
        assertTrue(spanValid(1, 2))
        assertFalse(spanValid(2, 2))
    }
}
