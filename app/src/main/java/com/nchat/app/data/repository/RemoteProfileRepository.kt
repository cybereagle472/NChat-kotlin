package com.nchat.app.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import com.nchat.app.data.model.UserProfile
import com.nchat.app.network.NChatApi
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The signed-in account against the Rust backend (`GET/PATCH /users/me`).
 *
 * Edits are optimistic: the field changes in the UI immediately and the request follows on
 * [scope]. The avatar stays a local [ImageBitmap] for now — uploading it needs the media
 * route plus a re-encode step, which is the next slice of this work.
 */
class RemoteProfileRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val scope: CoroutineScope
) : ProfileRepository {

    private val _profile = MutableStateFlow(
        UserProfile(
            fullName = tokens.fullName.orEmpty(),
            handle = tokens.username?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
            about = "",
            phoneNumber = ""
        )
    )
    override val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _avatar = MutableStateFlow<ImageBitmap?>(null)
    override val avatar: StateFlow<ImageBitmap?> = _avatar.asStateFlow()

    override suspend fun refresh() {
        val remote = runCatching { api.myProfile().profile }.getOrNull() ?: return
        apply(remote)
    }

    override suspend fun isUsernameAvailable(username: String): Boolean {
        val value = username.trim().lowercase()
        if (value.length < 3) return false
        return runCatching { api.usernameAvailable(value).available }.getOrDefault(false)
    }

    override fun updateName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _profile.update { it.copy(fullName = trimmed) }
        push(fullName = trimmed)
    }

    override fun updateAbout(about: String) {
        val trimmed = about.trim()
        _profile.update { it.copy(about = trimmed) }
        push(bio = trimmed)
    }

    override fun updateUsername(username: String) {
        val sanitized = username.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (sanitized.length < 3) return
        _profile.update { it.copy(handle = "@$sanitized") }
        push(username = sanitized)
    }

    override fun update(fullName: String, username: String, about: String) {
        val name = fullName.trim()
        val handle = username.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' }
        val bio = about.trim()
        _profile.update {
            it.copy(
                fullName = name.ifEmpty { it.fullName },
                handle = if (handle.length >= 3) "@$handle" else it.handle,
                about = bio
            )
        }
        push(
            fullName = name.ifEmpty { null },
            username = handle.takeIf { it.length >= 3 },
            bio = bio
        )
    }

    override fun setAvatar(image: ImageBitmap?) {
        _avatar.value = image
    }

    private fun push(fullName: String? = null, username: String? = null, bio: String? = null) {
        scope.launch {
            val remote = runCatching {
                api.updateMe(fullName = fullName, username = username, bio = bio)
            }.getOrNull()
            // A rejected edit (username taken, empty patch) snaps the UI back to the server's
            // truth instead of leaving the optimistic value on screen.
            val authoritative = remote?.profile ?: runCatching { api.myProfile().profile }.getOrNull()
            if (authoritative != null) apply(authoritative)
        }
    }

    private fun apply(remote: UserDto) {
        _profile.value = UserProfile(
            fullName = remote.fullName.orEmpty(),
            handle = remote.username?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
            about = remote.bio.orEmpty(),
            phoneNumber = ""
        )
        tokens.saveIdentity(
            id = remote.id,
            email = remote.email,
            fullName = remote.fullName,
            username = remote.username,
            avatarUrl = remote.avatarUrl
        )
    }
}
