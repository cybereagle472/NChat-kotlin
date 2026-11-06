package com.nchat.app.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
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
class NcNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** The chat currently on screen; its messages are read, so they must not ring. */
    @Volatile
    var activeChatId: String? = null

    /** Recent lines per chat, so a second message extends the notification instead of replacing it. */
    private val lines = LinkedHashMap<String, MutableList<Line>>()

    private data class Line(val sender: String, val body: String, val at: Long)

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

    fun show(chat: Chat, message: Message) {
        if (!canPost()) return
        if (chat.id == activeChatId) return
        ensureChannels()
        val channelId = ensureChatChannel(chat)

        val entry = lines.getOrPut(chat.id) { mutableListOf() }
        entry += Line(
            sender = if (chat.isGroup) message.authorName else chat.title,
            body = bodyOf(message),
            at = if (message.timestamp > 0) message.timestamp else System.currentTimeMillis()
        )
        while (entry.size > MAX_LINES) entry.removeAt(0)

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (chat.isGroup) chat.title else null)
        entry.forEach { line ->
            style.addMessage(
                line.body,
                line.at,
                if (line.sender == "You") me else Person.Builder().setName(line.sender).build()
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
            .setContentTitle(chat.title)
            .setContentText(entry.last().let { if (chat.isGroup) "${it.sender}: ${it.body}" else it.body })
            .setContentIntent(openIntent(chat))
            .setGroup(GROUP_MESSAGES)
            .setOnlyAlertOnce(chat.muted)

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

    fun cancel(chatId: String) {
        lines.remove(chatId)
        runCatching { manager.cancel(chatId.hashCode()) }
        if (lines.isEmpty()) runCatching { manager.cancel(SUMMARY_ID) }
    }

    fun cancelAll() {
        lines.clear()
        runCatching { manager.cancelAll() }
    }

    // ---------------------------------------------------------------- channels

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

    private fun openIntent(chat: Chat): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chat.id)
            putExtra(EXTRA_CHAT_TITLE, chat.title)
        }
        return PendingIntent.getActivity(
            context,
            chat.id.hashCode(),
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
        const val EXTRA_CHAT_TITLE = "com.nchat.app.extra.CHAT_TITLE"
        const val KEY_REPLY = "com.nchat.app.extra.REPLY_TEXT"

        private const val CHAT_PREFIX = "chat_"
        private const val GROUP_CHANNEL = "messages"
        private const val GROUP_MESSAGES = "com.nchat.app.MESSAGES"
        private const val SUMMARY_ID = 0x4E43484E
        private const val MAX_LINES = 6
    }
}
