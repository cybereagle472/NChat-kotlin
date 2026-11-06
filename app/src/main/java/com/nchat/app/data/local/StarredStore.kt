package com.nchat.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One starred message.
 *
 * The text is copied in on purpose: **Starred messages** has to keep working after the original
 * has scrolled out of the loaded page, and after the sender deletes it (WhatsApp keeps the star
 * with a "message deleted" body). A server reference would go blank in both cases.
 */
@Serializable
data class StarredMessage(
    val chatId: String,
    val messageId: String,
    val authorName: String = "",
    val preview: String = "",
    /** Epoch millis of the original message, used for ordering the list. */
    val at: Long = 0L
)

/**
 * The device's starred messages (`StarredMessage`), newest first.
 *
 * Like drafts, starring is a private, per-device flag: it is not part of the wire format the
 * worker speaks, and no other member of a group should ever learn that you starred something.
 * Storing it locally means starring works offline and never fails.
 */
class StarredStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "starred.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<StarredMessage>> = _items.asStateFlow()

    fun isStarred(chatId: String, messageId: String): Boolean =
        _items.value.any { it.chatId == chatId && it.messageId == messageId }

    fun forChat(chatId: String): List<StarredMessage> =
        _items.value.filter { it.chatId == chatId }.sortedByDescending { it.at }

    /** Stars [item], or un-stars the same message if it already is — one tap, both directions. */
    fun toggle(item: StarredMessage) {
        val starred = isStarred(item.chatId, item.messageId)
        _items.update { current ->
            if (starred) {
                current.filterNot { it.chatId == item.chatId && it.messageId == item.messageId }
            } else {
                (current + item).sortedByDescending { it.at }
            }
        }
        persist()
    }

    fun unstar(chatId: String, messageId: String) {
        _items.update { current ->
            current.filterNot { it.chatId == chatId && it.messageId == messageId }
        }
        persist()
    }

    fun clearAll() {
        _items.value = emptyList()
        runCatching { file.delete() }
    }

    private fun load(): List<StarredMessage> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(LIST_SERIALIZER, file.readText())
    }.getOrDefault(emptyList())

    private fun persist() = runCatching {
        val starred = _items.value
        if (starred.isEmpty()) {
            file.delete()
            return@runCatching
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(LIST_SERIALIZER, starred))
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(StarredMessage.serializer())
    }
}
