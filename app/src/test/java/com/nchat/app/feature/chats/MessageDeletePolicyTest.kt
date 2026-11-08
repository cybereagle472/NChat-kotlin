package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.data.model.hasUnconfirmedDelivery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeletePolicyTest {
    private val sent = Message(
        id = "server-id", chatId = "peer", authorId = "me", authorName = "Me",
        text = "hello", timestamp = 1000L, isMine = true, clientMessageId = "client-id"
    )
    private val unconfirmed = sent.copy(id = "client-id")

    @Test
    fun onlyConfirmedEligibleMessagesCanBeDeletedForEveryone() {
        assertTrue(unconfirmed.hasUnconfirmedDelivery())
        assertFalse(sent.hasUnconfirmedDelivery())
        assertFalse(sent.copy(clientMessageId = null).hasUnconfirmedDelivery())
        assertFalse(canDeleteForEveryone(unconfirmed, isGroup = false, now = 1000L))
        assertTrue(canDeleteForEveryone(sent, isGroup = false, now = 1000L))
        assertFalse(canDeleteForEveryone(sent.copy(isMine = false), isGroup = false, now = 1000L))
        assertFalse(canDeleteForEveryone(sent.copy(deleted = true), isGroup = false, now = 1000L))
        val oneHourLater = 1000L + 60 * 60 * 1000L
        assertFalse(canDeleteForEveryone(sent, isGroup = true, now = oneHourLater))
        assertTrue(canDeleteForEveryone(sent, isGroup = false, now = oneHourLater))
    }

    @Test
    fun batchRecallRequiresEverySelectedMessageToBeEligibleAndPresent() {
        assertTrue(canDeleteAllForEveryone(listOf(sent), selectedCount = 1, isGroup = false, now = 1000L))
        assertFalse(canDeleteAllForEveryone(emptyList(), selectedCount = 0, isGroup = false, now = 1000L))
        assertFalse(canDeleteAllForEveryone(listOf(sent), selectedCount = 2, isGroup = false, now = 1000L))
        assertFalse(canDeleteAllForEveryone(listOf(sent, unconfirmed), selectedCount = 2, isGroup = false, now = 1000L))
        assertFalse(canDeleteAllForEveryone(listOf(sent, sent.copy(isMine = false)), selectedCount = 2, isGroup = false, now = 1000L))
    }
}
