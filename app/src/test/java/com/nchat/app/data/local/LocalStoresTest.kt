package com.nchat.app.data.local

import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.GroupMember
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.OutboxEntry
import com.nchat.app.network.LocalSeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the app keeps on the phone beside its keys is sealed for the account
 * that is signed in: conversations, the chat and group lists with their
 * previews, messages waiting to be sent (with the keys of their files),
 * drafts and starred messages.
 *
 * The rules, for every one of these stores:
 *  - what is written cannot be read off the disk;
 *  - with nothing to seal with, nothing is written — never an unsealed copy;
 *  - a file from a build before sealing is still read, and is sealed from
 *    then on;
 *  - a sealed file that cannot be opened right now is left where it is;
 *  - one that will never open here (another account's, or put in another
 *    file's place) counts as nothing.
 */
class LocalStoresTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(32) { (it + 3).toByte() }
    private val seal = LocalSeal(key)
    private val mine: () -> LocalSeal? = { seal }
    private val none: () -> LocalSeal? = { null }
    private val another: () -> LocalSeal? = { LocalSeal(ByteArray(32) { 7 }) }

    private val secret = "the code is 4471"
    private val message = Message(id = "m1", chatId = "bob", authorId = "bob", authorName = "Bob", text = secret, timestamp = 1000L, isMine = false)
    private val entry = OutboxEntry(clientId = "c1", chatId = "bob", ownerId = "alice", text = secret, mediaUrl = "https://x.test/media/f#nchat-sealed:v1:KEYKEYKEY:12:jpg")
    private val chat = Chat(id = "bob", title = "Bob", lastMessage = secret, lastMessageAt = 5L)
    private val group = Group(
        id = "g1", name = "Family", description = "", memberCount = 2, lastActivity = secret, lastActivityAt = 5L,
        members = listOf(GroupMember(userId = "bob", name = "Bob"))
    )
    private val friend = Friend(id = "bob", name = "Bob Example", handle = "@bobby", isOnline = false, relation = FriendRelation.Friend)

    private fun readable(file: File): String = String(file.readBytes(), Charsets.ISO_8859_1)

    private fun everyFile(): List<File> = folder.root.walkTopDown().filter { it.isFile }.toList()

    private fun assertNothingReadable(vararg words: String) {
        val files = everyFile()
        assertTrue("something was written", files.isNotEmpty())
        for (file in files) for (word in words + secret) assertFalse("${file.name} contains '$word'", readable(file).contains(word))
    }

    // ------------------------------------------------------------ conversations

    @Test
    fun aConversationIsSealedOnDiskAndReadBack() {
        val root = folder.root.resolve("messages")
        assertTrue(MessageStore(root, mine).save("alice", "bob", listOf(message)))
        assertNothingReadable("Bob", "m1")
        assertEquals(listOf(message), MessageStore(root, mine).load("alice", "bob"))
        // Not with another account's key, and not in another conversation's place.
        assertEquals(emptyList<Message>(), MessageStore(root, another).load("alice", "bob"))
        val file = everyFile().single()
        file.copyTo(File(file.parentFile, "carol.json"))
        assertEquals(emptyList<Message>(), MessageStore(root, mine).load("alice", "carol"))
    }

    @Test
    fun withoutAKeyAConversationIsNeitherWrittenNorLost() {
        val root = folder.root.resolve("messages")
        assertFalse("nothing to seal with: nothing is written", MessageStore(root, none).save("alice", "bob", listOf(message)))
        assertEquals(emptyList<File>(), everyFile())

        assertTrue(MessageStore(root, mine).save("alice", "bob", listOf(message)))
        val before = everyFile().single().readBytes().toList()
        // The key cannot be had for the moment: the conversation reads as empty, and stays on disk.
        assertEquals(emptyList<Message>(), MessageStore(root, none).load("alice", "bob"))
        assertFalse(MessageStore(root, none).save("alice", "bob", emptyList()))
        assertEquals(before, everyFile().single().readBytes().toList())
        assertEquals(listOf(message), MessageStore(root, mine).load("alice", "bob"))
    }

    @Test
    fun aConversationFromBeforeSealingIsReadAndSealedFromThenOn() {
        val root = folder.root.resolve("messages")
        // What 1.0.77 left on the phone.
        val old = File(root, "alice/bob.json").also { it.parentFile.mkdirs() }
        old.writeText("""[{"id":"m1","chatId":"bob","authorId":"bob","authorName":"Bob","text":"$secret","timestamp":1000,"isMine":false}]""")
        val untouched = File(root, "alice/carol.json")
        untouched.writeText("""[{"id":"m2","chatId":"carol","authorId":"carol","authorName":"Carol","text":"$secret","timestamp":2000,"isMine":false}]""")

        val store = MessageStore(root, mine)
        assertEquals(listOf(message), store.load("alice", "bob"))
        assertFalse("read once, it is sealed", readable(old).contains(secret))
        assertEquals(listOf(message), MessageStore(root, mine).load("alice", "bob"))

        // The ones nobody opened are sealed in one pass, and read as before afterwards.
        assertTrue(readable(untouched).contains(secret))
        assertEquals(1, store.sealPlainFiles())
        assertNothingReadable("Carol")
        assertEquals("m2", MessageStore(root, mine).load("alice", "carol").single().id)
        assertEquals("a second pass finds nothing to do", 0, store.sealPlainFiles())

        // Without a key the old file is still read — and stays as it is, rather than be lost.
        old.writeText("""[{"id":"m1","chatId":"bob","authorId":"bob","authorName":"Bob","text":"$secret","timestamp":1000,"isMine":false}]""")
        assertEquals(listOf(message), MessageStore(root, none).load("alice", "bob"))
        assertTrue(readable(old).contains(secret))
        assertEquals(0, MessageStore(root, none).sealPlainFiles())
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun messagesWaitingToBeSentAreSealedWithTheKeysOfTheirFiles() {
        val file = folder.root.resolve("outbox.json")
        assertTrue(OutboxStore(file, mine).add(entry))
        assertNothingReadable("KEYKEYKEY", "nchat-sealed", "alice")
        assertEquals(listOf(entry), OutboxStore(file, mine).all())
        assertEquals(entry, OutboxStore(file, mine).find("c1"))
        assertTrue(OutboxStore(file, mine).remove("c1"))
        assertEquals(emptyList<OutboxEntry>(), OutboxStore(file, mine).all())
    }

    @Test
    fun withoutAKeyNothingIsQueuedAndWhatIsQueuedStays() {
        val file = folder.root.resolve("outbox.json")
        // The caller is told, so the message is shown as not sent instead of as safely queued.
        assertFalse(OutboxStore(file, none).add(entry))
        assertFalse(file.exists())

        assertTrue(OutboxStore(file, mine).add(entry))
        val before = file.readBytes().toList()
        val locked = OutboxStore(file, none)
        assertEquals("unknown is not empty", emptyList<OutboxEntry>(), locked.all())
        assertNull(locked.find("c1"))
        assertFalse(locked.add(entry.copy(clientId = "c2")))
        assertFalse(locked.remove("c1"))
        assertFalse(locked.clearExcept("alice"))
        assertNull(locked.removeConversation("bob", "alice"))
        assertEquals(0, locked.bumpAttempts("c1"))
        assertEquals("and nothing was overwritten", before, file.readBytes().toList())
        assertEquals(listOf(entry), OutboxStore(file, mine).all())
    }

    @Test
    fun anOutboxOfAnotherAccountCountsAsEmptyAndIsReplaced() {
        val file = folder.root.resolve("outbox.json")
        assertTrue(OutboxStore(file, another).add(entry.copy(clientId = "theirs", ownerId = "zoe")))
        // Somebody else signed in on this phone: what the last account queued is not theirs to
        // send, cannot be opened by them — and must not stop them queuing their own.
        val store = OutboxStore(file, mine)
        assertEquals(emptyList<OutboxEntry>(), store.all())
        assertTrue(store.clearExcept("alice"))
        assertTrue(store.add(entry))
        assertEquals(listOf(entry), OutboxStore(file, mine).all())
        assertNothingReadable("theirs", "zoe")
    }

    @Test
    fun anOutboxFromBeforeSealingIsDeliveredAndSealedFromThenOn() {
        val file = folder.root.resolve("outbox.json")
        file.writeText("""[{"clientId":"c0","chatId":"bob","ownerId":"alice","text":"$secret","createdAt":1}]""")
        val store = OutboxStore(file, mine)
        assertEquals(listOf("c0"), store.all().map { it.clientId })
        assertFalse("read once, it is sealed", readable(file).contains(secret))
        assertEquals(secret, OutboxStore(file, mine).find("c0")?.text)

        // Without a key it is still read; it cannot be changed, and is not lost.
        file.writeText("""[{"clientId":"c0","chatId":"bob","ownerId":"alice","text":"$secret","createdAt":1}]""")
        assertEquals(listOf("c0"), OutboxStore(file, none).all().map { it.clientId })
        assertFalse(OutboxStore(file, none).remove("c0"))
        assertTrue(readable(file).contains(secret))
    }

    // ---------------------------------------------------- lists with previews

    @Test
    fun theChatListWithItsPreviewsIsSealed() {
        val file = folder.root.resolve("cache/chats.json")
        ChatListCache(file, mine).save(listOf(chat))
        assertNothingReadable("Bob")
        assertEquals(listOf(chat), ChatListCache(file, mine).load())
        assertEquals(emptyList<Chat>(), ChatListCache(file, another).load())
        assertEquals(emptyList<Chat>(), ChatListCache(file, none).load())
        val before = file.readBytes().toList()
        ChatListCache(file, none).save(listOf(chat.copy(lastMessage = "written without a key")))
        assertEquals(before, file.readBytes().toList())

        file.writeText("""[{"id":"bob","title":"Bob","lastMessage":"$secret","lastMessageAt":5}]""")
        assertEquals(listOf(chat), ChatListCache(file, mine).load())
        assertFalse("read once, it is sealed", readable(file).contains(secret))
        assertEquals(listOf(chat), ChatListCache(file, mine).load())
    }

    @Test
    fun theGroupListWithItsPreviewsIsSealed() {
        val file = folder.root.resolve("cache/groups.json")
        GroupListCache(file, mine).save(listOf(group))
        assertNothingReadable("Family")
        assertEquals(listOf(group), GroupListCache(file, mine).load())
        assertEquals(emptyList<Group>(), GroupListCache(file, another).load())
        assertEquals(emptyList<Group>(), GroupListCache(file, none).load())
        val before = file.readBytes().toList()
        GroupListCache(file, none).save(listOf(group.copy(lastActivity = "written without a key")))
        assertEquals(before, file.readBytes().toList())

        file.writeText(
            """[{"id":"g1","name":"Family","description":"","memberCount":2,"lastActivity":"$secret","lastActivityAt":5,""" +
                """"members":[{"userId":"bob","name":"Bob"}]}]"""
        )
        assertEquals(listOf(group), GroupListCache(file, mine).load())
        assertFalse("read once, it is sealed", readable(file).contains(secret))
        assertEquals(listOf(group), GroupListCache(file, mine).load())
    }

    @Test
    fun theFriendListIsSealed() {
        val file = folder.root.resolve("cache/friends.json")
        FriendGraphCache(file, mine).save("alice", listOf(friend))
        assertNothingReadable("Bob Example", "@bobby", "alice")
        assertEquals(listOf(friend), FriendGraphCache(file, mine).load("alice"))
        assertEquals("another account never reads it", emptyList<Friend>(), FriendGraphCache(file, mine).load("zoe"))
        assertEquals(emptyList<Friend>(), FriendGraphCache(file, another).load("alice"))
        assertEquals(emptyList<Friend>(), FriendGraphCache(file, none).load("alice"))
        val before = file.readBytes().toList()
        FriendGraphCache(file, none).save("alice", listOf(friend.copy(name = "written without a key")))
        assertEquals(before, file.readBytes().toList())

        file.writeText("""{"owner":"alice","friends":[{"id":"bob","name":"Bob Example","handle":"@bobby","isOnline":false,"relation":"Friend"}]}""")
        assertEquals(listOf(friend), FriendGraphCache(file, mine).load("alice"))
        assertFalse("read once, it is sealed", readable(file).contains("Bob Example"))
        assertEquals(listOf(friend), FriendGraphCache(file, mine).load("alice"))
    }

    // ------------------------------------------------------ drafts and stars

    @Test
    fun draftsAreSealed() {
        val file = folder.root.resolve("drafts.json")
        DraftStore(file, mine).set("bob", secret)
        assertNothingReadable("bob")
        assertEquals(secret, DraftStore(file, mine).get("bob"))
        assertEquals("", DraftStore(file, another).get("bob"))

        // Without a key: not read, not written, and what is there stays.
        val before = file.readBytes().toList()
        val locked = DraftStore(file, none)
        assertEquals("", locked.get("bob"))
        locked.set("carol", "typed while the key could not be had")
        assertEquals(before, file.readBytes().toList())
        assertEquals(secret, DraftStore(file, mine).get("bob"))

        // The last draft cleared: nothing is left behind.
        DraftStore(file, mine).clear("bob")
        assertFalse(file.exists())

        file.writeText("""{"bob":"$secret"}""")
        assertEquals(secret, DraftStore(file, mine).get("bob"))
        assertFalse("read once, it is sealed", readable(file).contains(secret))
    }

    @Test
    fun starredMessagesAreSealed() {
        val file = folder.root.resolve("starred.json")
        val star = StarredMessage(chatId = "bob", messageId = "m1", authorName = "Bob", preview = secret, at = 9L)
        StarredStore(file, mine).toggle(star)
        assertNothingReadable("Bob", "m1")
        assertEquals(listOf(star), StarredStore(file, mine).forChat("bob"))
        assertEquals(emptyList<StarredMessage>(), StarredStore(file, another).forChat("bob"))
        val before = file.readBytes().toList()
        StarredStore(file, none).toggle(star.copy(messageId = "m2"))
        assertEquals(before, file.readBytes().toList())

        file.writeText("""[{"chatId":"bob","messageId":"m1","authorName":"Bob","preview":"$secret","at":9}]""")
        assertEquals(listOf(star), StarredStore(file, mine).forChat("bob"))
        assertFalse(readable(file).contains(secret))
    }

    @Test
    fun pinsAndTheArchiveAreSealed() {
        val pins = folder.root.resolve("pinned.json")
        PinnedMessageStore(pins, mine).pin("chat-with-bob", "message-17")
        val archive = folder.root.resolve("archived.json")
        ArchivedStore(archive, mine).archive("chat-with-carol")
        assertNothingReadable("chat-with-bob", "message-17", "chat-with-carol")

        assertEquals("message-17", PinnedMessageStore(pins, mine).pinnedId("chat-with-bob"))
        assertNull(PinnedMessageStore(pins, another).pinnedId("chat-with-bob"))
        assertTrue(ArchivedStore(archive, mine).isArchived("chat-with-carol"))
        assertFalse(ArchivedStore(archive, another).isArchived("chat-with-carol"))

        // Without a key: nothing is read, nothing is written, and what is there stays.
        val before = pins.readBytes().toList() to archive.readBytes().toList()
        val lockedPins = PinnedMessageStore(pins, none)
        assertNull(lockedPins.pinnedId("chat-with-bob"))
        lockedPins.pin("chat-with-dave", "message-1")
        lockedPins.unpin("chat-with-dave")
        val lockedArchive = ArchivedStore(archive, none)
        assertFalse(lockedArchive.isArchived("chat-with-carol"))
        lockedArchive.archive("chat-with-dave")
        lockedArchive.unarchive("chat-with-dave")
        assertEquals(before, pins.readBytes().toList() to archive.readBytes().toList())

        // From a build before sealing.
        pins.writeText("""{"chat-with-bob":"message-17"}""")
        archive.writeText("""["chat-with-carol"]""")
        assertEquals("message-17", PinnedMessageStore(pins, mine).pinnedId("chat-with-bob"))
        assertTrue(ArchivedStore(archive, mine).isArchived("chat-with-carol"))
        assertNothingReadable("chat-with-bob", "message-17", "chat-with-carol")

        // The last one removed: nothing is left behind.
        PinnedMessageStore(pins, mine).unpin("chat-with-bob")
        ArchivedStore(archive, mine).unarchive("chat-with-carol")
        assertEquals(emptyList<File>(), everyFile())
    }

    // -------------------------------------------- the account ends, or changes

    @Test
    fun clearingForgetsWhatWasHeldAndTheNextAccountStartsEmpty() {
        val drafts = DraftStore(folder.root.resolve("drafts.json"), mine)
        val stars = StarredStore(folder.root.resolve("starred.json"), mine)
        val pins = PinnedMessageStore(folder.root.resolve("pinned.json"), mine)
        val archive = ArchivedStore(folder.root.resolve("archived.json"), mine)
        drafts.set("bob", secret)
        stars.toggle(StarredMessage(chatId = "bob", messageId = "m1", preview = secret))
        pins.pin("bob", "m1")
        archive.archive("bob")
        assertEquals(4, everyFile().size)

        drafts.clearAll(); stars.clearAll(); pins.clearAll(); archive.clearAll()
        assertEquals(emptyList<File>(), everyFile())
        assertEquals("", drafts.get("bob"))
        assertFalse(stars.isStarred("bob", "m1"))
        assertNull(pins.pinnedId("bob"))
        assertFalse(archive.isArchived("bob"))
        assertEquals(emptyMap<String, String>(), drafts.drafts.value)

        // The same objects go on serving whoever signs in next.
        drafts.set("carol", "hello")
        assertEquals("hello", DraftStore(folder.root.resolve("drafts.json"), mine).get("carol"))
    }

    @Test
    fun whatWasSealedBeforeTheKeyChangedIsReplacedNotKept() {
        // Signed out and in again, or another account on this phone: the old file never opens,
        // and it must not stop the new account from keeping its own.
        val file = folder.root.resolve("drafts.json")
        DraftStore(file, another).set("zoe", "somebody else's draft")
        val store = DraftStore(file, mine)
        assertEquals("", store.get("zoe"))
        store.set("bob", secret)
        assertEquals(mapOf("bob" to secret), DraftStore(file, mine).drafts.value)

        val root = folder.root.resolve("messages")
        assertTrue(MessageStore(root, another).save("alice", "bob", listOf(message.copy(id = "old"))))
        assertTrue(MessageStore(root, mine).save("alice", "bob", listOf(message)))
        assertEquals(listOf(message), MessageStore(root, mine).load("alice", "bob"))
    }

    // ----------------------------------------------------------- damaged files

    @Test
    fun aDamagedConversationCostsThatConversationOnly() {
        val root = folder.root.resolve("messages")
        val store = MessageStore(root, mine)
        assertTrue(store.save("alice", "bob", listOf(message)))
        assertTrue(store.save("alice", "carol", listOf(message.copy(chatId = "carol"))))
        val damaged = File(root, "alice/bob.json")
        damaged.writeBytes(damaged.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 4).toByte() })
        assertEquals(emptyList<Message>(), store.load("alice", "bob"))
        assertEquals(1, store.load("alice", "carol").size)
        // A cache: the next answer from the server replaces it.
        assertTrue(store.save("alice", "bob", listOf(message)))
        assertEquals(listOf(message), store.load("alice", "bob"))
    }

    @Test
    fun aDamagedOutboxIsNotOverwritten() {
        // The rule it had before sealing: a queue that cannot be read is not an empty queue.
        val file = folder.root.resolve("outbox.json")
        assertTrue(OutboxStore(file, mine).add(entry))
        file.writeBytes(file.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 4).toByte() })
        val before = file.readBytes().toList()
        val store = OutboxStore(file, mine)
        assertEquals(emptyList<OutboxEntry>(), store.all())
        assertFalse(store.add(entry.copy(clientId = "c2")))
        assertFalse(store.remove("c1"))
        assertEquals(before, file.readBytes().toList())
        // Signing out clears it, as before.
        assertTrue(store.clear())
        assertTrue(store.add(entry))
    }
}
