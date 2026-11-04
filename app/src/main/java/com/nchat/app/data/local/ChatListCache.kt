package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Chat
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
        json.decodeFromString<List<Chat>>(file.readText())
    }.getOrDefault(emptyList())

    fun save(chats: List<Chat>) {
        if (chats.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(chats))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
