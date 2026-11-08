package com.nchat.app.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the backend's tokens and the signed-in identity live.
 *
 * Values are AES-256-GCM encrypted under an Android Keystore key (see [SecurePrefs]), because
 * a refresh token is a 30-day bearer credential: plain text in app storage would be worth
 * stealing on a rooted or backed-up device.
 *
 * The server rotates refresh tokens on every use (single-use `sid`), so the pair has to be
 * written atomically-ish on every refresh — [saveTokens] does exactly that.
 */
class TokenStore(context: Context) {

    private val prefs = SecurePrefs(context.applicationContext, PREFS)

    private val _signedIn = MutableStateFlow(!prefs.getString(KEY_ACCESS).isNullOrEmpty())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * True when the credential pair was thrown away while the app was still signed in — i.e. the
     * server refused our refresh token (revoked, already rotated by another client, expired).
     *
     * Without this the app kept the stored e-mail, stayed on the home screen and showed every
     * tab as empty, which looks exactly like "all my chats were deleted". Watching this flow
     * lets the UI go back to the sign-in screen and say what happened instead.
     */
    private val _sessionLost = MutableStateFlow(false)
    val sessionLost: StateFlow<Boolean> = _sessionLost.asStateFlow()

    fun acknowledgeSessionLost() {
        _sessionLost.value = false
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS)
        set(value) {
            prefs.putString(KEY_ACCESS, value)
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH)
        set(value) {
            prefs.putString(KEY_REFRESH, value)
        }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID)
        set(value) {
            prefs.putString(KEY_USER_ID, value)
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL)
        set(value) {
            prefs.putString(KEY_EMAIL, value)
        }

    var fullName: String?
        get() = prefs.getString(KEY_FULL_NAME)
        set(value) {
            prefs.putString(KEY_FULL_NAME, value)
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME)
        set(value) {
            prefs.putString(KEY_USERNAME, value)
        }

    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR)
        set(value) {
            prefs.putString(KEY_AVATAR, value)
        }

    /** False until the profile-setup step is finished on this device. */
    var profileComplete: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_DONE)
        set(value) {
            prefs.putBoolean(KEY_PROFILE_DONE, value)
        }

    /**
     * The whole token pair from `POST /auth/login`, `/auth/signup` or `/auth/refresh`.
     *
     * Written synchronously: the server single-uses refresh tokens, so losing this write means
     * losing the session (see [SecurePrefs.putStringsSync]).
     */
    fun saveTokens(access: String?, refresh: String?) {
        val hadSession = _signedIn.value
        // A single commit prevents a crash between two writes from leaving a
        // mismatched access/refresh pair on disk after token rotation.
        prefs.putStringsSync(KEY_ACCESS to access, KEY_REFRESH to refresh)
        val signedIn = !access.isNullOrEmpty()
        _signedIn.value = signedIn
        if (hadSession && !signedIn) _sessionLost.value = true
    }

    fun saveIdentity(id: String?, email: String?, fullName: String?, username: String?, avatarUrl: String?) {
        prefs.putString(KEY_USER_ID, id)
        prefs.putString(KEY_EMAIL, email)
        prefs.putString(KEY_FULL_NAME, fullName)
        prefs.putString(KEY_USERNAME, username)
        prefs.putString(KEY_AVATAR, avatarUrl)
    }

    fun clear() {
        prefs.remove(
            KEY_ACCESS,
            KEY_REFRESH,
            KEY_USER_ID,
            KEY_EMAIL,
            KEY_FULL_NAME,
            KEY_USERNAME,
            KEY_AVATAR,
            KEY_PROFILE_DONE
        )
        _signedIn.value = false
        _sessionLost.value = false
    }

    private companion object {
        const val PREFS = "nchat_remote"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_USERNAME = "username"
        const val KEY_AVATAR = "avatar_url"
        const val KEY_PROFILE_DONE = "profile_complete"
    }
}
