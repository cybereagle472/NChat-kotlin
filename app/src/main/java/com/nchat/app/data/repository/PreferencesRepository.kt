package com.nchat.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.nchat.app.network.NChatApi
import com.nchat.app.network.PrivacySettingsDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * Privacy tiers and notification preferences.
 *
 * Privacy is the backend's (`/privacy`) — it decides what other people's clients may see, so
 * it has to live on the server. Notifications are per-device by nature and stay local.
 */
interface PreferencesRepository {
    val privacy: StateFlow<PrivacySettings>
    val notifications: StateFlow<NotificationSettings>

    fun setLastSeen(value: Visibility)
    fun setProfilePhoto(value: Visibility)
    fun setReadReceipts(enabled: Boolean)
    fun setOnlineStatus(enabled: Boolean)

    /** Pulls the privacy tiers from the backend. */
    suspend fun refresh() = Unit

    fun setMessageNotifications(enabled: Boolean)
    fun setCallNotifications(enabled: Boolean)
    fun setShowPreview(enabled: Boolean)
    fun setSound(enabled: Boolean)
    fun setVibrate(enabled: Boolean)
}

class LocalPreferencesRepository(context: Context) : PreferencesRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A stored flag, whatever type it happens to be in.
     *
     * `SharedPreferences.getBoolean` throws `ClassCastException` when the key holds another
     * type, and one such key is enough to make the app unopenable (see `SecurePrefs`). A
     * missing or unreadable flag falls back to the default instead.
     */
    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    private val _privacy = MutableStateFlow(
        PrivacySettings(
            lastSeen = prefs.visibility(KEY_LAST_SEEN, Visibility.Everyone),
            profilePhoto = prefs.visibility(KEY_PROFILE_PHOTO, Visibility.Everyone),
            readReceipts = flag(KEY_READ_RECEIPTS, true),
            onlineStatus = flag(KEY_ONLINE_STATUS, true)
        )
    )
    override val privacy: StateFlow<PrivacySettings> = _privacy.asStateFlow()

    private val _notifications = MutableStateFlow(
        NotificationSettings(
            messages = flag(KEY_NOTIF_MESSAGES, true),
            calls = flag(KEY_NOTIF_CALLS, true),
            preview = flag(KEY_NOTIF_PREVIEW, true),
            sound = flag(KEY_NOTIF_SOUND, true),
            vibrate = flag(KEY_NOTIF_VIBRATE, true)
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

/**
 * Privacy tiers from `GET/PATCH /privacy`; notification switches stay device-local, because
 * the reference has no server-side notification preferences either.
 */
class RemotePreferencesRepository(
    context: Context,
    private val api: NChatApi,
    private val scope: CoroutineScope
) : PreferencesRepository {

    private val local = LocalPreferencesRepository(context)

    private val _privacy = MutableStateFlow(PrivacySettings())
    override val privacy: StateFlow<PrivacySettings> = _privacy.asStateFlow()

    override val notifications: StateFlow<NotificationSettings> = local.notifications

    init {
        scope.launch { runCatching { refresh() } }
    }

    override suspend fun refresh() {
        val remote = runCatching { api.privacySettings().settings }.getOrNull() ?: return
        _privacy.value = remote.toModel()
    }

    override fun setLastSeen(value: Visibility) {
        _privacy.update { it.copy(lastSeen = value) }
        push(lastSeen = value)
    }

    override fun setProfilePhoto(value: Visibility) {
        _privacy.update { it.copy(profilePhoto = value) }
        push(profilePhoto = value)
    }

    override fun setReadReceipts(enabled: Boolean) {
        _privacy.update { it.copy(readReceipts = enabled) }
        push(readReceipts = enabled)
    }

    override fun setOnlineStatus(enabled: Boolean) {
        _privacy.update { it.copy(onlineStatus = enabled) }
        push(onlineStatus = enabled)
    }

    override fun setMessageNotifications(enabled: Boolean) = local.setMessageNotifications(enabled)
    override fun setCallNotifications(enabled: Boolean) = local.setCallNotifications(enabled)
    override fun setShowPreview(enabled: Boolean) = local.setShowPreview(enabled)
    override fun setSound(enabled: Boolean) = local.setSound(enabled)
    override fun setVibrate(enabled: Boolean) = local.setVibrate(enabled)

    private fun push(
        lastSeen: Visibility? = null,
        profilePhoto: Visibility? = null,
        readReceipts: Boolean? = null,
        onlineStatus: Boolean? = null
    ) {
        scope.launch {
            val response = runCatching {
                api.updatePrivacy(
                    lastSeenVisibility = lastSeen?.wire(),
                    profilePhotoVisibility = profilePhoto?.wire(),
                    readReceipts = readReceipts,
                    onlineStatusEnabled = onlineStatus
                )
            }.getOrNull()
            // Snap back to the server's truth if the write was rejected.
            response?.settings?.let { _privacy.value = it.toModel() }
        }
    }

    private fun PrivacySettingsDto.toModel() = PrivacySettings(
        lastSeen = lastSeenVisibility.toVisibility(Visibility.Everyone),
        profilePhoto = profilePhotoVisibility.toVisibility(Visibility.Everyone),
        readReceipts = showsReadReceipts,
        onlineStatus = showsOnlineStatus
    )

    private fun String.toVisibility(fallback: Visibility): Visibility = when (this) {
        "everyone" -> Visibility.Everyone
        "contacts" -> Visibility.Contacts
        "nobody" -> Visibility.Nobody
        else -> fallback
    }

    private fun Visibility.wire(): String = when (this) {
        Visibility.Everyone -> "everyone"
        Visibility.Contacts -> "contacts"
        Visibility.Nobody -> "nobody"
    }
}
