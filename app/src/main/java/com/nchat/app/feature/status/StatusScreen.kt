package com.nchat.app.feature.status

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.MediaPreparer
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.StatusViewer
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader

@Composable
fun StatusScreen(
    statuses: List<StatusUpdate>,
    listState: LazyListState,
    onCreateStatus: (String) -> Unit = {},
    /** My own photo, so the "My status" ring shows it the same way a contact's does. */
    myAvatarUrl: String? = null,
    /** Photo status: bytes, mime type, caption, file name — uploaded then posted. */
    onCreatePhotoStatus: (ByteArray, String, String, String) -> Unit = { _, _, _, _ -> },
    mutedAuthorIds: Set<String> = emptySet(),
    onMuteAuthor: (String) -> Unit = {},
    onUnmuteAuthor: (String) -> Unit = {},
    /** Who watched one of my updates (`GET /status/:id/views`). */
    loadViewers: suspend (String) -> List<StatusViewer> = { emptyList() },
    /** `POST /status/:id/view` — the author's viewer count depends on this being sent. */
    onMarkViewed: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    val mine = statuses.firstOrNull { it.isMine }
    val others = remember(statuses) { statuses.filterNot { it.isMine } }

    var viewingIndex by remember { mutableStateOf<Int?>(null) }
    var locallyViewed by remember { mutableStateOf(emptySet<String>()) }
    var composing by remember { mutableStateOf(false) }
    var viewersFor by remember { mutableStateOf<StatusUpdate?>(null) }
    var viewers by remember { mutableStateOf<List<StatusViewer>>(emptyList()) }

    LaunchedEffect(viewersFor) {
        val target = viewersFor ?: return@LaunchedEffect
        viewers = loadViewers(target.id)
    }
    fun openStatus(status: StatusUpdate) {
        viewingIndex = statuses.indexOfFirst { it.id == status.id }.takeIf { it >= 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NcTabHeader(
                title = "Status",
                actions = {
                    NcHeaderAction(Icons.Rounded.CameraAlt, "Camera", onClick = { composing = true })
                    NcHeaderAction(Icons.Rounded.Edit, "Text status", onClick = { composing = true })
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                item(key = "my-status") {
                    NcGlassRow(onClick = { if (mine != null) openStatus(mine) else composing = true }) {
                        Box {
                            NcAvatar(
                                name = mine?.authorName ?: "My status",
                                imageUrl = mine?.avatarUrl ?: myAvatarUrl,
                                size = 56.dp
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .border(2.dp, Color.White, CircleShape)
                                    .clickable { composing = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "My status",
                                style = MaterialTheme.typography.titleMedium,
                                color = extra.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tap to add status update",
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item(key = "recent-header") {
                    Text(
                        text = "Recent updates",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = extra.textTertiary,
                        modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.sm)
                    )
                }

                items(items = others, key = { it.id }) { status ->
                    NcGlassRow(onClick = { openStatus(status) }) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(
                                    if (status.viewed || status.id in locallyViewed) {
                                        Brush.linearGradient(listOf(extra.divider, extra.divider))
                                    } else {
                                        Brush.linearGradient(extra.brandGradient)
                                    }
                                )
                                .padding(3.dp)
                        ) {
                            NcAvatar(
                                name = status.authorName,
                                emoji = status.avatarEmoji,
                                imageUrl = status.avatarUrl,
                                size = 56.dp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = status.authorName,
                                style = MaterialTheme.typography.titleMedium,
                                color = extra.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = status.caption,
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = TimeFormat.shortAgo(status.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.textMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                item(key = "status-footer") {
                    Spacer(modifier = Modifier.size(NcSpace.md))
                    Text(
                        text = "Status updates disappear after 24 hours",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.textMuted
                    )
                }
            }
        }

        viewingIndex?.let { start ->
            StatusViewer(
                statuses = statuses,
                startIndex = start,
                onViewed = { id ->
                    locallyViewed = locallyViewed + id
                    /*
                     * Tell the server.
                     *
                     * `StatusRepository.markViewed` existed but nothing ever called it, so a
                     * friend's story was only ever "viewed" on this device: their viewer count
                     * never moved and the ring never cleared for them. `POST /status/:id/view`
                     * is the whole feature (`worker/src/routes/status.ts:109`).
                     */
                    onMarkViewed(id)
                },
                onDismiss = { viewingIndex = null },
                onShowViewers = { status -> viewersFor = status },
                mutedAuthorIds = mutedAuthorIds,
                onMuteAuthor = onMuteAuthor,
                onUnmuteAuthor = onUnmuteAuthor
            )
        }

        viewersFor?.let { status ->
            StatusViewersDialog(
                count = status.viewCount,
                viewers = viewers,
                onDismiss = { viewersFor = null }
            )
        }

        if (composing) {
            StatusComposerDialog(
                onDismiss = { composing = false },
                onCreate = { text ->
                    composing = false
                    onCreateStatus(text)
                },
                onCreatePhoto = { bytes, mimeType, caption, fileName ->
                    composing = false
                    onCreatePhotoStatus(bytes, mimeType, caption, fileName)
                }
            )
        }
    }
}

/**
 * The status composer. A caption on its own becomes a text status; picking a photo turns it
 * into an image status (`content_type: "image"` + `content_url`), which is what the reference
 * offers and what `POST /status` expects. The picture is re-encoded through [MediaPreparer]
 * first, so a 12 MP camera shot is not uploaded raw.
 */
@Composable
private fun StatusComposerDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onCreatePhoto: (bytes: ByteArray, mimeType: String, caption: String, fileName: String) -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<MediaPreparer.Prepared?>(null) }
    var preparing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        preparing = true
        photo = MediaPreparer.prepare(context, uri, "status.jpg")
            ?: runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) null else MediaPreparer.Prepared(bytes, null, "status.jpg", mime)
            }.getOrNull()
        preparing = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (photo == null) "New status" else "New photo status") },
        text = {
            Column {
                if (photo == null) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("What's on your mind?") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = NcTheme.extra.textMuted.copy(alpha = 0.4f)
                        )
                    )
                    TextButton(
                        enabled = !preparing,
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(if (preparing) "  Preparing…" else "  Add a photo")
                    }
                } else {
                    val preview = remember(photo) {
                        runCatching {
                            android.graphics.BitmapFactory.decodeByteArray(
                                photo!!.bytes, 0, photo!!.bytes.size
                            )?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (preview != null) {
                        androidx.compose.foundation.Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Add a caption (optional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = NcSpace.sm),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = NcTheme.extra.textMuted.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (photo == null) draft.isNotBlank() else !preparing,
                onClick = {
                    val picked = photo
                    if (picked == null) {
                        onCreate(draft.trim())
                    } else {
                        onCreatePhoto(picked.bytes, picked.mimeType, draft.trim(), picked.fileName)
                    }
                }
            ) {
                Text("Share", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Who watched one of my updates, newest first (`GET /status/:id/views`). */
@Composable
private fun StatusViewersDialog(
    count: Int,
    viewers: List<StatusViewer>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count > 0) "Viewed by $count" else "No views yet") },
        text = {
            if (viewers.isEmpty()) {
                Text(
                    text = if (count > 0) {
                        "The server counted views but returned no names."
                    } else {
                        "Nobody has opened this status yet."
                    },
                    color = NcTheme.extra.textSecondary
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(NcSpace.sm)) {
                    items(items = viewers, key = { it.id }) { viewer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                        ) {
                            NcAvatar(
                                name = viewer.name,
                                imageUrl = viewer.avatarUrl,
                                size = 36.dp
                            )
                            Text(
                                text = viewer.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NcTheme.extra.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
