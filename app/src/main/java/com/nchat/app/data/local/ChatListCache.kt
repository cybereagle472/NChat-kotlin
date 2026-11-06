package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.withoutKindMarkers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last known chat list, kept on disk so the app opens with content instead of a spinner.
 *
 * WhatsApp, Telegram and Messenger all paint from a local database first and reconcile with
 * the server afterwards; that is most of why they feel instant. This is the small version of
 * that idea: one JSON file written after every successful refresh and read during start-up,
 * before the network answers.
 *
 * It is a *cache*, never a source of truth: the server's reply replaces it wholesale, and a
 * corrupt or outdated file is simply ignored.
 */
class ChatListCache(context: Context) {

    private val file = File(context.applicationContext.filesDir, "cache/chats.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<Chat> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(LIST_SERIALIZER, file.readText())
            /*
             * Old previews are on disk, written by a build that prefixed the row text with the
             * message type ("🎤 Voice message"). The list draws its own icon, so a cached row
             * would show both until the next successful refresh — strip it on the way in.
             */
            .map { chat -> chat.copy(lastMessage = chat.lastMessage.withoutKindMarkers()) }
    }.getOrDefault(emptyList())

    fun save(chats: List<Chat>) {
        if (chats.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LIST_SERIALIZER, chats))
        }
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Chat.serializer())
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
