package com.nchat.app.feature.chats

import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.network.PeerSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which messages of an encrypted conversation the server could read. Without
 * a mark on those, a message somebody slipped into the conversation would
 * look like any other.
 */
class EncryptionMarksTest {
    private fun text(id: String, at: Long, mine: Boolean = false, sealed: Boolean = false) = Message(
        id = id,
        chatId = "peer",
        authorId = if (mine) "me" else "peer",
        authorName = if (mine) "Me" else "Peer",
        text = "words",
        timestamp = at,
        isMine = mine,
        state = if (mine) DeliveryState.Sent else DeliveryState.Read,
        encrypted = sealed,
    )

    @Test
    fun aChatThatWasNeverEncryptedMarksNothing() {
        val messages = listOf(text("1", 10), text("2", 20, mine = true))
        assertEquals(emptySet<String>(), unsealedMessageIds(messages, encryptedSince = null))
    }

    @Test
    fun whatWasWrittenBeforeEncryptionBeganIsLeftAlone() {
        val messages = listOf(text("old-1", 100), text("old-2", 200, mine = true), text("sealed", 400, sealed = true))
        assertEquals(emptySet<String>(), unsealedMessageIds(messages, encryptedSince = 300))
    }

    @Test
    fun readableWordsAfterEncryptionBeganStandOut() {
        val messages = listOf(
            text("old", 100),
            text("sealed", 400, sealed = true),
            text("slipped in", 500),
            text("sent from the website", 600, mine = true),
            text("sealed again", 700, mine = true, sealed = true),
        )
        assertEquals(setOf("slipped in", "sent from the website"), unsealedMessageIds(messages, encryptedSince = 300))
        assertEquals("the very moment encryption began counts", setOf("at"), unsealedMessageIds(listOf(text("at", 300)), 300))
    }

    @Test
    fun aClockThatWasWrongDoesNotHideWhatFollowsASealedMessage() {
        // This phone's clock was a day ahead when encryption began.
        val tomorrow = 86_400_000L
        assertEquals(
            setOf("slipped in"),
            unsealedMessageIds(listOf(text("sealed", 400, sealed = true), text("slipped in", 500)), encryptedSince = tomorrow),
        )
        // A page that holds no sealed message at all can only be judged by the date.
        assertEquals(setOf("late"), unsealedMessageIds(listOf(text("early", 100), text("late", 500)), encryptedSince = 300))
        assertEquals(emptySet<String>(), unsealedMessageIds(listOf(text("early", 100), text("late", 500)), encryptedSince = tomorrow))
    }

    @Test
    fun aSealedMessageThisPhoneCannotOpenIsStillASealedOne() {
        val unreadable = text("for another phone", 400).copy(unreadable = true)
        assertEquals(
            setOf("after it"),
            unsealedMessageIds(listOf(text("before it", 350), unreadable, text("after it", 450)), encryptedSince = 1_000),
        )
    }

    @Test
    fun onlyWhatWouldHaveBeenSealedAndHasReachedTheServerIsMarked() {
        val messages = listOf(
            // Attachments are not sealed yet; marking each of them would say nothing.
            text("photo", 500).copy(kind = MessageKind.Photo),
            text("video", 500).copy(kind = MessageKind.Video),
            text("voice", 500).copy(kind = MessageKind.Voice),
            text("document", 500).copy(kind = MessageKind.Document),
            // A position is sealed like text.
            text("position", 500).copy(kind = MessageKind.Location),
            text("deleted", 500).copy(deleted = true),
            text("system line", 500).copy(systemEvent = true),
            // On their way, or never sent: the server has not seen them.
            text("sending", 500, mine = true).copy(state = DeliveryState.Sending),
            text("failed", 500, mine = true).copy(failed = true),
            text("sent", 500, mine = true),
            text("read", 500, mine = true).copy(state = DeliveryState.Read),
        )
        assertEquals(setOf("position", "sent", "read"), unsealedMessageIds(messages, encryptedSince = 300))
    }

    // ------------------------------------------------------------- wording

    private val settled = PeerSecurity(key = "their-key", verified = false, changed = false, held = false, since = 0L)

    @Test
    fun theNoticeSaysWhatChangedAndWhatWaits() {
        val changed = settled.copy(changed = true)
        val held = changed.copy(held = true)
        val lost = changed.copy(key = null)

        assertTrue(securityChangeNotice("Sara", changed).startsWith("Sara's security code changed."))
        assertFalse("nothing waits for a contact nobody had verified", securityChangeNotice("Sara", changed).contains("wait"))
        assertTrue(securityChangeNotice("Sara", held).contains("messages to Sara wait"))
        assertTrue(securityChangeNotice("Sara", lost).contains("could not be read"))

        assertEquals("OK", securityAcceptLabel(changed))
        assertEquals("Accept", securityAcceptLabel(held))
        assertNull("no key yet: nothing to accept", securityAcceptLabel(lost))
        assertNull(securityAcceptLabel(held.copy(key = null)))
    }

    @Test
    fun theSheetNeverCallsAChatEncryptedThatIsNot() {
        val never = encryptionSummary("Sara", null)
        assertTrue(never.contains("not end-to-end encrypted yet"))

        val encrypted = encryptionSummary("Sara", settled)
        assertTrue(encrypted.contains("are end-to-end encrypted"))
        assertTrue("and says what is not covered", encrypted.contains("voice messages and files are not encrypted yet"))
        assertEquals("a verified chat is described the same way", encrypted, encryptionSummary("Sara", settled.copy(verified = true)))

        val changed = encryptionSummary("Sara", settled.copy(changed = true))
        val held = encryptionSummary("Sara", settled.copy(changed = true, held = true))
        val lost = encryptionSummary("Sara", settled.copy(changed = true, key = null))
        assertTrue(changed.contains("security code changed"))
        assertTrue(held.contains("Nothing is sent to Sara"))
        assertTrue(lost.contains("could not be read"))
        assertEquals("four states, four texts", 4, setOf(encrypted, changed, held, lost).size)
        assertNotEquals(never, encrypted)
    }
}
