package com.nchat.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One pinned message per conversation.
 *
 * The server has no concept of a pinned message (`messages` has `reply_to` and nothing else), and
 * a pin is a *personal* bookmark anyway — in a group, pinning for yourself must not pin for
 * everybody. One id per chat is enough: WhatsApp shows a single banner and replaces it when you
 * pin another message, which is exactly what a `Map<chatId, messageId>` says.
 *
 * The id is resolved against the messages already loaded, so a pin whose message has been deleted
 * or paged out simply shows nothing rather than a blank banner.
 */
class PinnedMessageStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "pinned.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<Map<String, String>> = _pins.asStateFlow()

    fun pinnedId(chatId: String): String? = _pins.value[chatId]

    fun pin(chatId: String, messageId: String) {
        _pins.update { it + (chatId to messageId) }
        persist()
    }

    fun unpin(chatId: String) {
        if (chatId !in _pins.value) return
        _pins.update { it - chatId }
        persist()
    }

    /** Returns true when the message is now pinned, false when it was unpinned. */
    fun toggle(chatId: String, messageId: String): Boolean {
        val nowPinned = _pins.value[chatId] != messageId
        if (nowPinned) pin(chatId, messageId) else unpin(chatId)
        return nowPinned
    }

    fun clearAll() {
        _pins.value = emptyMap()
        runCatching { file.delete() }
    }

    private fun load(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        json.decodeFromString(MAP_SERIALIZER, file.readText())
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching {
        val pins = _pins.value
        if (pins.isEmpty()) {
            file.delete()
            return@runCatching
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(MAP_SERIALIZER, pins))
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
