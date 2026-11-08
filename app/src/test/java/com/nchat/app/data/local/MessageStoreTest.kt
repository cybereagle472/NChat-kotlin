package com.nchat.app.data.local

import com.nchat.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val message = Message(
        id = "server-id", chatId = "peer", authorId = "me", authorName = "Me",
        text = "hello", timestamp = 1000L, isMine = true
    )

    @Test
    fun emptyConversationReplacesPreviousCache() {
        val root = folder.root.resolve("messages")
        val store = MessageStore(root)
        assertTrue(store.save("me", "peer", listOf(message)))
        assertEquals(listOf(message), MessageStore(root).load("me", "peer"))
        assertTrue(store.save("me", "peer", emptyList()))
        assertTrue(MessageStore(root).load("me", "peer").isEmpty())
    }

    @Test
    fun failedReplacePreservesPreviouslyCommittedCache() {
        val root = folder.root.resolve("messages")
        assertTrue(MessageStore(root).save("me", "peer", listOf(message)))
        val failing = MessageStore(root) { _, _ -> false }
        assertFalse(failing.save("me", "peer", emptyList()))
        assertEquals(listOf(message), MessageStore(root).load("me", "peer"))
        assertTrue(root.resolve("me").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun emptyChatAndInvalidIdsDoNotAffectOtherAccounts() {
        val store = MessageStore(folder.root.resolve("messages"))
        assertTrue(store.save("one", "peer", listOf(message)))
        assertTrue(store.save("two", "peer", listOf(message)))
        assertTrue(store.save("one", "peer", emptyList()))
        assertFalse(store.save("", "peer", listOf(message)))
        assertEquals(listOf(message), store.load("two", "peer"))
    }
}
