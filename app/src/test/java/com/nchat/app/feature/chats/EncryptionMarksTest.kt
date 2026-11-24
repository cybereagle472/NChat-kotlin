package com.nchat.app.feature.chats

import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Group
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
    fun sealedHistoryThisPhoneHoldsNoKeysForStillSetsTheMeasure() {
        // After a reinstall: the sealed messages of before are on the server, and nothing has
        // been agreed with the other person from this phone yet.
        val unreadable = text("for the phone before", 400).copy(unreadable = true)
        assertEquals(
            setOf("after it"),
            unsealedMessageIds(listOf(text("before it", 350), unreadable, text("after it", 450)), encryptedSince = null),
        )
    }

    @Test
    fun everyKindIsMarkedSoThatACaptionIsNoWayAroundIt() {
        val messages = listOf(
            text("photo with a caption", 500).copy(kind = MessageKind.Photo),
            text("video", 500).copy(kind = MessageKind.Video),
            text("voice", 500).copy(kind = MessageKind.Voice),
            text("document", 500).copy(kind = MessageKind.Document),
            text("position", 500).copy(kind = MessageKind.Location),
            text("my photo", 500, mine = true).copy(kind = MessageKind.Photo),
        )
        assertEquals(messages.map { it.id }.toSet(), unsealedMessageIds(messages, encryptedSince = 300))
    }

    @Test
    fun whatCarriesNobodysWordsOrNeverReachedTheServerIsNotMarked() {
        val messages = listOf(
            text("deleted", 500).copy(deleted = true),
            text("system line", 500).copy(systemEvent = true),
            // On their way, or never sent: the server has not seen them.
            text("sending", 500, mine = true).copy(state = DeliveryState.Sending),
            text("failed", 500, mine = true).copy(failed = true),
            text("photo still uploading", 500, mine = true).copy(kind = MessageKind.Photo, state = DeliveryState.Sending),
            text("sent", 500, mine = true),
            text("read", 500, mine = true).copy(state = DeliveryState.Read),
            // Somebody else's message has no "sending": whatever state it carries, it arrived.
            text("theirs", 500).copy(state = DeliveryState.Sending),
        )
        assertEquals(setOf("sent", "read", "theirs"), unsealedMessageIds(messages, encryptedSince = 300))
    }

    // ------------------------------------------- the chat list and notifications

    private fun row(lastAt: Long, unsealed: Boolean, group: Boolean = false) = Chat(
        id = if (group) "group" else "peer", title = "Sara", isGroup = group,
        lastMessage = "words", lastMessageAt = lastAt, lastMessageUnsealed = unsealed,
    )

    @Test
    fun theListSaysWhichChatsAreEncryptedAndWhichNeedALook() {
        val changed = settled.copy(changed = true)
        val held = changed.copy(held = true)
        val lost = changed.copy(key = null)

        assertEquals(ChatLock.None, chatListMark(row(10, false), security = null, groupEncryptedSince = null).lock)
        assertEquals(ChatLock.Encrypted, chatListMark(row(10, false), settled, null).lock)
        assertEquals(ChatLock.Encrypted, chatListMark(row(10, false), settled.copy(verified = true), null).lock)
        assertEquals(ChatLock.Changed, chatListMark(row(10, false), changed, null).lock)
        assertEquals(ChatLock.Held, chatListMark(row(10, false), held, null).lock)
        assertEquals("keys that could not be read need a look too", ChatLock.Changed, chatListMark(row(10, false), lost, null).lock)

        // A group has no code of its own: it is encrypted or it is not.
        assertEquals(ChatLock.None, chatListMark(row(10, false, group = true), null, null).lock)
        assertEquals(ChatLock.Encrypted, chatListMark(row(10, false, group = true), null, 5L).lock)
        // What is known about a person says nothing about a group, and the other way round.
        assertEquals(ChatLock.None, chatListMark(row(10, false, group = true), held, null).lock)
        assertEquals(ChatLock.None, chatListMark(row(10, false), null, 5L).lock)
    }

    @Test
    fun aChangedCodeTakesThePlaceOfThePreviewUntilItWasLookedAt() {
        val changed = settled.copy(changed = true)
        assertNull(chatListMark(row(10, false), null, null).notice)
        assertNull(chatListMark(row(10, false), settled, null).notice)
        assertNull(chatListMark(row(10, false, group = true), null, 5L).notice)
        assertEquals("Security code changed", chatListMark(row(10, false), changed, null).notice)
        val waiting = chatListMark(row(10, false), changed.copy(held = true), null).notice!!
        assertTrue(waiting.startsWith("Security code changed"))
        assertTrue("and that something waits for it", waiting.contains("waiting"))
        assertTrue(chatListMark(row(10, false), changed.copy(key = null), null).notice!!.contains("could not be read"))
    }

    @Test
    fun thePreviewOfWordsTheServerCouldReadSaysSoInAnEncryptedChat() {
        val since = settled.copy(since = 300)
        // The lock on a row must not vouch for a preview that was never sealed.
        assertTrue(chatListMark(row(500, unsealed = true), since, null).unsealedPreview)
        assertTrue("the very moment encryption began counts", chatListMark(row(300, unsealed = true), since, null).unsealedPreview)
        assertFalse("sealed", chatListMark(row(500, unsealed = false), since, null).unsealedPreview)
        assertFalse("from before encryption began", chatListMark(row(200, unsealed = true), since, null).unsealedPreview)
        assertFalse("a chat that never was encrypted marks nothing", chatListMark(row(500, unsealed = true), null, null).unsealedPreview)
        // Groups go by when the group was first encrypted on this phone.
        assertTrue(chatListMark(row(500, unsealed = true, group = true), null, 300L).unsealedPreview)
        assertFalse(chatListMark(row(200, unsealed = true, group = true), null, 300L).unsealedPreview)
        assertFalse(chatListMark(row(500, unsealed = true, group = true), since, null).unsealedPreview)
        // While the notice is there it has the line; the mark returns with the preview.
        val both = chatListMark(row(500, unsealed = true), since.copy(changed = true), null)
        assertEquals("Security code changed", both.notice)
        assertTrue(both.unsealedPreview)
    }

    @Test
    fun theGroupsTabSaysTheSameAboutAGroup() {
        fun group(lastAt: Long, unsealed: Boolean) = Group(
            id = "g", name = "Family", description = "", memberCount = 3,
            lastActivity = "Sara: words", lastActivityAt = lastAt, lastActivityUnsealed = unsealed,
        )
        assertEquals(ChatListMark(ChatLock.None, null, false), groupListMark(group(500, unsealed = true), encryptedSince = null))
        assertEquals(ChatListMark(ChatLock.Encrypted, null, false), groupListMark(group(500, unsealed = false), 300L))
        assertEquals(ChatListMark(ChatLock.Encrypted, null, true), groupListMark(group(500, unsealed = true), 300L))
        assertEquals("from before the group was encrypted", ChatListMark(ChatLock.Encrypted, null, false), groupListMark(group(200, unsealed = true), 300L))
        // The same answer as for the row the group has in the chat list.
        val row = row(500, unsealed = true, group = true)
        assertEquals(chatListMark(row, null, 300L), groupListMark(group(500, unsealed = true), 300L))
    }

    @Test
    fun whatALineUnderAChatsNameIsToldAboutItsMessage() {
        // The row keeps one fact about its newest message: were these somebody's words, readable
        // by the server? Whether that deserves a mark depends on the chat, which the list knows.
        assertTrue(text("theirs", 500).reachedServerReadable)
        assertTrue(text("mine, sent", 500, mine = true).reachedServerReadable)
        assertFalse(text("sealed", 500, sealed = true).reachedServerReadable)
        assertFalse("sealed, though this phone cannot open it", text("closed", 500).copy(unreadable = true).reachedServerReadable)
        assertFalse(text("deleted", 500).copy(deleted = true).reachedServerReadable)
        assertFalse(text("system line", 500).copy(systemEvent = true).reachedServerReadable)
        assertFalse("still on its way", text("sending", 500, mine = true).copy(state = DeliveryState.Sending).reachedServerReadable)
        assertFalse("never sent", text("failed", 500, mine = true).copy(failed = true).reachedServerReadable)
        assertTrue("somebody else's message has no 'sending'", text("theirs", 500).copy(state = DeliveryState.Sending).reachedServerReadable)
        // It is the same measure the conversation uses for its own marks.
        for (message in listOf(text("a", 500), text("b", 500, sealed = true), text("c", 500).copy(deleted = true), text("d", 500, mine = true).copy(failed = true))) {
            assertEquals(message.id, message.reachedServerReadable, message.id in unsealedMessageIds(listOf(message), encryptedSince = 300))
        }
    }

    @Test
    fun aNotificationOfWordsTheServerCouldReadSaysSoInAnEncryptedChat() {
        // A notification is where a message slipped into an encrypted chat would be read first.
        assertEquals("Not encrypted · send me the code", notificationBody("send me the code", text("1", 500), encryptedSince = 300))
        assertEquals("send me the code", notificationBody("send me the code", text("1", 500, sealed = true), encryptedSince = 300))
        assertEquals("a chat that never was encrypted", "hello", notificationBody("hello", text("1", 500), encryptedSince = null))
        assertEquals("from before encryption began", "hello", notificationBody("hello", text("1", 200), encryptedSince = 300))
        assertEquals("📷 Photo is marked like any words", "Not encrypted · 📷 Photo", notificationBody("📷 Photo", text("1", 500).copy(kind = MessageKind.Photo), 300))
        assertEquals("nobody's words", "Sara joined", notificationBody("Sara joined", text("1", 500).copy(systemEvent = true), 300))
    }

    @Test
    fun aChangedCodeIsAnnouncedOnceAndSaysWhatWaits() {
        val changed = settled.copy(changed = true)
        val held = changed.copy(held = true)
        // What the notification says; null when there is nothing to announce.
        assertNull(securityNotification("Sara", null))
        assertNull(securityNotification("Sara", settled))
        assertEquals(securityChangeNotice("Sara", changed), securityNotification("Sara", changed))
        assertEquals(securityChangeNotice("Sara", held), securityNotification("Sara", held))
        assertNull("keys that could not be read are this phone's trouble, not news about Sara", securityNotification("Sara", changed.copy(key = null)))

        // Announced when it changes, not again for the same key, and again for the next one.
        assertTrue(announcesSecurityChange(before = settled, now = changed.copy(key = "new-key")))
        assertTrue("first seen already changed", announcesSecurityChange(before = null, now = changed))
        assertFalse(announcesSecurityChange(before = changed, now = changed))
        assertFalse("looked at", announcesSecurityChange(before = changed, now = settled))
        assertFalse(announcesSecurityChange(before = settled, now = settled.copy(verified = true)))
        assertTrue("another change while the first was not looked at", announcesSecurityChange(before = changed, now = changed.copy(key = "third-key")))
        assertTrue("now something waits", announcesSecurityChange(before = changed, now = held))
        assertFalse(announcesSecurityChange(before = settled, now = null))
        assertFalse(announcesSecurityChange(before = null, now = settled))
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
    fun theSheetOfAGroupSaysWhetherItIsEncryptedAndWhoReads() {
        val not = groupEncryptionSummary(encrypted = false)
        val yes = groupEncryptionSummary(encrypted = true)
        assertTrue(not.contains("not end-to-end encrypted yet"))
        assertTrue("and what it waits for", not.contains("every member"))
        assertTrue(yes.contains("are end-to-end encrypted"))
        assertFalse(yes.contains("not end-to-end encrypted"))
        assertTrue("who reads what after joining and leaving", yes.contains("joins") && yes.contains("leaves"))
        assertTrue("where a code can be compared", yes.contains("security code"))
        assertNotEquals(not, yes)
    }

    @Test
    fun theSheetNeverCallsAChatEncryptedThatIsNot() {
        val never = encryptionSummary("Sara", null)
        assertTrue(never.contains("not end-to-end encrypted yet"))

        val encrypted = encryptionSummary("Sara", settled)
        assertTrue(encrypted.contains("are end-to-end encrypted"))
        assertTrue("and says what is covered", encrypted.contains("photos, videos, voice messages and files"))
        assertFalse("nothing in a chat with one person is left out any more", encrypted.contains("not encrypted"))
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
