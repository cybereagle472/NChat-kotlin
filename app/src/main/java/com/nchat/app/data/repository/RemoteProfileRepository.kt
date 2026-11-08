package com.nchat.app.data.repository

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.nchat.app.data.model.UserProfile
import com.nchat.app.network.NChatApi
import com.nchat.app.network.TokenStore
import com.nchat.app.network.UserDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * The signed-in account against the Rust backend (`GET/PATCH /users/me`).
 *
 * Edits are optimistic: the field changes in the UI immediately and the request follows on
 * [scope]. The avatar is re-encoded to PNG, uploaded through `POST /media/upload`, and its
 * URL stored on the account with `PATCH /users/me`, so every client sees the same photo.
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
            phoneNumber = "",
            // Straight from the last known identity, so the header/avatar shows the real photo
            // on the very first frame after a cold start instead of waiting for the network.
            avatarUrl = tokens.avatarUrl
        )
    )
    override val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _avatar = MutableStateFlow<ImageBitmap?>(null)
    private var avatarOwnerId: String? = tokens.userId
    private val setupAndRefresh = Mutex()
    override val avatar: StateFlow<ImageBitmap?> = _avatar.asStateFlow()

    override suspend fun refresh() = setupAndRefresh.withLock {
        val userId = tokens.userId
        val remote = try {
            api.myProfile().profile
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (remote != null && userId != null && tokens.userId == userId && remote.id == userId) apply(remote)
    }

    override suspend fun isUsernameAvailable(username: String): Boolean {
        val value = username.trim().lowercase()
        if (value.length < 3) return false
        return api.usernameAvailable(value).available
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

    override suspend fun completeSetup(
        fullName: String,
        username: String,
        about: String,
        image: ImageBitmap?
    ) = setupAndRefresh.withLock {
        val userId = tokens.userId ?: throw IOException("You are not signed in")
        val name = fullName.trim()
        val handle = username.trim().lowercase()
        val photoUrl = profileSetupAvatarUrl(image) { selectedImage ->
            val bytes = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { sink ->
                    if (!selectedImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, sink)) {
                        throw IOException("Could not prepare the profile photo")
                    }
                    sink.toByteArray()
                }
            }
            api.uploadMedia("avatar.png", "image/png", bytes).url
        }
        val remote = api.updateMe(
            fullName = name,
            username = handle,
            bio = about.trim(),
            avatarUrl = photoUrl
        ).profile ?: throw IOException("The server did not confirm your profile")
        if (tokens.userId != userId || remote.id != userId || remote.fullName?.trim() != name ||
            remote.username?.lowercase() != handle || remote.avatarUrl.orEmpty() != photoUrl) {
            throw IOException("The server did not confirm your profile")
        }
        apply(remote)
        _avatar.value = image
    }

    override fun setAvatar(image: ImageBitmap?) {
        _avatar.value = image
        if (image == null) return
        val bytes = runCatching {
            ByteArrayOutputStream().use { sink ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, sink)
                sink.toByteArray()
            }
        }.getOrNull() ?: return

        scope.launch {
            val media = runCatching { api.uploadMedia("avatar.png", "image/png", bytes) }.getOrNull()
                ?: return@launch
            val remote = runCatching { api.updateMe(avatarUrl = media.url) }.getOrNull()
            remote?.profile?.let { apply(it) }
        }
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
        if (avatarOwnerId != remote.id) {
            _avatar.value = null
            avatarOwnerId = remote.id
        }
        _profile.value = UserProfile(
            fullName = remote.fullName.orEmpty(),
            handle = remote.username?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
            about = remote.bio.orEmpty(),
            phoneNumber = "",
            avatarUrl = remote.avatarUrl
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
