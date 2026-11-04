package com.nchat.app.data.mock

import com.nchat.app.data.model.AttachmentKind
import com.nchat.app.data.model.CallEntry
import com.nchat.app.data.model.CallOutcome
import com.nchat.app.data.model.CallType
import com.nchat.app.data.model.Channel
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Community
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageStatus
import com.nchat.app.data.model.Reaction
import com.nchat.app.data.model.Story
import com.nchat.app.data.model.UserProfile

/**
 * Local seed data. Everything the UI shows today comes from here so the app can be
 * exercised without a backend; the repositories are written so a Rust service can be
 * dropped in behind them later.
 */
object MockDataProvider {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private fun now(): Long = System.currentTimeMillis()

    val profile: UserProfile = UserProfile(
        fullName = "Muhammad Nasir",
        handle = "@nasir",
        about = "Building NChat — fast, private, and beautiful messaging.",
        phoneNumber = "+92 300 1234567",
        email = "nasir@nchat.app"
    )

    fun chats(): List<Chat> {
        val base = now()
        return listOf(
            Chat(
                id = "chat_areeb",
                title = "Areeb Khan",
                lastMessagePreview = "Bhai call recording bhej de, urgent hai.",
                lastMessageAt = base - 2 * MINUTE,
                unreadCount = 3,
                isPinned = true,
                isOnline = true,
                hasStory = true,
                storySeen = false
            ),
            Chat(
                id = "chat_family",
                title = "Family ❤️",
                lastMessagePreview = "Ammi: Khana kha liya sab ne?",
                lastMessageAt = base - 11 * MINUTE,
                unreadCount = 12,
                isGroup = true,
                memberCount = 9,
                isPinned = true,
                hasStory = true,
                storySeen = false,
                avatarEmoji = "👨‍👩‍👧"
            ),
            Chat(
                id = "chat_zain",
                title = "Zain Ahmed",
                lastMessagePreview = "Voice message",
                lastMessageAt = base - 34 * MINUTE,
                isTyping = true,
                lastMessageFromMe = false,
                isOnline = true
            ),
            Chat(
                id = "chat_design",
                title = "NChat Design Team",
                lastMessagePreview = "You: Pushed the new bubble spec, please review.",
                lastMessageAt = base - 58 * MINUTE,
                unreadCount = 4,
                isGroup = true,
                memberCount = 6,
                isVerified = true,
                lastMessageFromMe = true,
                lastMessageStatus = MessageStatus.Delivered,
                hasStory = true,
                storySeen = true
            ),
            Chat(
                id = "chat_ayesha",
                title = "Ayesha Noor",
                lastMessagePreview = "Photo",
                lastMessageAt = base - 2 * HOUR,
                unreadCount = 1,
                isOnline = true,
                hasStory = true,
                storySeen = false
            ),
            Chat(
                id = "chat_hamza",
                title = "Hamza Ali",
                lastMessagePreview = "Kal milte hain InshaAllah 👍",
                lastMessageAt = base - 4 * HOUR,
                draft = "yaar sun—"
            ),
            Chat(
                id = "chat_news",
                title = "NChat Announcements",
                lastMessagePreview = "Version 6.1 is rolling out with faster media sync.",
                lastMessageAt = base - 6 * HOUR,
                unreadCount = 2,
                isVerified = true,
                isMuted = true,
                avatarEmoji = "📣"
            ),
            Chat(
                id = "chat_sara",
                title = "Sara Malik",
                lastMessagePreview = "You: The invoice is attached.",
                lastMessageAt = base - 1 * DAY,
                lastMessageFromMe = true,
                lastMessageStatus = MessageStatus.Read,
                isMuted = true
            ),
            Chat(
                id = "chat_usman",
                title = "Usman Tariq",
                lastMessagePreview = "Missed voice call",
                lastMessageAt = base - 1 * DAY - 3 * HOUR,
                isMuted = true
            ),
            Chat(
                id = "chat_hira",
                title = "Hira Shah",
                lastMessagePreview = "Haan bilkul, main dekh leti hoon.",
                lastMessageAt = base - 2 * DAY,
                unreadCount = 1,
                hasStory = true,
                storySeen = true
            ),
            Chat(
                id = "chat_hassan",
                title = "Hassan Rauf",
                lastMessagePreview = "Sticker",
                lastMessageAt = base - 2 * DAY - 5 * HOUR
            ),
            Chat(
                id = "chat_worked",
                title = "Work — Sprint 24",
                lastMessagePreview = "Daniyal: Standup notes added to the board.",
                lastMessageAt = base - 3 * DAY,
                isGroup = true,
                memberCount = 14,
                isMuted = true
            ),
            Chat(
                id = "chat_fatima",
                title = "Fatima Zahra",
                lastMessagePreview = "Thank you so much! 🙏",
                lastMessageAt = base - 4 * DAY,
                lastMessageFromMe = false
            ),
            Chat(
                id = "chat_laiba",
                title = "Laiba",
                lastMessagePreview = "Bhej diya hai document.",
                lastMessageAt = base - 5 * DAY
            ),
            Chat(
                id = "chat_bilal",
                title = "Bilal Khan",
                lastMessagePreview = "You: Done bhai.",
                lastMessageAt = base - 6 * DAY,
                lastMessageFromMe = true,
                lastMessageStatus = MessageStatus.Read,
                isMuted = true
            ),
            Chat(
                id = "chat_iqra",
                title = "Iqra Bibi",
                lastMessagePreview = "Location shared",
                lastMessageAt = base - 8 * DAY
            ),
            Chat(
                id = "chat_saad",
                title = "Saad Javed",
                lastMessagePreview = "Ok",
                lastMessageAt = base - 9 * DAY
            ),
            Chat(
                id = "chat_maryam",
                title = "Maryam",
                lastMessagePreview = "You: See you tomorrow.",
                lastMessageAt = base - 12 * DAY,
                lastMessageFromMe = true,
                lastMessageStatus = MessageStatus.Read
            )
        )
    }

    fun conversation(chatId: String, chatTitle: String): List<Message> {
        val base = now()
        return listOf(
            Message(
                id = "$chatId-m1",
                chatId = chatId,
                authorName = chatTitle,
                text = "Assalam o alaikum! Sab theek?",
                timestamp = base - 1 * DAY - 4 * HOUR,
                isMine = false
            ),
            Message(
                id = "$chatId-m2",
                chatId = chatId,
                authorName = "You",
                text = "Walaikum salam, bilkul theek. Tum sunao?",
                timestamp = base - 1 * DAY - 4 * HOUR + 3 * MINUTE,
                isMine = true,
                status = MessageStatus.Read
            ),
            Message(
                id = "$chatId-m3",
                chatId = chatId,
                authorName = chatTitle,
                text = "Alhamdulillah. Ek cheez share karni thi, dekh lena.",
                timestamp = base - 1 * DAY - 3 * HOUR,
                isMine = false,
                reactions = listOf(Reaction("👍", 1, reactedByMe = true))
            ),
            Message(
                id = "$chatId-m4",
                chatId = chatId,
                authorName = chatTitle,
                text = "Voice note",
                timestamp = base - 1 * DAY - 3 * HOUR + 2 * MINUTE,
                isMine = false,
                attachment = AttachmentKind.Voice,
                durationLabel = "00:42"
            ),
            Message(
                id = "$chatId-m5",
                chatId = chatId,
                authorName = "You",
                text = "Sunn liya, zabardast! Main aaj hi kaam start kar deta hoon.",
                timestamp = base - 1 * DAY - 2 * HOUR,
                isMine = true,
                status = MessageStatus.Read,
                replyAuthor = chatTitle,
                replyText = "Voice note"
            ),
            Message(
                id = "$chatId-m6",
                chatId = chatId,
                authorName = chatTitle,
                text = "Photo",
                timestamp = base - 5 * HOUR,
                isMine = false,
                attachment = AttachmentKind.Photo,
                attachmentLabel = "IMG_2048.jpg"
            ),
            Message(
                id = "$chatId-m7",
                chatId = chatId,
                authorName = "You",
                text = "Ye design bohat clean lag rahi hai 🔥",
                timestamp = base - 4 * HOUR,
                isMine = true,
                status = MessageStatus.Delivered,
                reactions = listOf(
                    Reaction("🔥", 2, reactedByMe = false),
                    Reaction("❤️", 1, reactedByMe = true)
                )
            ),
            Message(
                id = "$chatId-m8",
                chatId = chatId,
                authorName = chatTitle,
                text = "Bhai call recording bhej de, urgent hai.",
                timestamp = base - 2 * MINUTE,
                isMine = false
            )
        )
    }

    fun calls(): List<CallEntry> {
        val base = now()
        return listOf(
            CallEntry(
                id = "call_1",
                contactName = "Areeb Khan",
                type = CallType.Voice,
                outcome = CallOutcome.Missed,
                isOutgoing = false,
                timestamp = base - 25 * MINUTE
            ),
            CallEntry(
                id = "call_2",
                contactName = "Zain Ahmed",
                type = CallType.Video,
                outcome = CallOutcome.Answered,
                isOutgoing = true,
                timestamp = base - 2 * HOUR,
                durationLabel = "12:08"
            ),
            CallEntry(
                id = "call_3",
                contactName = "NChat Design Team",
                type = CallType.Video,
                outcome = CallOutcome.Answered,
                isOutgoing = true,
                timestamp = base - 5 * HOUR,
                durationLabel = "48:31",
                isGroup = true,
                participantCount = 5
            ),
            CallEntry(
                id = "call_4",
                contactName = "Ayesha Noor",
                type = CallType.Voice,
                outcome = CallOutcome.Declined,
                isOutgoing = false,
                timestamp = base - 8 * HOUR
            ),
            CallEntry(
                id = "call_5",
                contactName = "Usman Tariq",
                type = CallType.Voice,
                outcome = CallOutcome.Missed,
                isOutgoing = false,
                timestamp = base - 1 * DAY - 2 * HOUR
            ),
            CallEntry(
                id = "call_6",
                contactName = "Hamza Ali",
                type = CallType.Voice,
                outcome = CallOutcome.Answered,
                isOutgoing = false,
                timestamp = base - 1 * DAY - 6 * HOUR,
                durationLabel = "03:47"
            ),
            CallEntry(
                id = "call_7",
                contactName = "Sara Malik",
                type = CallType.Video,
                outcome = CallOutcome.Answered,
                isOutgoing = true,
                timestamp = base - 3 * DAY,
                durationLabel = "21:15"
            )
        )
    }

    fun stories(): List<Story> {
        val base = now()
        return listOf(
            Story(
                id = "story_me",
                authorName = "My status",
                caption = "Add to my status",
                timestamp = 0L,
                viewed = true,
                isMine = true
            ),
            Story(
                id = "story_areeb",
                authorName = "Areeb Khan",
                caption = "Late night build session 🚀",
                timestamp = base - 30 * MINUTE,
                viewed = false
            ),
            Story(
                id = "story_ayesha",
                authorName = "Ayesha Noor",
                caption = "Coffee and code ☕",
                timestamp = base - 2 * HOUR,
                viewed = false
            ),
            Story(
                id = "story_family",
                authorName = "Family ❤️",
                caption = "Eid prep at home",
                timestamp = base - 3 * HOUR,
                viewed = false,
                avatarEmoji = "👨‍👩‍👧"
            ),
            Story(
                id = "story_hira",
                authorName = "Hira Shah",
                caption = "Mountains again 🏔️",
                timestamp = base - 6 * HOUR,
                viewed = true
            ),
            Story(
                id = "story_design",
                authorName = "NChat Design",
                caption = "New bubble radius preview",
                timestamp = base - 9 * HOUR,
                viewed = true
            )
        )
    }

    fun channels(): List<Channel> {
        val base = now()
        return listOf(
            Channel(
                id = "channel_news",
                name = "NChat Announcements",
                lastUpdate = "Version 6.1 is rolling out with faster media sync.",
                timestamp = base - 6 * HOUR,
                followerLabel = "182K followers",
                verified = true,
                unreadCount = 2
            ),
            Channel(
                id = "channel_tech",
                name = "Kotlin Weekly",
                lastUpdate = "Compose 1.7 stable, and what it means for app startup.",
                timestamp = base - 1 * DAY,
                followerLabel = "64K followers",
                verified = true
            ),
            Channel(
                id = "channel_local",
                name = "Abbottabad Updates",
                lastUpdate = "Snowfall expected on the northern routes this weekend.",
                timestamp = base - 1 * DAY - 4 * HOUR,
                followerLabel = "12K followers",
                verified = false,
                isMuted = true
            )
        )
    }

    fun communities(): List<Community> {
        return listOf(
            Community(
                id = "community_devs",
                name = "NChat Developers",
                description = "Engineers building NChat clients",
                memberLabel = "2.4K members",
                unreadCount = 18,
                groupCount = 6,
                announcement = "Rust backend RFC #14 is open for comments.",
                avatarEmoji = "🛠️"
            ),
            Community(
                id = "community_campus",
                name = "Campus 2026",
                description = "Batch announcements and study groups",
                memberLabel = "860 members",
                unreadCount = 0,
                groupCount = 11,
                announcement = "Mid-term schedule is posted in the announcements group."
            ),
            Community(
                id = "community_family",
                name = "Extended Family",
                description = "Events, photos, and updates",
                memberLabel = "54 members",
                unreadCount = 3,
                groupCount = 4,
                announcement = "Eid dinner at 8 PM, please confirm attendance.",
                avatarEmoji = "🏡"
            )
        )
    }
}
