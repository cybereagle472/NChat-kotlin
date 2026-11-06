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
 * Half-written messages, per conversation, on the device.
 *
 * Typing a paragraph, leaving the chat and coming back must not lose it — WhatsApp keeps the
 * text in the composer and shows a red **Draft** on the chat row until it is sent or erased.
 * The server has nowhere to store this (it is not a message and must never be delivered), and
 * it is per-device by definition, so it lives in a small JSON file next to the other caches.
 *
 * Writes are debounced by the view model, not here: this class is the storage, and the caller
 * decides how often it is asked to persist.
 */
class DraftStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "drafts.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _drafts = MutableStateFlow(load())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun get(chatId: String): String = _drafts.value[chatId].orEmpty()

    /** Stores [text] for [chatId]; blank text removes the entry instead of keeping an empty one. */
    fun set(chatId: String, text: String) {
        val current = _drafts.value[chatId].orEmpty()
        if (current == text) return
        _drafts.update { drafts ->
            if (text.isBlank()) drafts - chatId else drafts + (chatId to text)
        }
        persist()
    }

    fun clear(chatId: String) = set(chatId, "")

    fun clearAll() {
        _drafts.value = emptyMap()
        runCatching { file.delete() }
    }

    private fun load(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        json.decodeFromString(MAP_SERIALIZER, file.readText())
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching {
        val drafts = _drafts.value
        if (drafts.isEmpty()) {
            file.delete()
            return@runCatching
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(MAP_SERIALIZER, drafts))
    }

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
