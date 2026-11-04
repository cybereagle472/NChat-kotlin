package com.nchat.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who may see a privacy-gated field, mirrored from `privacyService.ts`. */
enum class Visibility(val label: String) {
    Everyone("Everyone"),
    Contacts("My Contacts"),
    Nobody("Nobody");

    fun next(): Visibility = entries[(ordinal + 1) % entries.size]
}

data class PrivacySettings(
    val lastSeen: Visibility = Visibility.Everyone,
    val profilePhoto: Visibility = Visibility.Everyone,
    val readReceipts: Boolean = true,
    val onlineStatus: Boolean = true
)

data class NotificationSettings(
    val messages: Boolean = true,
    val calls: Boolean = true,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true
)

/**
 * Local stand-in for the reference `privacyService` + notification preferences. Values are
 * kept in SharedPreferences so a toggle survives a relaunch, which is what makes the
 * Settings panes feel real rather than decorative.
 */
interface PreferencesRepository {
    val privacy: StateFlow<PrivacySettings>
    val notifications: StateFlow<NotificationSettings>

    fun setLastSeen(value: Visibility)
    fun setProfilePhoto(value: Visibility)
    fun setReadReceipts(enabled: Boolean)
    fun setOnlineStatus(enabled: Boolean)

    fun setMessageNotifications(enabled: Boolean)
    fun setCallNotifications(enabled: Boolean)
    fun setShowPreview(enabled: Boolean)
    fun setSound(enabled: Boolean)
    fun setVibrate(enabled: Boolean)
}

class LocalPreferencesRepository(context: Context) : PreferencesRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _privacy = MutableStateFlow(
        PrivacySettings(
            lastSeen = prefs.visibility(KEY_LAST_SEEN, Visibility.Everyone),
            profilePhoto = prefs.visibility(KEY_PROFILE_PHOTO, Visibility.Everyone),
            readReceipts = prefs.getBoolean(KEY_READ_RECEIPTS, true),
            onlineStatus = prefs.getBoolean(KEY_ONLINE_STATUS, true)
        )
    )
    override val privacy: StateFlow<PrivacySettings> = _privacy.asStateFlow()

    private val _notifications = MutableStateFlow(
        NotificationSettings(
            messages = prefs.getBoolean(KEY_NOTIF_MESSAGES, true),
            calls = prefs.getBoolean(KEY_NOTIF_CALLS, true),
            preview = prefs.getBoolean(KEY_NOTIF_PREVIEW, true),
            sound = prefs.getBoolean(KEY_NOTIF_SOUND, true),
            vibrate = prefs.getBoolean(KEY_NOTIF_VIBRATE, true)
        )
    )
    override val notifications: StateFlow<NotificationSettings> = _notifications.asStateFlow()

    override fun setLastSeen(value: Visibility) {
        prefs.edit().putString(KEY_LAST_SEEN, value.name).apply()
        _privacy.update { it.copy(lastSeen = value) }
    }

    override fun setProfilePhoto(value: Visibility) {
        prefs.edit().putString(KEY_PROFILE_PHOTO, value.name).apply()
        _privacy.update { it.copy(profilePhoto = value) }
    }

    override fun setReadReceipts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_RECEIPTS, enabled).apply()
        _privacy.update { it.copy(readReceipts = enabled) }
    }

    override fun setOnlineStatus(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_STATUS, enabled).apply()
        _privacy.update { it.copy(onlineStatus = enabled) }
    }

    override fun setMessageNotifications(enabled: Boolean) =
        updateNotifications(KEY_NOTIF_MESSAGES, enabled) { it.copy(messages = enabled) }

    override fun setCallNotifications(enabled: Boolean) =
        updateNotifications(KEY_NOTIF_CALLS, enabled) { it.copy(calls = enabled) }

    override fun setShowPreview(enabled: Boolean) =
        updateNotifications(KEY_NOTIF_PREVIEW, enabled) { it.copy(preview = enabled) }

    override fun setSound(enabled: Boolean) =
        updateNotifications(KEY_NOTIF_SOUND, enabled) { it.copy(sound = enabled) }

    override fun setVibrate(enabled: Boolean) =
        updateNotifications(KEY_NOTIF_VIBRATE, enabled) { it.copy(vibrate = enabled) }

    private fun updateNotifications(
        key: String,
        enabled: Boolean,
        transform: (NotificationSettings) -> NotificationSettings
    ) {
        prefs.edit().putBoolean(key, enabled).apply()
        _notifications.update(transform)
    }

    private fun SharedPreferences.visibility(key: String, fallback: Visibility): Visibility =
        getString(key, null)?.let { name ->
            Visibility.entries.firstOrNull { it.name == name }
        } ?: fallback

    private companion object {
        const val PREFS = "nchat_prefs"
        const val KEY_LAST_SEEN = "privacy_last_seen"
        const val KEY_PROFILE_PHOTO = "privacy_profile_photo"
        const val KEY_READ_RECEIPTS = "privacy_read_receipts"
        const val KEY_ONLINE_STATUS = "privacy_online_status"
        const val KEY_NOTIF_MESSAGES = "notif_messages"
        const val KEY_NOTIF_CALLS = "notif_calls"
        const val KEY_NOTIF_PREVIEW = "notif_preview"
        const val KEY_NOTIF_SOUND = "notif_sound"
        const val KEY_NOTIF_VIBRATE = "notif_vibrate"
    }
}
