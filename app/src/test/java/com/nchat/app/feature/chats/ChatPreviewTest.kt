package com.nchat.app.feature.chats

import com.nchat.app.data.model.Chat
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPreviewTest {
    private val direct = Chat(id = "peer", title = "Ayesha", lastMessage = "Hello")

    @Test fun directMessagesShowTheCorrectSender() {
        assertEquals("Ayesha" to "Hello", chatPreview(direct))
        assertEquals("You" to "Hello", chatPreview(direct.copy(lastMessageMine = true)))
    }

    @Test fun groupMessagesDoNotRepeatTheLegacySenderPrefix() {
        val group = direct.copy(isGroup = true, lastMessageSender = "Ali", lastMessage = "Ali: Hi")
        assertEquals("Ali" to "Hi", chatPreview(group))
        assertEquals("You" to "Hi", chatPreview(group.copy(lastMessageSender = "You", lastMessage = "You: Hi", lastMessageMine = true)))
    }

    @Test fun systemAndEmptyRowsDoNotInventAuthors() {
        assertEquals(null to "Ali joined", chatPreview(direct.copy(isGroup = true, lastMessage = "Ali joined")))
        assertEquals(null to "", chatPreview(direct.copy(lastMessage = "", lastMessageMine = true)))
    }

    @Test fun colonsInMessageBodyArePreserved() {
        val group = direct.copy(isGroup = true, lastMessageSender = "Ali", lastMessage = "Ali: Meet at 5:30")
        assertEquals("Ali" to "Meet at 5:30", chatPreview(group))
    }
}
