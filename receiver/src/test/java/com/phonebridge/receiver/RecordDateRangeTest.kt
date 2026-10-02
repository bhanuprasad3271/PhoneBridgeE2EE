package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecordDateRangeTest {
    @Test fun weeksStartMondayAcrossMonthBoundary() {
        assertEquals(LocalDate.of(2026, 9, 28), RecordDateRange.start(LocalDate.of(2026, 10, 1), true))
    }
    @Test fun dayUsesLocalMidnightAndExcludesNextDay() {
        val day = LocalDate.of(2026, 10, 1)
        val zone = ZoneId.of("Asia/Kolkata")
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(RecordDateRange.contains(start, day, false, zone))
        assertTrue(RecordDateRange.contains(end - 1, day, false, zone))
        assertFalse(RecordDateRange.contains(end, day, false, zone))
        assertFalse(RecordDateRange.contains(start - 1, day, false, zone))
    }
    @Test fun weekExcludesFollowingMonday() {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = LocalDate.of(2026, 10, 1)
        val end = LocalDate.of(2026, 10, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(RecordDateRange.contains(end - 1, day, true, zone))
        assertFalse(RecordDateRange.contains(end, day, true, zone))
    }
}
