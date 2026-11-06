package com.nchat.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Archived conversations, kept on the device.
 *
 * WhatsApp's archive is a private filing decision: nothing about it is visible to the other side,
 * and the worker has no column for it (`/messages/chats` has no `archived` flag). So it lives here
 * next to the other per-device state, and archiving is instant and offline by construction.
 *
 * Archiving hides a chat from the main list but keeps every message, the pin, the mute and the
 * drafts exactly as they were — opening the archive and unarchiving puts the row back untouched.
 */
class ArchivedStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "archived.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _ids = MutableStateFlow(load())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun isArchived(chatId: String): Boolean = chatId in _ids.value

    fun archive(chatId: String) {
        if (chatId in _ids.value) return
        _ids.update { it + chatId }
        persist()
    }

    fun unarchive(chatId: String) {
        if (chatId !in _ids.value) return
        _ids.update { it - chatId }
        persist()
    }

    fun toggle(chatId: String) {
        if (chatId in _ids.value) unarchive(chatId) else archive(chatId)
    }

    fun clearAll() {
        _ids.value = emptySet()
        runCatching { file.delete() }
    }

    private fun load(): Set<String> = runCatching {
        if (!file.exists()) return emptySet()
        json.decodeFromString(LIST_SERIALIZER, file.readText()).toSet()
    }.getOrDefault(emptySet())

    private fun persist() = runCatching {
        val ids = _ids.value
        if (ids.isEmpty()) {
            file.delete()
            return@runCatching
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(LIST_SERIALIZER, ids.toList()))
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
