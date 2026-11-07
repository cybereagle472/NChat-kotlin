package com.nchat.app.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.nchat.app.MainActivity
import com.nchat.app.R
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind

/**
 * System notifications — the thing NChat did not have at all until 1.0.51.
 *
 * Design mirrors WhatsApp:
 *
 *  * **One channel per conversation**, named after the chat, so Android's own settings can mute
 *    a single chat. A muted chat gets `IMPORTANCE_LOW`, which keeps the notification in the shade
 *    without a sound or a head-up card.
 *  * **`MessagingStyle`**, so several messages from the same chat stack inside one notification
 *    with the sender's name on each, and the conversation looks right on a lock screen.
 *  * **Inline reply** through a `RemoteInput` broadcast: answering from the shade does not open
 *    the app (`NotificationReplyReceiver`).
 *  * **Tapping opens that conversation**, not just the app (`ServiceLocator.pendingOpen`).
 *  * The open conversation never notifies for its own chat — [activeChatId] is set while a
 *    [com.nchat.app.feature.chats.ConversationScreen] is on screen.
 *
 * Notifications are best-effort: a missing POST_NOTIFICATIONS grant on Android 13+, or a vendor
 * ROM that throttles the notifier, must never break message delivery, so every call is guarded.
 */
class NcNotifications(
    private val context: Context,
    private val scope: CoroutineScope? = null,
    /**
     * Loads a sender's picture for the notification.
     *
     * Supplied by `ServiceLocator` (Coil, with the session token the backend needs). A missing
     * picture must never delay the alert, so the notification is posted first and upgraded in
     * place a moment later — the same id, so the user sees one notification that "fills in",
     * not two.
     */
    private val loadAvatar: (suspend (String) -> Bitmap?)? = null
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Chats whose pending notification is a mention.
     *
     * The mention is a property of the newest message, but the notification is rebuilt more than
     * once (the avatar arriving, a second message), so the flag has to outlive a single call or
     * "you were mentioned" would vanish on the second build.
     */
    private val mentionedChats = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Avatars already fetched this session, keyed by URL. */
    private val avatars = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The chat currently on screen; its messages are read, so they must not ring. */
    @Volatile
    var activeChatId: String? = null

    /** Recent lines per chat, so a second message extends the notification instead of replacing it. */
    private val lines = LinkedHashMap<String, MutableList<Line>>()

    private data class Line(val sender: String, val body: String, val at: Long, val id: String? = null)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(GROUP_CHANNEL) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    GROUP_CHANNEL,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "New messages in your conversations"
                    enableVibration(true)
                }
            )
        }
    }

    fun show(chat: Chat, message: Message, mentioned: Boolean = false) {
        if (!canPost()) return
        if (chat.id == activeChatId) return
        ensureChannels()

        val entry = lines.getOrPut(chat.id) { mutableListOf() }
        entry += Line(
            sender = if (chat.isGroup) message.authorName else chat.title,
            body = bodyOf(message),
            at = if (message.timestamp > 0) message.timestamp else System.currentTimeMillis(),
            // Kept so tapping the notification can land on this exact bubble, not just the chat.
            id = message.id
        )
        while (entry.size > MAX_LINES) entry.removeAt(0)

        if (mentioned) mentionedChats[chat.id] = true
        post(chat, entry, mentioned || mentionedChats[chat.id] == true)
        refreshAvatar(chat)
    }

    /**
     * A notification that is not a message: a friend request, being added to a group, a call.
     *
     * Same channels, same tap-to-open behaviour, same honesty about a missing permission — the
     * only difference is that there is no conversation to stack lines into, so each kind gets its
     * own id and its own picture.
     */
    fun showEvent(
        key: String,
        title: String,
        body: String,
        chatId: String? = null,
        chatTitle: String? = null,
        avatarUrl: String? = null,
        highPriority: Boolean = true,
        callId: String? = null
    ) {
        if (!canPost()) return
        ensureChannels()
        val channelId = ensureEventChannel()
        val avatar = avatarUrl?.let { url -> avatars[url] }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(0xFF7C3AED.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (highPriority) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setShowWhen(true)
            .apply {
                if (avatar != null) setLargeIcon(avatar)
                // Every alert opens something specific: the chat, or — for a call — the call
                // screen itself, which is what the user tapped for.
                if (chatId != null || callId != null) {
                    setContentIntent(openIntent(chatId, chatTitle ?: title, callId = callId))
                }
            }

        runCatching { manager.notify(EVENT_TAG.hashCode() * 31 + key.hashCode(), builder.build()) }

        val loader = loadAvatar
        val coroutineScope = scope
        if (avatarUrl != null && avatar == null && loader != null && coroutineScope != null) {
            coroutineScope.launch {
                val picture = runCatching { loader(avatarUrl) }.getOrNull() ?: return@launch
                avatars[avatarUrl] = picture
                main.post {
                    runCatching {
                        showEvent(key, title, body, chatId, chatTitle, avatarUrl, highPriority, callId)
                    }
                }
            }
        }
    }

    /**
     * An incoming call, in the shade and over the lock screen.
     *
     * A call is the one notification that cannot be a quiet line of text: it needs its own
     * channel with a ringtone and vibration (the message channels are silent-ish by design), it
     * stays until the call is answered, declined or ends, and it carries a **Decline** action so
     * the call does not have to be opened just to reject it. `setTimeoutAfter` is the backstop for
     * a caller whose "ended" frame never arrives — the notification cleans itself up instead of
     * ringing into the next hour.
     */
    fun showIncomingCall(
        callId: String,
        callerName: String,
        video: Boolean,
        avatarUrl: String? = null
    ) {
        if (!canPost()) return
        val channelId = ensureCallChannel()
        val avatar = avatarUrl?.let { url -> avatars[url] }
        val decline = PendingIntent.getBroadcast(
            context,
            callId.hashCode(),
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(0xFF7C3AED.toInt())
            .setContentTitle(if (video) "$callerName · video call" else "$callerName is calling")
            .setContentText("Tap to answer in NChat")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setTimeoutAfter(CALL_NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(openIntent(chatId = null, chatTitle = callerName, callId = callId))
            .addAction(R.drawable.ic_stat_message, "Decline", decline)
            .apply { if (avatar != null) setLargeIcon(avatar) }

        runCatching { manager.notify(CALL_TAG.hashCode() * 31 + callId.hashCode(), builder.build()) }

        val loader = loadAvatar
        val coroutineScope = scope
        if (avatarUrl != null && avatar == null && loader != null && coroutineScope != null) {
            coroutineScope.launch {
                val picture = runCatching { loader(avatarUrl) }.getOrNull() ?: return@launch
                avatars[avatarUrl] = picture
                main.post {
                    runCatching { showIncomingCall(callId, callerName, video, avatarUrl) }
                }
            }
        }
    }

    /** Stops the ringing notification for [callId] (answered, declined, or ended). */
    fun cancelIncomingCall(callId: String) {
        runCatching { manager.cancel(CALL_TAG.hashCode() * 31 + callId.hashCode()) }
    }

    /** Removes one [showEvent] notification (the call that stopped ringing, the handled request). */
    fun cancelEvent(key: String) {
        runCatching { manager.cancel(EVENT_TAG.hashCode() * 31 + key.hashCode()) }
    }

    /** Builds and posts the per-chat notification from the lines already collected for it. */
    private fun post(chat: Chat, entry: List<Line>, mentioned: Boolean = false) {
        val channelId = ensureChatChannel(chat)
        val avatar = chat.avatarUrl?.let { url -> avatars[url] }
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (chat.isGroup) chat.title else null)
        val senderIcon = avatar?.let { picture -> IconCompat.createWithBitmap(picture) }
        entry.forEach { line ->
            style.addMessage(
                line.body,
                line.at,
                when {
                    line.sender == "You" -> me
                    senderIcon != null -> Person.Builder().setName(line.sender).setIcon(senderIcon).build()
                    else -> Person.Builder().setName(line.sender).build()
                }
            )
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(0xFF7C3AED.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (chat.muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(entry.last().at)
            .setShowWhen(true)
            /*
             * A mention is the one message in a group that is *about you*, so it says so.
             *
             * The wire carries `@Name` as plain text (no attribute), which means the only place
             * the difference can be surfaced is here, where the text is handed to the system.
             */
            .setContentTitle(if (mentioned) "${chat.title} · you were mentioned" else chat.title)
            .setContentText(
                entry.last().let {
                    if (chat.isGroup) "${it.sender}: ${it.body}" else it.body
                }
            )
            .setContentIntent(openIntent(chat.id, chat.title, messageId = entry.last().id))
            .setGroup(GROUP_MESSAGES)
            .setOnlyAlertOnce(chat.muted)
            .apply { if (avatar != null) setLargeIcon(avatar) }

        // Replying from the shade also works for a muted chat; the channel only decides noise.
        builder.addAction(replyAction(chat))
        if (entry.size > 1) builder.setSubText("${entry.size} new messages")

        runCatching { manager.notify(chat.id.hashCode(), builder.build()) }

        // One silent summary keeps the group collapsed and gives the launcher its badge count.
        if (lines.size > 1) {
            val summary = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(0xFF7C3AED.toInt())
                .setGroup(GROUP_MESSAGES)
                .setGroupSummary(true)
                .setStyle(NotificationCompat.InboxStyle().setSummaryText("${lines.size} conversations"))
                .setContentTitle("NChat")
                .setContentText("${lines.size} conversations with new messages")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { manager.notify(SUMMARY_ID, summary) }
        }
    }

    /**
     * Fetches the sender's picture once per session, then reposts the same notification id with
     * it. Reposting an existing id is silent, so this "fills in" the picture instead of alerting
     * twice — and the first alert is never delayed waiting for a network round trip.
     */
    private fun refreshAvatar(chat: Chat) {
        val url = chat.avatarUrl ?: return
        if (avatars.containsKey(url)) return
        val loader = loadAvatar ?: return
        val coroutineScope = scope ?: return
        coroutineScope.launch {
            val picture = runCatching { loader(url) }.getOrNull() ?: return@launch
            avatars[url] = picture
            main.post {
                val current = lines[chat.id] ?: return@post
                runCatching { post(chat, current, mentionedChats[chat.id] == true) }
            }
        }
    }

    fun cancel(chatId: String) {
        lines.remove(chatId)
        mentionedChats.remove(chatId)
        runCatching { manager.cancel(chatId.hashCode()) }
        if (lines.isEmpty()) runCatching { manager.cancel(SUMMARY_ID) }
    }

    fun cancelAll() {
        lines.clear()
        mentionedChats.clear()
        runCatching { manager.cancelAll() }
    }

    // ---------------------------------------------------------------- channels

    /**
     * The ringing channel.
     *
     * Its own id, not the generic `events` one, because only a call should make the phone ring —
     * and because a user who silences "Requests and calls" should still be able to hear a call.
     */
    private fun ensureCallChannel(): String {
        val id = "calls"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return id
        if (system.getNotificationChannel(id) == null) {
            val ringtone = runCatching {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            }.getOrNull()
            system.createNotificationChannel(
                NotificationChannel(id, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Ringing for voice and video calls"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 500, 700)
                    if (ringtone != null) {
                        setSound(
                            ringtone,
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        return id
    }

    /** Channel for the non-message alerts (requests, groups, calls). */
    private fun ensureEventChannel(): String {
        val id = "events"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return id
        if (system.getNotificationChannel(id) == null) {
            system.createNotificationChannel(
                NotificationChannel(id, "Requests and calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Friend requests, group invites and incoming calls"
                    enableVibration(true)
                }
            )
        }
        return id
    }

    /**
     * The per-chat channel, rebuilt when the chat's mute state changes.
     *
     * Android does not let an app change a channel's importance in place, so a chat that was
     * muted and is now unmuted (or the reverse) needs a delete/recreate. Doing it only when the
     * importance actually differs keeps the user's own channel tweaks intact in every other case.
     */
    private fun ensureChatChannel(chat: Chat): String {
        val id = "$CHAT_PREFIX${chat.id}"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return id
        val wanted = if (chat.muted) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
        val existing = system.getNotificationChannel(id)
        if (existing == null || existing.importance != wanted) {
            if (existing != null) runCatching { system.deleteNotificationChannel(id) }
            system.createNotificationChannel(
                NotificationChannel(id, chat.title, wanted).apply {
                    description = "Messages in ${chat.title}"
                    if (!chat.muted) enableVibration(true)
                }
            )
        }
        return id
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // ---------------------------------------------------------------- intents

    private fun openIntent(
        chatId: String?,
        chatTitle: String,
        messageId: String? = null,
        callId: String? = null
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chatId != null) {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
            }
            if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
            if (callId != null) putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getActivity(
            context,
            (chatId ?: callId ?: chatTitle).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun replyAction(chat: Chat): NotificationCompat.Action {
        val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_ID, chat.id)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            chat.id.hashCode() + 7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val input = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "Reply", pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun bodyOf(message: Message): String = when {
        message.kind == MessageKind.Text -> message.text
        message.kind == MessageKind.Photo -> "📷 Photo"
        message.kind == MessageKind.Video -> "🎥 Video"
        message.kind == MessageKind.Voice -> "🎤 Voice message"
        message.kind == MessageKind.Document -> "📄 ${message.fileName ?: "Document"}"
        message.kind == MessageKind.Location -> "📍 Location"
        else -> message.snippet()
    }

    companion object {
        const val ACTION_REPLY = "com.nchat.app.notification.REPLY"
        const val EXTRA_CHAT_ID = "com.nchat.app.extra.CHAT_ID"

        /** The message a notification was about, so the chat opens *on* it. */
        const val EXTRA_MESSAGE_ID = "com.nchat.app.extra.MESSAGE_ID"

        /** The call a notification was about, so tapping it re-opens the call screen. */
        const val EXTRA_CALL_ID = "com.nchat.app.extra.CALL_ID"
        const val EXTRA_CHAT_TITLE = "com.nchat.app.extra.CHAT_TITLE"
        const val KEY_REPLY = "com.nchat.app.extra.REPLY_TEXT"

        /**
         * The foreground service's own channel: the quiet "NChat · connected" line.
         *
         * `IMPORTANCE_MIN` keeps it collapsed at the bottom of the shade with no icon, no sound
         * and no vibration — visible enough to be honest about what the app is doing, quiet
         * enough that it is not a notification anybody has to deal with.
         */
        fun backgroundChannel(context: Context): String {
            val id = "background"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
            val system = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return id
            if (system.getNotificationChannel(id) == null) {
                system.createNotificationChannel(
                    NotificationChannel(id, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Keeps NChat connected so messages arrive while the app is closed"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            return id
        }

        private const val CHAT_PREFIX = "chat_"
        /** How long a ringing notification survives if nothing cancels it. */
        private const val CALL_NOTIFICATION_TIMEOUT_MS = 45_000L
        private const val CALL_TAG = "call-ring"

        /** Namespace for [showEvent] ids, so they can never collide with a chat's id. */
        private const val EVENT_TAG = "event:"
        private const val GROUP_CHANNEL = "messages"
        private const val GROUP_MESSAGES = "com.nchat.app.MESSAGES"
        private const val SUMMARY_ID = 0x4E43484E
        private const val MAX_LINES = 6
    }
}
