package com.nchat.app.data.local

import com.nchat.app.data.model.OutboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun deletedEntryCannotBeResurrectedByAnUploadCompletion() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val original = OutboxEntry(clientId = "one", chatId = "peer", ownerId = "user", text = "hello")
        store.add(original)
        assertTrue(store.replaceIfPresent(original.copy(mediaUrl = "https://example.test/file")))
        assertEquals("https://example.test/file", store.find("one")?.mediaUrl)
        store.remove("one")
        assertFalse(store.replaceIfPresent(original.copy(mediaUrl = "https://example.test/late")))
        assertNull(store.find("one"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun updatingOneEntryPreservesOthers() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val first = OutboxEntry(clientId = "one", chatId = "peer", text = "first")
        val second = OutboxEntry(clientId = "two", chatId = "peer", text = "second")
        store.add(first)
        store.add(second)
        assertTrue(store.replaceIfPresent(first.copy(mediaUrl = "https://example.test/file")))
        assertEquals(second, store.find("two"))
    }
}
