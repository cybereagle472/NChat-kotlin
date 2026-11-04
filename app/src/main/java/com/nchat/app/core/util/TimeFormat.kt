package com.nchat.app.core.util

import java.util.Calendar

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

    private fun dayDistance(timestamp: Long, now: Long): Long {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val reference = Calendar.getInstance().apply { timeInMillis = now }
        val targetDay = target.startOfDay()
        val referenceDay = reference.startOfDay()
        return ((referenceDay - targetDay) / DAY).coerceAtLeast(0L)
    }

    private fun Calendar.startOfDay(): Long {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        return timeInMillis
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
