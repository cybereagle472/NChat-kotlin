package com.nchat.app.network

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The last few hundred REST calls, kept in memory.
 *
 * Every repository swallows its own failures (`runCatching { … }.getOrDefault(emptyList())`)
 * so a broken request shows up as an empty screen instead of an error — good for a polished
 * UI, terrible for answering "why is my chat list empty?". This log is the answer: the
 * Settings ▸ Sync check screen prints it next to live counts from each endpoint.
 *
 * Memory only, no network, no disk: it must never be able to fail on its own.
 */
object ApiLog {

    /** One request: what was called, what came back, and how long it took. */
    data class Entry(
        val at: Long,
        val method: String,
        val path: String,
        val status: Int?,
        val millis: Long,
        val error: String?
    ) {
        val isFailure: Boolean get() = status == null || status !in 200..299
    }

    private const val CAPACITY = 200

    private val entries = ArrayDeque<Entry>(CAPACITY)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun record(method: String, path: String, status: Int?, millis: Long, error: String?) {
        synchronized(entries) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(System.currentTimeMillis(), method, path, status, millis, error))
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    /** The most recent request that failed, or `null` when everything answered 2xx. */
    fun lastFailure(): Entry? = synchronized(entries) { entries.lastOrNull { it.isFailure } }

    /** The most recent request that succeeded. */
    fun lastSuccess(): Entry? = synchronized(entries) { entries.lastOrNull { !it.isFailure } }

    fun clear() = synchronized(entries) { entries.clear() }

    /** `10:42:07  GET  /messages/chats  200  180 ms` — or the failure, in one line. */
    fun format(entry: Entry): String {
        val time = clock.format(Date(entry.at))
        val code = entry.status?.toString() ?: "ERR"
        val tail = entry.error?.let { "  ← $it" }.orEmpty()
        return "$time  ${entry.method.padEnd(6)} ${entry.path}  $code  ${entry.millis} ms$tail"
    }

    fun dump(): String = snapshot().joinToString("\n") { format(it) }
}
