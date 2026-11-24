package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.withoutKindMarkers
import com.nchat.app.network.LocalSeal
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
 *
 * Every row carries the preview of its newest message, so the file is sealed for the account
 * that is signed in (see [LocalFiles]); with nothing to seal with it is neither read nor written.
 */
class ChatListCache internal constructor(private val file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "cache/chats.json"), seal)

    private val files = LocalFiles(seal)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<Chat> = runCatching {
        val stored = files.read(file, PLACE) as? Stored.Readable ?: return emptyList()
        val chats = json.decodeFromString(LIST_SERIALIZER, stored.text)
            /*
             * Old previews are on disk, written by a build that prefixed the row text with the
             * message type ("🎤 Voice message"). The list draws its own icon, so a cached row
             * would show both until the next successful refresh — strip it on the way in.
             */
            .map { chat -> chat.copy(lastMessage = chat.lastMessage.withoutKindMarkers()) }
        // Written by a build before sealing: read once, sealed from then on.
        if (stored.plain) files.write(file, PLACE, stored.text)
        chats
    }.getOrDefault(emptyList())

    fun save(chats: List<Chat>) {
        if (chats.isEmpty()) return
        runCatching { files.write(file, PLACE, json.encodeToString(LIST_SERIALIZER, chats)) }
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Chat.serializer())
        const val PLACE = "chats"
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
