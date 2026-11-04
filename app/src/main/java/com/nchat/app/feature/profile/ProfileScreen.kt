package com.nchat.app.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.ImageCrop
import com.nchat.app.data.model.UserProfile

private val Violet = Color(0xFF7C3AED)
private val Slate900 = Color(0xFF0F172A)
private val Slate800 = Color(0xFF1E293B)
private val Slate500 = Color(0xFF64748B)
private val Slate400 = Color(0xFF94A3B8)

/**
 * The `profile` section of `Settings.tsx`: big avatar with the camera upload button
 * (which routes through [ImageCropperScreen]) and the inline-editable Name and About
 * fields, with Username / Phone shown read-only.
 */
@Composable
fun ProfileScreen(
    profile: UserProfile,
    avatar: ImageBitmap?,
    onBack: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateAbout: (String) -> Unit,
    onAvatarChanged: (ImageBitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editingName by remember { mutableStateOf(false) }
    var editingAbout by remember { mutableStateOf(false) }
    var nameDraft by remember(profile.fullName) { mutableStateOf(profile.fullName) }
    var aboutDraft by remember(profile.about) { mutableStateOf(profile.about) }
    var pendingCrop by remember { mutableStateOf<ImageBitmap?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingCrop = ImageCrop.loadFromUri(context, uri)
        }
    }

    pendingCrop?.let { image ->
        ImageCropperScreen(
            image = image,
            onCancel = { pendingCrop = null },
            onCrop = { cropped ->
                onAvatarChanged(cropped)
                pendingCrop = null
            }
        )
        return
    }

    NcBackdrop(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // `settings-subheader dark`: light bar, dark icons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xF2FFFFFF))
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF1F5F9))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Slate800,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Profile",
                    color = Slate900,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(modifier = Modifier.size(36.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NcSpace.md, vertical = NcSpace.lg)
            ) {
                // Avatar + camera button
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = NcSpace.xl)
                ) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ProfileAvatar(
                            name = profile.fullName,
                            image = avatar,
                            size = 106.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
                            )
                            .border(3.dp, Color.White, CircleShape)
                            .clickable {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = "Change photo",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Name and About card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xD9FFFFFF))
                        .border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(20.dp))
                ) {
                    ProfileField(
                        icon = Icons.Rounded.Person,
                        label = "Name",
                        editing = editingName,
                        value = profile.fullName,
                        draft = nameDraft,
                        onDraftChange = { nameDraft = it },
                        onEdit = {
                            nameDraft = profile.fullName
                            editingName = true
                        },
                        onSave = {
                            onUpdateName(nameDraft)
                            editingName = false
                        },
                        onCancel = {
                            nameDraft = profile.fullName
                            editingName = false
                        }
                    )

                    FieldDivider()

                    ProfileField(
                        icon = Icons.Rounded.Edit,
                        label = "About",
                        editing = editingAbout,
                        value = profile.about.ifBlank { "Add a bio" },
                        draft = aboutDraft,
                        onDraftChange = { if (it.length <= 150) aboutDraft = it },
                        multiline = true,
                        counter = "${aboutDraft.length}/150",
                        onEdit = {
                            aboutDraft = profile.about
                            editingAbout = true
                        },
                        onSave = {
                            onUpdateAbout(aboutDraft)
                            editingAbout = false
                        },
                        onCancel = {
                            aboutDraft = profile.about
                            editingAbout = false
                        }
                    )

                    FieldDivider()

                    ReadOnlyField(
                        icon = Icons.Rounded.Lock,
                        label = "Username",
                        value = profile.handle
                    )

                    FieldDivider()

                    ReadOnlyField(
                        icon = Icons.Rounded.Lock,
                        label = "Phone",
                        value = profile.phoneNumber
                    )
                }

                Text(
                    text = "Your profile is how friends find and recognise you on NChat.",
                    color = Slate400,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.lg, bottom = NcSpace.xxl)
                )
            }
        }
    }
}

/** Circular avatar: the cropped photo when set, otherwise the gradient initials. */
@Composable
fun ProfileAvatar(
    name: String,
    image: ImageBitmap?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.trim().take(2).uppercase().ifBlank { "?" },
                color = Color.White,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProfileField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    editing: Boolean,
    value: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    multiline: Boolean = false,
    counter: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md),
        verticalAlignment = Alignment.Top
    ) {
        FieldIcon(icon)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(text = label, color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Medium)

            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = !multiline,
                    textStyle = TextStyle(fontSize = 15.sp, color = Slate800),
                    cursorBrush = SolidColor(Violet),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(min = if (multiline) 52.dp else 30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (counter != null) {
                        Text(
                            text = counter,
                            color = Slate400,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }

                    SmallPill(label = "Save", filled = true, onClick = onSave)
                    Box(modifier = Modifier.size(8.dp))
                    SmallPill(label = "Cancel", filled = false, onClick = onCancel)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = value,
                        color = Slate800,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onEdit),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit $label",
                            tint = Violet,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md),
        verticalAlignment = Alignment.Top
    ) {
        FieldIcon(icon, muted = true)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(text = label, color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                color = Slate500,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun FieldIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    muted: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (muted) Color(0xFFF1F5F9) else Color(0xFFEDE9FE)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (muted) Slate500 else Violet,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SmallPill(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (filled) {
                    Brush.linearGradient(NcTheme.extra.brandGradient)
                } else {
                    SolidColor(Color(0xFFF1F5F9))
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (filled) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(13.dp)
                        .padding(end = 0.dp)
                )
                Box(modifier = Modifier.size(4.dp))
            } else {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Slate500,
                    modifier = Modifier.size(13.dp)
                )
                Box(modifier = Modifier.size(4.dp))
            }
            Text(
                text = label,
                color = if (filled) Color.White else Slate500,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FieldDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 62.dp)
            .height(1.dp)
            .background(Color(0xFFF1F5F9))
    )
}
