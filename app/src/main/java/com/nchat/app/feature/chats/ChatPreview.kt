package com.nchat.app.feature.chats

import com.nchat.app.data.model.Chat

/** Sender and body for a chat row, including legacy group previews with a baked-in prefix. */
internal fun chatPreview(chat: Chat): Pair<String?, String> {
    val original = chat.lastMessage
    if (original.isBlank()) return null to original
    val storedSender = chat.lastMessageSender?.trim()?.takeIf { it.isNotEmpty() }
    val body = storedSender?.let { original.removePrefix("$it: ") } ?: original
    val sender = when {
        chat.lastMessageMine -> "You"
        chat.isGroup -> storedSender // System events do not have a sender.
        else -> chat.title.trim().takeIf { it.isNotEmpty() }
    }
    return sender to body
}
