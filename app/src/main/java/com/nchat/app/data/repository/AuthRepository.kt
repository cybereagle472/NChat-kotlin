package com.nchat.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Outcome of a sign-in / sign-up attempt. */
sealed interface AuthResult {
    data object Success : AuthResult

    data class Failure(val message: String) : AuthResult
}

/**
 * Local stand-in for the reference `authService`: accounts and the signed-in session are
 * remembered in SharedPreferences, so signing up once survives restarts.
 */
interface AuthRepository {
    /** Signed-in email, or null when signed out. */
    val session: StateFlow<String?>

    /** False until the profile-setup step has been completed for this account. */
    val profileComplete: StateFlow<Boolean>

    fun signIn(email: String, password: String): AuthResult
    fun signUp(email: String, password: String): AuthResult
    fun isEmailRegistered(email: String): Boolean
    fun completeProfile()
    fun signOut()
}

class LocalAuthRepository(context: Context) : AuthRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val registered: MutableSet<String> =
        (prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()).toMutableSet()

    private val _session = MutableStateFlow(prefs.getString(KEY_EMAIL, null))
    override val session: StateFlow<String?> = _session.asStateFlow()

    private val _profileComplete = MutableStateFlow(prefs.getBoolean(KEY_PROFILE_DONE, false))
    override val profileComplete: StateFlow<Boolean> = _profileComplete.asStateFlow()

    override fun isEmailRegistered(email: String): Boolean =
        registered.contains(normalize(email))

    override fun signIn(email: String, password: String): AuthResult {
        val key = normalize(email)
        if (!EMAIL_RE.matches(key)) return AuthResult.Failure("Please enter a valid email address")
        if (password.length < 6) return AuthResult.Failure("Password must be at least 6 characters")
        if (!registered.contains(key)) {
            return AuthResult.Failure("No account found for this email — sign up instead")
        }
        startSession(key)
        return AuthResult.Success
    }

    override fun signUp(email: String, password: String): AuthResult {
        val key = normalize(email)
        if (!EMAIL_RE.matches(key)) return AuthResult.Failure("Please enter a valid email address")
        if (password.length < 6) return AuthResult.Failure("Password must be at least 6 characters")
        if (registered.contains(key)) {
            return AuthResult.Failure("This email is already registered — log in instead")
        }
        registered += key
        prefs.edit().putStringSet(KEY_ACCOUNTS, registered.toSet()).apply()
        prefs.edit().putBoolean(KEY_PROFILE_DONE, false).apply()
        startSession(key)
        return AuthResult.Success
    }

    override fun completeProfile() {
        prefs.edit().putBoolean(KEY_PROFILE_DONE, true).apply()
        _profileComplete.value = true
    }

    override fun signOut() {
        prefs.edit().remove(KEY_EMAIL).apply()
        prefs.edit().putBoolean(KEY_PROFILE_DONE, false).apply()
        _session.value = null
        _profileComplete.value = false
    }

    private fun startSession(email: String) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
        _session.value = email
        _profileComplete.value = prefs.getBoolean(KEY_PROFILE_DONE, false)
    }

    private fun normalize(email: String) = email.trim().lowercase()

    private companion object {
        const val PREFS = "nchat_auth"
        const val KEY_EMAIL = "session_email"
        const val KEY_ACCOUNTS = "registered_accounts"
        const val KEY_PROFILE_DONE = "profile_complete"
        val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
