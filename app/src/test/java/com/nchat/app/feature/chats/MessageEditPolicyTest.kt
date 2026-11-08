package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEditPolicyTest {
    private val sent = Message(
        id = "server-id", chatId = "peer", authorId = "me", authorName = "Me",
        text = "hello", timestamp = 1000L, isMine = true, clientMessageId = "client-id"
    )

    @Test
    fun onlyConfirmedOwnDirectTextCanBeEdited() {
        assertTrue(canEditMessage(sent, isGroup = false, now = 1000L))
        assertFalse(canEditMessage(sent.copy(id = "client-id"), isGroup = false, now = 1000L))
        assertFalse(canEditMessage(sent, isGroup = true, now = 1000L))
        assertFalse(canEditMessage(sent.copy(isMine = false), isGroup = false, now = 1000L))
        assertFalse(canEditMessage(sent.copy(deleted = true), isGroup = false, now = 1000L))
        assertFalse(canEditMessage(sent.copy(kind = MessageKind.Photo), isGroup = false, now = 1000L))
        assertFalse(canEditMessage(sent, isGroup = false, now = 1000L + 15 * 60 * 1000L))
    }
}
