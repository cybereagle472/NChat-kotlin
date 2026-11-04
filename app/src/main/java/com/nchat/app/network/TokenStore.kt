package com.nchat.app.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the Rust backend's tokens and the signed-in identity live.
 *
 * The server rotates refresh tokens on every use (single-use `sid`), so the pair has to be
 * written atomically-ish on every refresh — [saveTokens] does exactly that.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _signedIn = MutableStateFlow(!prefs.getString(KEY_ACCESS, null).isNullOrEmpty())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS, value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH, value).apply()
        }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) {
            prefs.edit().putString(KEY_USER_ID, value).apply()
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    var fullName: String?
        get() = prefs.getString(KEY_FULL_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_FULL_NAME, value).apply()
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR, null)
        set(value) {
            prefs.edit().putString(KEY_AVATAR, value).apply()
        }

    /** False until the profile-setup step is finished on this device. */
    var profileComplete: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROFILE_DONE, value).apply()
        }

    /** The whole token pair from `POST /auth/login`, `/auth/signup` or `/auth/refresh`. */
    fun saveTokens(access: String?, refresh: String?) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
        _signedIn.value = !access.isNullOrEmpty()
    }

    fun saveIdentity(id: String?, email: String?, fullName: String?, username: String?, avatarUrl: String?) {
        prefs.edit()
            .putString(KEY_USER_ID, id)
            .putString(KEY_EMAIL, email)
            .putString(KEY_FULL_NAME, fullName)
            .putString(KEY_USERNAME, username)
            .putString(KEY_AVATAR, avatarUrl)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_FULL_NAME)
            .remove(KEY_USERNAME)
            .remove(KEY_AVATAR)
            .remove(KEY_PROFILE_DONE)
            .apply()
        _signedIn.value = false
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
