package com.nchat.app.core.util

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * All timestamps in the app are epoch milliseconds. Formatting avoids java.time so the
 * app runs on API 24 without core library desugaring.
 */
object TimeFormat {

    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /** `14:07` style clock label. */
    fun clock(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "${hour.pad()}:${minute.pad()}"
    }

    /** Relative label used in conversation rows: `now`, `14:07`, `Yesterday`, `Mon`, `12/03/2026`. */
    fun listTimestamp(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val daysApart = dayDistance(timestamp, now)
        return when {
            now - timestamp < MINUTE -> "now"
            daysApart == 0L -> clock(timestamp)
            daysApart == 1L -> "Yesterday"
            daysApart < 7L -> weekday(timestamp)
            else -> date(timestamp)
        }
    }

    /** Last-seen label only for a timestamp the server actually disclosed. */
    fun lastSeen(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val day = dayDistance(timestamp, now)
        val whenSeen = when (day) {
            0L -> "today"
            1L -> "yesterday"
            else -> "on ${date(timestamp)}"
        }
        return "last seen $whenSeen at ${clock(timestamp)}"
    }

    /** Separator label inside a conversation: `Today`, `Yesterday`, `Monday`, `12/03/2026`. */
    fun daySeparator(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val daysApart = dayDistance(timestamp, now)
        return when {
            daysApart == 0L -> "Today"
            daysApart == 1L -> "Yesterday"
            daysApart < 7L -> weekday(timestamp)
            else -> date(timestamp)
        }
    }

    /** Call / channel metadata label: `just now`, `4m`, `3h`, `6d`, `12/03/2026`. */
    fun shortAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val elapsed = (now - timestamp).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE -> "just now"
            elapsed < HOUR -> "${elapsed / MINUTE}m"
            elapsed < DAY -> "${elapsed / HOUR}h"
            elapsed < 7 * DAY -> "${elapsed / DAY}d"
            else -> date(timestamp)
        }
    }

    /** `12/03/2026` style date, used by "Last seen …" labels. */
    fun dateLabel(timestamp: Long): String = date(timestamp)

    /** `Just now` / `8m ago` / `3h ago` / `2d ago` labels for friend requests. */
    fun agoLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val elapsed = (now - timestamp).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE -> "Just now"
            elapsed < HOUR -> "${elapsed / MINUTE}m ago"
            elapsed < DAY -> "${elapsed / HOUR}h ago"
            elapsed < 7 * DAY -> "${elapsed / DAY}d ago"
            else -> date(timestamp)
        }
    }

    /** History row label: `5:04 PM` today, `Yesterday`, `Mon`, `Nov 4`. */
    fun historyTimestamp(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val daysApart = dayDistance(timestamp, now)
        return when {
            daysApart == 0L -> clock12(timestamp)
            daysApart == 1L -> "Yesterday"
            daysApart < 7L -> weekday(timestamp)
            else -> "${monthShort(timestamp)} ${dayOfMonth(timestamp)}"
        }
    }

    private fun clock12(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = calendar.get(Calendar.HOUR)
        val suffix = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val display = if (hour == 0) 12 else hour
        return "$display:${calendar.get(Calendar.MINUTE).pad()} $suffix"
    }

    private fun monthShort(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when (calendar.get(Calendar.MONTH)) {
            Calendar.JANUARY -> "Jan"
            Calendar.FEBRUARY -> "Feb"
            Calendar.MARCH -> "Mar"
            Calendar.APRIL -> "Apr"
            Calendar.MAY -> "May"
            Calendar.JUNE -> "Jun"
            Calendar.JULY -> "Jul"
            Calendar.AUGUST -> "Aug"
            Calendar.SEPTEMBER -> "Sep"
            Calendar.OCTOBER -> "Oct"
            Calendar.NOVEMBER -> "Nov"
            else -> "Dec"
        }
    }

    private fun dayOfMonth(timestamp: Long): Int =
        Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_MONTH)

    private fun dayDistance(timestamp: Long, now: Long): Long {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val reference = Calendar.getInstance().apply { timeInMillis = now }
        // Compare local calendar dates rather than elapsed hours between local midnights:
        // daylight-saving transitions can make a local day 23 or 25 hours long.
        return ((reference.utcDateMillis() - target.utcDateMillis()) / DAY).coerceAtLeast(0L)
    }

    private fun Calendar.utcDateMillis(): Long = GregorianCalendar(TimeZone.getTimeZone("UTC")).run {
        clear()
        set(this@utcDateMillis.get(Calendar.YEAR), this@utcDateMillis.get(Calendar.MONTH),
            this@utcDateMillis.get(Calendar.DAY_OF_MONTH))
        timeInMillis
    }

    private fun weekday(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sun"
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            else -> "Sat"
        }
    }

    private fun date(timestamp: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val day = calendar.get(Calendar.DAY_OF_MONTH).pad()
        val month = (calendar.get(Calendar.MONTH) + 1).pad()
        return "$day/$month/${calendar.get(Calendar.YEAR)}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
    private fun Long.pad(): String = toString().padStart(2, '0')
}
