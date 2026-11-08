package com.nchat.app.feature.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import com.nchat.app.network.ApiException
import com.nchat.app.core.util.ImageCrop
import com.nchat.app.feature.profile.ImageCropperScreen
import com.nchat.app.feature.profile.ProfileAvatar
import com.nchat.app.data.ServiceLocator

private val Slate900 = Color(0xFF0F172A)
private val Slate500 = Color(0xFF64748B)
private val Slate400 = Color(0xFF94A3B8)
private val Violet600 = Color(0xFF7C3AED)

/**
 * `ProfileSetup.tsx`: the "One last step / Create your profile" screen shown right after
 * sign-up — optional photo, default avatar, name, username and bio.
 */
@Composable
fun ProfileSetupScreen(
    email: String,
    onComplete: suspend (fullName: String, username: String, bio: String, photo: ImageBitmap?) -> Unit,
    modifier: Modifier = Modifier
) {
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var pendingCrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadingPhoto by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                loadingPhoto = true
                val decoded = withContext(Dispatchers.IO) { ImageCrop.loadFromUri(context, uri) }
                loadingPhoto = false
                if (decoded == null) error = "Could not open this photo. Please choose another."
                else pendingCrop = decoded
            }
        }
    }
    var submitting by remember { mutableStateOf(false) }
    var checkingUsername by remember { mutableStateOf(false) }
    var usernameAvailable by remember { mutableStateOf<Boolean?>(null) }

    var availabilityError by remember { mutableStateOf(false) }

    LaunchedEffect(username) {
        val value = username
        usernameAvailable = null
        availabilityError = false
        if (value.length < 3) {
            checkingUsername = false
            return@LaunchedEffect
        }
        checkingUsername = true
        delay(350)
        try {
            usernameAvailable = ServiceLocator.profileRepository.isUsernameAvailable(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The server will still validate the username when the profile is saved.
            availabilityError = true
        } finally {
            checkingUsername = false
        }
    }

    fun submit() {
        when {
            fullName.trim().isEmpty() -> error = "Please enter your full name"
            username.length < 3 -> error = "Username must be at least 3 characters"
            usernameAvailable == false -> error = "Username is already taken"
            loadingPhoto -> error = "Please wait for your photo to load"
            submitting -> Unit
            else -> {
                error = ""
                submitting = true
                scope.launch {
                    try {
                        onComplete(
                            fullName.trim(), username,
                            bio.trim().ifBlank { "Hey there! I am using NChat." }, photo
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: ApiException) {
                        error = failure.message.ifBlank { "Could not save your profile. Please try again." }
                    } catch (_: IOException) {
                        error = "Could not save your profile. Check your connection and try again."
                    } catch (_: Exception) {
                        error = "Could not save your profile. Please try again."
                    } finally {
                        submitting = false
                    }
                }
            }
        }
    }

    pendingCrop?.let { image ->
        ImageCropperScreen(
            image = image,
            onCancel = { pendingCrop = null },
            onCrop = { cropped -> photo = cropped; pendingCrop = null; error = "" }
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFF5F3FF), Color(0xFFFAF5FF), Color(0xFFFDF4FF))
                )
            )
    ) {
        SetupBlobs()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(32.dp))
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xCCFFFFFF))
                    .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(32.dp))
                    .padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF8B5CF6),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "ONE LAST STEP",
                        color = Violet600,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }

                Text(
                    text = "Create your profile",
                    color = Slate900,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Text(
                    text = "This is how friends will see you on NChat",
                    color = Slate500,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 22.dp)
                )

                // This is the actual photo (or built-in default), not a decorative preset.
                Box(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    ProfileAvatar(
                        name = fullName.ifBlank { email },
                        image = photo,
                        size = 112.dp,
                        modifier = Modifier.clickable(enabled = !submitting && !loadingPhoto) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape)
                            .background(Violet600)
                            .clickable(enabled = !submitting && !loadingPhoto) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = "Choose profile photo", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = if (loadingPhoto) "Opening photo…" else if (photo == null) "Default avatar · tap to add a photo" else "Tap to change photo",
                    color = Slate500,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                if (photo != null) {
                    Text(
                        text = "Use default avatar",
                        color = Violet600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !submitting) { photo = null }
                            .padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                if (error.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFEF2F2))
                            .border(1.dp, Color(0xFFFEE2E2), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = error,
                            color = Color(0xFFDC2626),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                SetupField(
                    label = "Full name",
                    value = fullName,
                    onValueChange = { fullName = it },
                    placeholder = "Jane Doe",
                    icon = Icons.Rounded.Person,
                    enabled = !submitting,
                    modifier = Modifier.padding(top = 18.dp)
                )

                SetupField(
                    label = "Username",
                    value = username,
                    onValueChange = { value ->
                        username = value.lowercase().filter { it.isLetterOrDigit() || it == '_' }
                        usernameAvailable = null
                    },
                    placeholder = "janedoe",
                    icon = Icons.Rounded.Email,
                    enabled = !submitting,
                    modifier = Modifier.padding(top = 14.dp),
                    trailing = {
                        when {
                            checkingUsername -> CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFCBD5E1)
                            )

                            username.length >= 3 && usernameAvailable == true -> Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Username available",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(17.dp)
                            )

                            usernameAvailable == false -> Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFFFEF2F2))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Taken",
                                    color = Color(0xFFEF4444),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            else -> Unit
                        }
                    }
                )

                if (availabilityError) {
                    Text(
                        text = "Could not check availability; we will check when saving.",
                        color = Slate500, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }

                if (username.isNotEmpty() && username.length < 3) {
                    Text(
                        text = "At least 3 characters",
                        color = Slate400,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bio",
                        color = Slate500,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${bio.length}/150",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                }

                BasicTextField(
                    value = bio,
                    onValueChange = { if (it.length <= 150) bio = it },
                    enabled = !submitting,
                    textStyle = TextStyle(fontSize = 14.sp, color = Slate900),
                    cursorBrush = SolidColor(Violet600),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = 62.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (bio.isEmpty()) {
                        Text(
                            text = "Hey there! I am using NChat.",
                            color = Slate400,
                            fontSize = 14.sp
                        )
                    }
                    it()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF7C3AED),
                                    Color(0xFF9333EA),
                                    Color(0xFFC026D3)
                                )
                            )
                        )
                        .clickable(enabled = !submitting, onClick = { submit() }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Setting up your account...",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        } else {
                            Text(
                                text = "Start chatting",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(17.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "Signed in as $email",
                color = Slate400,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SetupBlobs() {
    val transition = rememberInfiniteTransition(label = "setupBlobs")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "setupBlobDrift"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val shift = drift * 24.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x808E44FE), Color(0x008E44FE)),
                center = Offset(size.width * 0.1f, size.height * 0.08f)
            ),
            radius = 150.dp.toPx(),
            center = Offset(size.width * 0.1f - shift, size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x80F0ABFC), Color(0x00F0ABFC)),
                center = Offset(size.width * 0.95f, size.height * 0.12f)
            ),
            radius = 150.dp.toPx(),
            center = Offset(size.width * 0.95f + shift, size.height * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x667DD3FC), Color(0x007DD3FC)),
                center = Offset(size.width * 0.25f, size.height * 0.95f)
            ),
            radius = 150.dp.toPx(),
            center = Offset(size.width * 0.25f, size.height * 0.95f + shift)
        )
    }
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Slate500,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(18.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Slate900),
                    cursorBrush = SolidColor(Violet600),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isEmpty()) {
                    Text(text = placeholder, color = Slate400, fontSize = 14.sp)
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 6.dp)) { trailing() }
            }
        }
    }
}
