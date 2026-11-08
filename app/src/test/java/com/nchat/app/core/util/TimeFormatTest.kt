package com.nchat.app.core.util

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun calendarDayLabelsSurviveDaylightSavingTransitions() {
        val previous = TimeZone.getDefault()
        val eastern = TimeZone.getTimeZone("America/New_York")
        try {
            TimeZone.setDefault(eastern)
            fun noon(year: Int, month: Int, day: Int): Long =
                GregorianCalendar(eastern).apply {
                    clear()
                    set(year, month, day, 12, 0, 0)
                }.timeInMillis

            // March 9 has only 23 hours; it is still the day before March 10.
            val spring = noon(2025, Calendar.MARCH, 9)
            val springNext = noon(2025, Calendar.MARCH, 10)
            assertEquals("Yesterday", TimeFormat.daySeparator(spring, springNext))
            assertEquals("Yesterday", TimeFormat.listTimestamp(spring, springNext))
            assertEquals("Yesterday", TimeFormat.historyTimestamp(spring, springNext))

            // November 2 has 25 hours.
            val fall = noon(2025, Calendar.NOVEMBER, 2)
            val fallNext = noon(2025, Calendar.NOVEMBER, 3)
            assertEquals("Yesterday", TimeFormat.daySeparator(fall, fallNext))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
