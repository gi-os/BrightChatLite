package com.gios.brightchatlite

import com.gios.brightchatlite.model.TimeFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    // Friday 9 October 2026, 18:00.
    private val now = at(2026, 10, 9, 18, 0)
    private val f = TimeFormat(zone, Locale.US) { now }

    @Test fun todayIsClockTime() {
        assertEquals("3:14 PM", f.listTime(at(2026, 10, 9, 15, 14)).replace(' ', ' '))
    }

    @Test fun yesterdayAndWeekday() {
        assertEquals("Yesterday", f.listTime(at(2026, 10, 8, 23, 59)))
        assertEquals("Monday", f.listTime(at(2026, 10, 5, 9, 0)))
        assertEquals("Saturday", f.listTime(at(2026, 10, 3, 9, 0)))
    }

    @Test fun olderIsShortDate() {
        assertEquals("10/2/26", f.listTime(at(2026, 10, 2, 9, 0)))
        assertEquals("", f.listTime(0))
    }

    @Test fun dayHeadersAndDayStart() {
        assertEquals("Today", f.dayHeader(at(2026, 10, 9, 0, 1)))
        assertEquals("Yesterday", f.dayHeader(at(2026, 10, 8, 12, 0)))
        assertEquals(at(2026, 10, 9, 0, 0), f.dayStart(at(2026, 10, 9, 17, 3)))
    }
}
