package com.nchat.app.data.repository

import com.nchat.app.network.ApiException
import com.nchat.app.network.AuthResponse
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.network.NChatApi
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * `AuthRepository` against the Rust backend — the same three calls the reference's
 * `authService` makes (`/auth/check-email`, `/auth/signup`, `/auth/login`), plus the
 * refresh-token logout the worker expects.
 *
 * A restored session survives restarts: the token pair and the identity live in
 * [TokenStore], so the app opens straight into the signed-in state without a round trip and
 * the first protected request refreshes the access token if it has expired.
 */
class RemoteAuthRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
    /** On-device caches that must not survive a sign-out. */
    private val cache: ChatListCache? = null,
    /**
     * Everything else that is private to the account and lives on the device — drafts and
     * starred messages. The next person to sign in on this phone must not find them.
     */
    private val clearLocalState: () -> Unit = {}
) : AuthRepository {

    private val _session = MutableStateFlow(tokens.email)
    override val session: StateFlow<String?> = _session.asStateFlow()

    private val _profileComplete = MutableStateFlow(tokens.profileComplete)
    override val profileComplete: StateFlow<Boolean> = _profileComplete.asStateFlow()

    init {
        // If the backend ever refuses our refresh token (revoked, expired, rotated by another
        // client) the transport clears the pair. The stored e-mail used to survive that, so the
        // app stayed on the home screen with every list empty — indistinguishable from "my data
        // is gone". Dropping the session sends the user to the sign-in screen instead, and one
        // sign-in brings the whole account back, because nothing was ever deleted server-side.
        scope.launch {
            tokens.sessionLost.collect { lost ->
                if (!lost) return@collect
                cache?.clear()
                clearLocalState()
                _session.value = null
                _profileComplete.value = false
            }
        }
    }

    override suspend fun isEmailRegistered(email: String): Boolean {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return false
        return try {
            api.checkEmail(trimmed).exists
        } catch (_: IOException) {
            // Offline: don't block the form, the submit path reports the real error.
            false
        } catch (_: ApiException) {
            false
        }
    }

    override suspend fun signIn(email: String, password: String): AuthResult {
        val normalized = email.trim().lowercase()
        if (!EMAIL_RE.matches(normalized)) {
            return AuthResult.Failure("Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult.Failure("Password must be at least 6 characters")
        }
        return try {
            val response = api.signIn(normalized, password)
            adopt(response, profileCompleteByDefault = true)
            AuthResult.Success
        } catch (error: ApiException) {
            AuthResult.Failure(error.message)
        } catch (_: IOException) {
            AuthResult.Failure(OFFLINE)
        }
    }

    override suspend fun signUp(email: String, password: String): AuthResult {
        val normalized = email.trim().lowercase()
        if (!EMAIL_RE.matches(normalized)) {
            return AuthResult.Failure("Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult.Failure("Password must be at least 6 characters")
        }
        return try {
            // The backend wants a display name at signup; the real one arrives from the
            // profile-setup step, exactly like the reference's placeholder handling.
            val response = api.signUp(normalized, password, normalized.substringBefore('@'))
            adopt(response, profileCompleteByDefault = false)
            AuthResult.Success
        } catch (error: ApiException) {
            AuthResult.Failure(error.message)
        } catch (_: IOException) {
            AuthResult.Failure(OFFLINE)
        }
    }

    override fun completeProfile() {
        tokens.profileComplete = true
        _profileComplete.value = true
    }

    override fun signOut() {
        val refresh = tokens.refreshToken
        // Forget the session immediately: signing out must never depend on the network.
        tokens.clear()
        cache?.clear()
        clearLocalState()
        _session.value = null
        _profileComplete.value = false
        if (refresh != null) {
            // Best effort: revoke the refresh token server-side (`DELETE FROM sessions`).
            scope.launch { runCatching { api.logout(refresh) } }
        }
    }

    private fun adopt(response: AuthResponse, profileCompleteByDefault: Boolean) {
        val user = response.user
        val email = user?.email
        tokens.saveTokens(response.accessToken, response.refreshToken)
        tokens.saveIdentity(
            id = user?.id,
            email = email,
            fullName = user?.fullName,
            username = user?.username,
            avatarUrl = user?.avatarUrl
        )
        if (!tokens.profileComplete && profileCompleteByDefault) {
            // An existing account with a username has already been through onboarding.
            tokens.profileComplete = !user?.username.isNullOrBlank()
        }
        _session.value = email
        _profileComplete.value = tokens.profileComplete
    }

    private companion object {
        const val OFFLINE = "Cannot reach the server — check your connection"
        val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
