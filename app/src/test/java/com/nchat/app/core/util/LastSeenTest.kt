package com.nchat.app.core.util

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class LastSeenTest {
    @Test fun formatsActualLocalDateAndTime() {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 8, 14, 30)
        }
        val now = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 9)
        calendar.set(Calendar.MINUTE, 5)
        assertEquals("last seen today at 09:05", TimeFormat.lastSeen(calendar.timeInMillis, now))
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        assertEquals("last seen yesterday at 09:05", TimeFormat.lastSeen(calendar.timeInMillis, now))
        calendar.add(Calendar.DAY_OF_MONTH, -2)
        assertEquals("last seen on 05/11/2026 at 09:05", TimeFormat.lastSeen(calendar.timeInMillis, now))
    }
}
