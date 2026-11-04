package com.nchat.app.data.mock

import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.data.model.Reaction
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.UserProfile

/**
 * Local seed data so the client is fully explorable without the Rust backend.
 * Shapes match the reference app: chat rows carry the last message kind and delivery
 * state, because the list renders an icon plus a tick for both.
 */
object MockDataProvider {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private fun now(): Long = System.currentTimeMillis()

    val profile = UserProfile(
        fullName = "Muhammad Nasir",
        handle = "@nasir",
        about = "Building NChat — fast, private messaging.",
        phoneNumber = "+92 300 1234567"
    )

    fun chats(): List<Chat> {
        val base = now()
        return listOf(
            Chat(
                id = "areeb",
                title = "Areeb Khan",
                isOnline = true,
                lastMessage = "Bhai call recording bhej de, urgent hai.",
                lastMessageAt = base - 2 * MINUTE,
                unreadCount = 3
            ),
            Chat(
                id = "family",
                title = "Family ❤️",
                avatarEmoji = "👨‍👩‍👧",
                isGroup = true,
                lastMessage = "Ammi: Khana kha liya sab ne?",
                lastMessageAt = base - 11 * MINUTE,
                unreadCount = 12,
                lastMessageKind = MessageKind.Text
            ),
            Chat(
                id = "zain",
                title = "Zain Ahmed",
                isOnline = true,
                lastMessage = "Voice message",
                lastMessageAt = base - 34 * MINUTE,
                isTyping = true,
                lastMessageKind = MessageKind.Voice
            ),
            Chat(
                id = "ayesha",
                title = "Ayesha Noor",
                isOnline = true,
                lastMessage = "Photo",
                lastMessageAt = base - 58 * MINUTE,
                lastMessageKind = MessageKind.Photo,
                unreadCount = 1
            ),
            Chat(
                id = "hamza",
                title = "Hamza Ali",
                lastMessage = "Kal milte hain InshaAllah 👍",
                lastMessageAt = base - 2 * HOUR,
                lastMessageMine = true,
                lastMessageState = DeliveryState.Read
            ),
            Chat(
                id = "sara",
                title = "Sara Malik",
                lastMessage = "The invoice is attached.",
                lastMessageAt = base - 4 * HOUR,
                lastMessageMine = true,
                lastMessageKind = MessageKind.Document,
                lastMessageState = DeliveryState.Sent
            ),
            Chat(
                id = "usman",
                title = "Usman Tariq",
                lastMessage = "Video",
                lastMessageAt = base - 6 * HOUR,
                lastMessageKind = MessageKind.Video
            ),
            Chat(
                id = "hira",
                title = "Hira Shah",
                lastMessage = "Location shared",
                lastMessageAt = base - 1 * DAY,
                lastMessageKind = MessageKind.Location
            ),
            Chat(
                id = "hassan",
                title = "Hassan Rauf",
                lastMessage = "Sticker sent",
                lastMessageAt = base - 1 * DAY - 3 * HOUR
            ),
            Chat(
                id = "fatima",
                title = "Fatima Zahra",
                lastMessage = "Thank you so much! 🙏",
                lastMessageAt = base - 2 * DAY
            ),
            Chat(
                id = "laiba",
                title = "Laiba",
                lastMessage = "Bhej diya hai document.",
                lastMessageAt = base - 3 * DAY
            ),
            Chat(
                id = "bilal",
                title = "Bilal Khan",
                lastMessage = "Done bhai.",
                lastMessageAt = base - 5 * DAY,
                lastMessageMine = true,
                lastMessageState = DeliveryState.Read
            )
        )
    }

    fun conversation(chatId: String, chatTitle: String): List<Message> {
        val base = now()
        return listOf(
            Message(
                id = "$chatId-1",
                chatId = chatId,
                authorId = chatId,
                authorName = chatTitle,
                text = "Assalam o alaikum! Sab theek?",
                timestamp = base - 1 * DAY - 4 * HOUR,
                isMine = false
            ),
            Message(
                id = "$chatId-2",
                chatId = chatId,
                authorId = "me",
                authorName = "You",
                text = "Walaikum salam, bilkul theek. Tum sunao?",
                timestamp = base - 1 * DAY - 4 * HOUR + 3 * MINUTE,
                isMine = true,
                state = DeliveryState.Read
            ),
            Message(
                id = "$chatId-3",
                chatId = chatId,
                authorId = chatId,
                authorName = chatTitle,
                text = "Ek cheez share karni thi, dekh lena.",
                timestamp = base - 1 * DAY - 3 * HOUR,
                isMine = false,
                reactions = listOf(Reaction("👍", 1, reactedByMe = true))
            ),
            Message(
                id = "$chatId-4",
                chatId = chatId,
                authorId = chatId,
                authorName = chatTitle,
                text = "Voice message",
                timestamp = base - 1 * DAY - 3 * HOUR + 2 * MINUTE,
                isMine = false,
                kind = MessageKind.Voice,
                durationLabel = "00:42"
            ),
            Message(
                id = "$chatId-5",
                chatId = chatId,
                authorId = "me",
                authorName = "You",
                text = "Sunn liya, zabardast! Main aaj hi start karta hoon.",
                timestamp = base - 1 * DAY - 2 * HOUR,
                isMine = true,
                state = DeliveryState.Read,
                replyAuthor = chatTitle,
                replyText = "Voice message"
            ),
            Message(
                id = "$chatId-6",
                chatId = chatId,
                authorId = chatId,
                authorName = chatTitle,
                text = "Photo",
                timestamp = base - 5 * HOUR,
                isMine = false,
                kind = MessageKind.Photo
            ),
            Message(
                id = "$chatId-7",
                chatId = chatId,
                authorId = "me",
                authorName = "You",
                text = "Ye design bohat clean lag rahi hai 🔥",
                timestamp = base - 4 * HOUR,
                isMine = true,
                state = DeliveryState.Sent,
                reactions = listOf(
                    Reaction("🔥", 2, reactedByMe = false),
                    Reaction("❤️", 1, reactedByMe = true)
                )
            ),
            Message(
                id = "$chatId-8",
                chatId = chatId,
                authorId = chatId,
                authorName = chatTitle,
                text = "Bhai call recording bhej de, urgent hai.",
                timestamp = base - 2 * MINUTE,
                isMine = false
            )
        )
    }

    fun statuses(): List<StatusUpdate> {
        val base = now()
        return listOf(
            StatusUpdate(
                id = "me",
                authorName = "My status",
                caption = "Add to my status",
                timestamp = 0L,
                viewed = true,
                isMine = true
            ),
            StatusUpdate("areeb", "Areeb Khan", caption = "Late night build session 🚀", timestamp = base - 30 * MINUTE, viewed = false),
            StatusUpdate("ayesha", "Ayesha Noor", caption = "Coffee and code ☕", timestamp = base - 2 * HOUR, viewed = false),
            StatusUpdate("family", "Family ❤️", "👨‍👩‍👧", "Eid prep at home", base - 3 * HOUR, viewed = false),
            StatusUpdate("hira", "Hira Shah", caption = "Mountains again 🏔️", timestamp = base - 6 * HOUR, viewed = true),
            StatusUpdate("hassan", "Hassan Rauf", caption = "New lens test 📷", timestamp = base - 9 * HOUR, viewed = true)
        )
    }

    fun groups(): List<Group> {
        val base = now()
        return listOf(
            Group(
                id = "design",
                name = "NChat Design Team",
                description = "Bubbles, motion and the glass system",
                memberCount = 6,
                unreadCount = 4,
                lastActivity = "Pushed the new bubble spec",
                lastActivityAt = base - 22 * MINUTE
            ),
            Group(
                id = "sprint",
                name = "Work — Sprint 24",
                description = "Standups, blockers and release notes",
                memberCount = 14,
                lastActivity = "Daniyal: notes added to the board",
                lastActivityAt = base - 3 * HOUR
            ),
            Group(
                id = "campus",
                name = "Campus 2026",
                description = "Batch announcements and study groups",
                memberCount = 86,
                unreadCount = 1,
                lastActivity = "Mid-term schedule posted",
                lastActivityAt = base - 1 * DAY
            ),
            Group(
                id = "cricket",
                name = "Sunday Cricket 🏏",
                description = "Team selection and ground bookings",
                memberCount = 18,
                lastActivity = "Ground booked for 4 PM",
                lastActivityAt = base - 2 * DAY,
                avatarEmoji = "🏏"
            )
        )
    }

    /**
     * The whole friend graph in one list, exactly like the reference `friends`,
     * `requests` and `suggestions` props: `Friend` rows show on the Friends tab,
     * `Incoming` rows on Requests, and `Outgoing` / `None` rows in Find.
     */
    fun friends(): List<Friend> {
        val base = now()
        return listOf(
            Friend(
                "areeb", "Areeb Khan", "@areeb",
                isOnline = true, mutualFriends = 12, relation = FriendRelation.Friend
            ),
            Friend(
                "maryam", "Maryam Sheikh", "@maryam",
                isOnline = true, mutualFriends = 4, relation = FriendRelation.Friend
            ),
            Friend(
                "saad", "Saad Javed", "@saad",
                isOnline = false, mutualFriends = 5, relation = FriendRelation.Friend,
                lastSeenAt = base - 2 * HOUR
            ),
            Friend(
                "daniyal", "Daniyal Raza", "@daniyal",
                isOnline = false, mutualFriends = 3, relation = FriendRelation.Friend,
                lastSeenAt = base - 26 * HOUR
            ),
            Friend(
                "ayesha", "Ayesha Noor", "@ayesha",
                isOnline = true, mutualFriends = 8, relation = FriendRelation.Incoming,
                requestedAt = base - 8 * MINUTE
            ),
            Friend(
                "iqra", "Iqra Bibi", "@iqra",
                isOnline = false, mutualFriends = 2, relation = FriendRelation.Incoming,
                lastSeenAt = base - DAY,
                requestedAt = base - 3 * HOUR
            ),
            Friend(
                "zain", "Zain Ahmed", "@zain",
                isOnline = true, mutualFriends = 7, relation = FriendRelation.Outgoing,
                requestedAt = base - 20 * HOUR
            ),
            Friend(
                "hamza", "Hamza Tariq", "@hamza",
                isOnline = true, mutualFriends = 6
            ),
            Friend(
                "fatima", "Fatima Malik", "@fatima",
                isOnline = false, mutualFriends = 9,
                lastSeenAt = base - 5 * HOUR
            ),
            Friend(
                "bilal", "Bilal Shah", "@bilal",
                isOnline = true, mutualFriends = 1
            ),
            Friend(
                "nimra", "Nimra Aslam", "@nimra",
                isOnline = false, mutualFriends = 11,
                lastSeenAt = base - 3 * DAY
            ),
            Friend(
                "usman", "Usman Chaudhry", "@usman",
                isOnline = false, mutualFriends = 2,
                lastSeenAt = base - 6 * DAY
            )
        )
    }

    fun calls(): List<CallRecord> {
        val base = now()
        return listOf(
            CallRecord("c1", "Areeb Khan", isVideo = false, isOutgoing = false, missed = true, timestamp = base - 25 * MINUTE),
            CallRecord("c2", "Zain Ahmed", isVideo = true, isOutgoing = true, missed = false, timestamp = base - 2 * HOUR, durationLabel = "12:08"),
            CallRecord("c3", "NChat Design Team", isVideo = true, isOutgoing = true, missed = false, timestamp = base - 5 * HOUR, durationLabel = "48:31", isGroup = true),
            CallRecord("c4", "Ayesha Noor", isVideo = false, isOutgoing = false, missed = true, timestamp = base - 8 * HOUR),
            CallRecord("c5", "Usman Tariq", isVideo = false, isOutgoing = false, missed = true, timestamp = base - 1 * DAY),
            CallRecord("c6", "Hamza Ali", isVideo = false, isOutgoing = false, missed = false, timestamp = base - 1 * DAY - 6 * HOUR, durationLabel = "03:47"),
            CallRecord("c7", "Sara Malik", isVideo = true, isOutgoing = true, missed = false, timestamp = base - 3 * DAY, durationLabel = "21:15")
        )
    }
}
