package com.nchat.app.feature.status

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcGlassRow
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.util.MediaPreparer
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.StatusViewer
import com.nchat.app.data.repository.StatusPost
import com.nchat.app.ui.components.NcHeaderAction
import com.nchat.app.ui.components.NcTabHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Status tab.
 *
 * A status is a person, not a post: WhatsApp draws one ring per contact and steps through that
 * contact's updates in the viewer. This screen used to draw **one row per update**, so five
 * stories from one friend filled the tab with five identical rows, and the viewer walked the
 * whole feed instead of one person's story. The rows are now runs: newest first, unseen before
 * seen, each one saying how many updates it holds and how long they have left.
 */
@Composable
fun StatusScreen(
    statuses: List<StatusUpdate>,
    listState: LazyListState,
    myAvatarUrl: String? = null,
    /** Progress/error of the post being composed. */
    postState: StatusPost = StatusPost.Idle,
    onCreateText: (text: String, background: String?, font: String?) -> Unit = { _, _, _ -> },
    onCreateMedia: (
        bytes: ByteArray,
        mimeType: String,
        contentType: String,
        caption: String,
        fileName: String,
        durationMs: Long
    ) -> Unit = { _, _, _, _, _, _ -> },
    onClearPostError: () -> Unit = {},
    onDeleteStatus: (String) -> Unit = {},
    mutedAuthorIds: Set<String> = emptySet(),
    onMuteAuthor: (String) -> Unit = {},
    onUnmuteAuthor: (String) -> Unit = {},
    loadViewers: suspend (String) -> List<StatusViewer> = { emptyList() },
    /** Opens a chat with the author so the reply is a real message. */
    onReplyToAuthor: (StatusUpdate) -> Unit = {},
    onMarkViewed: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    val mine = remember(statuses) { statuses.filter { it.isMine }.sortedBy { it.timestamp } }
    val runs = remember(statuses) { buildRuns(statuses) }

    var viewing by remember { mutableStateOf<List<StatusUpdate>?>(null) }
    var startIndex by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var viewersFor by remember { mutableStateOf<StatusUpdate?>(null) }
    var viewers by remember { mutableStateOf<List<StatusViewer>>(emptyList()) }
    var menuFor by remember { mutableStateOf<StatusRun?>(null) }

    LaunchedEffect(viewersFor) {
        val target = viewersFor ?: return@LaunchedEffect
        viewers = loadViewers(target.id)
    }

    fun openRun(items: List<StatusUpdate>) {
        val ordered = items.sortedBy { it.timestamp }
        if (ordered.isEmpty()) return
        // Always start at the first update this device has not seen, the way WhatsApp does.
        startIndex = ordered.indexOfFirst { !it.viewed }.takeIf { it >= 0 } ?: 0
        viewing = ordered
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NcTabHeader(
                title = "Status",
                actions = {
                    NcHeaderAction(Icons.Rounded.CameraAlt, "New status", onClick = { composing = true })
                }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                item(key = "my-status") {
                    MyStatusRow(
                        items = mine,
                        avatarUrl = mine.lastOrNull()?.avatarUrl ?: myAvatarUrl,
                        onOpen = { if (mine.isEmpty()) composing = true else openRun(mine) },
                        onAdd = { composing = true },
                        onViewers = { status -> viewersFor = status }
                    )
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

                if (runs.isEmpty()) {
                    item(key = "recent-empty") {
                        EmptyUpdates()
                    }
                }

                items(items = runs, key = { it.authorId }) { run ->
                    StatusRunRow(
                        run = run,
                        muted = run.authorId in mutedAuthorIds,
                        onOpen = { openRun(run.items) },
                        onLongPress = { menuFor = run }
                    )
                }

                item(key = "status-footer") {
                    Spacer(modifier = Modifier.size(NcSpace.md))
                    Text(
                        text = "Status updates disappear 24 hours after they are posted",
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.textMuted
                    )
                }
            }
        }

        // One viewer for one person's run: closing it returns to the list, never to the next
        // person — matching the rows, which are per person.
        viewing?.let { items ->
            StatusViewer(
                statuses = items,
                startIndex = startIndex,
                onViewed = onMarkViewed,
                onDelete = { status ->
                    onDeleteStatus(status.id)
                    viewing = null
                },
                onClose = { viewing = null },
                onReply = { status ->
                    viewing = null
                    onReplyToAuthor(status)
                }
            )
        }

        if (composing) {
            StatusComposer(
                postState = postState,
                onClearPostError = onClearPostError,
                onClose = { composing = false },
                onPostText = { text, background, font ->
                    onCreateText(text, background, font)
                },
                onPostMedia = { bytes, mime, type, caption, fileName, duration ->
                    onCreateMedia(bytes, mime, type, caption, fileName, duration)
                }
            )
        }
    }

    menuFor?.let { run ->
        val muted = run.authorId in mutedAuthorIds
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(run.name) },
            text = {
                Column {
                    Text(
                        text = if (run.unseen) {
                            "${run.items.size} new update${if (run.items.size == 1) "" else "s"}"
                        } else {
                            "${run.items.size} update${if (run.items.size == 1) "" else "s"}, all seen"
                        },
                        color = NcTheme.extra.textSecondary
                    )
                    TextButton(
                        onClick = {
                            menuFor = null
                            if (muted) onUnmuteAuthor(run.authorId) else onMuteAuthor(run.authorId)
                            NcToaster.show(
                                if (muted) "Updates from ${run.name} are back" else "Updates from ${run.name} muted"
                            )
                        }
                    ) {
                        Text(if (muted) "Unmute updates" else "Mute updates")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = run.items
                    menuFor = null
                    openRun(items)
                }) { Text("View") }
            },
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } }
        )
    }

    viewersFor?.let { status ->
        StatusViewersDialog(
            count = viewers.size.takeIf { it > 0 } ?: status.viewCount,
            viewers = viewers,
            onDismiss = { viewersFor = null }
        )
    }
}

// --------------------------------------------------------------------- rows

/** One person's batch of updates. */
private data class StatusRun(
    val authorId: String,
    val name: String,
    val avatarUrl: String?,
    val items: List<StatusUpdate>
) {
    val newest: StatusUpdate = items.maxByOrNull { it.timestamp } ?: items.first()
    val unseen: Boolean = items.any { !it.viewed }
}

private fun buildRuns(statuses: List<StatusUpdate>): List<StatusRun> =
    statuses
        .filterNot { it.isMine }
        .groupBy { it.authorId.ifBlank { it.authorName } }
        .map { (id, items) ->
            StatusRun(
                authorId = id,
                name = items.first().authorName,
                avatarUrl = items.first().avatarUrl,
                items = items.sortedBy { it.timestamp }
            )
        }
        // Unseen first, then whoever posted most recently — the order the tab is read in.
        .sortedWith(compareByDescending<StatusRun> { it.unseen }.thenByDescending { it.newest.timestamp })

@Composable
private fun MyStatusRow(
    items: List<StatusUpdate>,
    avatarUrl: String?,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onViewers: (StatusUpdate) -> Unit
) {
    val extra = NcTheme.extra
    val newest = items.lastOrNull()
    NcGlassRow(onClick = onOpen) {
        Box(contentAlignment = Alignment.Center) {
            NcAvatar(
                name = items.firstOrNull()?.authorName ?: "My status",
                imageUrl = items.lastOrNull()?.avatarUrl ?: avatarUrl,
                size = 56.dp
            )
            if (items.isNotEmpty()) {
                // A live ring means "you have updates out there".
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, Color.White, CircleShape)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add to my status",
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
                text = if (items.isEmpty()) {
                    "Tap to add status update"
                } else {
                    val left = remainingLabel(newest?.expiresAt ?: 0L)
                    "${items.size} update${if (items.size == 1) "" else "s"}" +
                        if (left != null) " · $left" else ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (newest != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onViewers(newest) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Visibility,
                    contentDescription = "Who saw this",
                    tint = extra.textMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = newest.viewCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.textMuted
                )
            }
        }
    }
}

@Composable
private fun StatusRunRow(
    run: StatusRun,
    muted: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val extra = NcTheme.extra

    Box {
        NcGlassRow(onClick = onOpen, onLongClick = onLongPress) {
            Box(contentAlignment = Alignment.Center) {
                NcAvatar(
                    name = run.name,
                    imageUrl = run.avatarUrl,
                    size = 56.dp
                )
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .border(
                            width = if (run.unseen) 2.5.dp else 2.dp,
                            brush = if (run.unseen) {
                                Brush.linearGradient(extra.brandGradient)
                            } else {
                                Brush.linearGradient(listOf(extra.divider, extra.divider))
                            },
                            shape = CircleShape
                        )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (run.unseen) extra.textPrimary else extra.textSecondary,
                    fontWeight = if (run.unseen) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${run.items.size} update${if (run.items.size == 1) "" else "s"}")
                        append(" · ")
                        append(TimeFormat.shortAgo(run.newest.timestamp))
                        if (muted) append(" · muted")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (run.unseen) extra.textSecondary else extra.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyUpdates() {
    val extra = NcTheme.extra
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NcSpace.md, vertical = NcSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0x148B5CF6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = null,
                tint = extra.textTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = "No updates yet",
            color = extra.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = NcSpace.sm)
        )
        Text(
            text = "When a contact posts a status, it appears here for 24 hours.",
            color = extra.textTertiary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** "21h left" / "45m left", for the author's own rows. Null once it has expired. */
internal fun remainingLabel(expiresAt: Long): String? {
    if (expiresAt <= 0L) return null
    val left = expiresAt - System.currentTimeMillis()
    if (left <= 0L) return null
    val minutes = left / 60_000L
    return when {
        minutes >= 60 -> "${minutes / 60}h left"
        minutes >= 1 -> "${minutes}m left"
        else -> "under a minute left"
    }
}

// ----------------------------------------------------------------- composer

private val TEXT_BACKGROUNDS = listOf(
    "#7C3AED" to Color(0xFF7C3AED),
    "#0EA5E9" to Color(0xFF0EA5E9),
    "#10B981" to Color(0xFF10B981),
    "#F59E0B" to Color(0xFFF59E0B),
    "#EF4444" to Color(0xFFEF4444),
    "#111827" to Color(0xFF111827)
)

private val TEXT_FONTS = listOf(
    "sans" to (FontFamily.SansSerif to "Sans"),
    "serif" to (FontFamily.Serif to "Serif"),
    "mono" to (FontFamily.Monospace to "Mono")
)

private fun colorOf(hex: String?): Color =
    TEXT_BACKGROUNDS.firstOrNull { it.first == hex }?.second ?: Color(0xFF7C3AED)

private fun fontOf(style: String?): FontFamily = when (style) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

/** A picked photo or video, already read into memory and ready to upload. */
private data class PickedStatusMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val preview: ImageBitmap?
)

/**
 * The status composer.
 *
 * Three things can be posted — text, a photo, a video — and all three are composed here rather
 * than in an `AlertDialog`: a status is a full-screen thing, so the writing, the colour, the
 * picture and the caption are chosen against the background they will be seen on.
 */
@Composable
private fun StatusComposer(
    postState: StatusPost,
    onClearPostError: () -> Unit,
    onClose: () -> Unit,
    onPostText: (text: String, background: String?, font: String?) -> Unit,
    onPostMedia: (
        bytes: ByteArray,
        mimeType: String,
        contentType: String,
        caption: String,
        fileName: String,
        durationMs: Long
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(TEXT_BACKGROUNDS.first().first) }
    var font by remember { mutableStateOf(TEXT_FONTS.first().first) }
    var media by remember { mutableStateOf<PickedStatusMedia?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    val busy = postState is StatusPost.Busy
    val failure = (postState as? StatusPost.Failed)?.message

    /** Reads a picked Uri into [PickedStatusMedia], off the main thread. */
    fun prepare(uri: Uri, captured: Boolean = false) {
        preparing = true
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = context.contentResolver.getType(uri)
                        ?.takeIf { it.isNotBlank() }
                        ?: if (captured) "image/jpeg" else "image/jpeg"
                    val isVideo = mime.startsWith("video/")
                    if (isVideo) {
                        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        if (raw.size > MAX_STATUS_BYTES) return@runCatching null
                        val duration = videoDurationMs(context, uri)
                        PickedStatusMedia(
                            bytes = raw,
                            mimeType = mime,
                            fileName = "status.mp4",
                            isVideo = true,
                            durationMs = duration,
                            preview = videoFrame(context, uri)
                        )
                    } else {
                        // Photos take the same path a chat photo does: downscaled, EXIF
                        // stripped, so a 12 MP camera shot is not uploaded raw.
                        val preparedImage = MediaPreparer.prepare(context, uri, "status.jpg")
                        val bytes = preparedImage?.bytes
                            ?: context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        if (bytes.size > MAX_STATUS_BYTES) return@runCatching null
                        PickedStatusMedia(
                            bytes = bytes,
                            mimeType = preparedImage?.mimeType ?: mime,
                            fileName = preparedImage?.fileName ?: "status.jpg",
                            isVideo = false,
                            durationMs = 0L,
                            preview = android.graphics.BitmapFactory
                                .decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        )
                    }
                }.getOrNull()
            }
            preparing = false
            if (prepared == null) {
                NcToaster.show("That file is too large to post as a status")
            } else {
                media = prepared
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) prepare(uri) }

    val cameraCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = cameraTarget
        if (captured && uri != null) prepare(uri, captured = true) else NcToaster.show("No photo taken")
    }

    fun takePhoto() {
        val directory = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
        val target = java.io.File(directory, "status_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        }.getOrNull()
        if (uri == null) {
            NcToaster.show("The camera is not available on this device")
            return
        }
        cameraTarget = uri
        runCatching { cameraCapture.launch(uri) }.onFailure {
            NcToaster.show("The camera is not available on this device")
        }
    }

    /*
     * Success is "busy, and then not busy without a failure": the repository has no separate
     * success event, and inventing one only to close a screen would be another thing to keep in
     * sync. A failure keeps the composer open with the caption and the media still in place, so
     * the retry is one tap.
     */
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(postState) {
        when (postState) {
            is StatusPost.Busy -> wasBusy = true
            is StatusPost.Idle -> if (wasBusy) {
                wasBusy = false
                onClose()
            }
            is StatusPost.Failed -> wasBusy = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(NcSpace.lg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NcSpace.md, bottom = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                        .clickable(enabled = !busy, onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "New status",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = NcSpace.sm)
                )
                val canPost = !busy && !preparing && (media != null || text.isNotBlank())
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (canPost) Brush.linearGradient(NcTheme.extra.brandGradient)
                            else Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF)))
                        )
                        .clickable(enabled = canPost) {
                            // A retry starts from a clean slate, so an old error cannot sit under
                            // a fresh upload.
                            onClearPostError()
                            val picked = media
                            if (picked == null) {
                                onPostText(text.trim(), background, font)
                            } else {
                                onPostMedia(
                                    picked.bytes,
                                    picked.mimeType,
                                    if (picked.isVideo) "video" else "image",
                                    text.trim(),
                                    picked.fileName,
                                    picked.durationMs
                                )
                            }
                        }
                        .padding(horizontal = NcSpace.md, vertical = 8.dp)
                ) {
                    Text(
                        text = "Post",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ------------------------------------------------------------ preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (media == null) {
                            Brush.linearGradient(listOf(colorOf(background), colorOf(background)))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val picked = media
                if (picked == null) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NcSpace.lg),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            color = Color.White,
                            fontFamily = fontOf(font),
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "Type a status",
                                    color = Color(0x99FFFFFF),
                                    fontSize = 22.sp,
                                    textAlign = TextAlign.Center,
                                    fontFamily = fontOf(font),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            inner()
                        }
                    )
                } else {
                    if (picked.preview != null) {
                        Image(
                            bitmap = picked.preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (picked.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0x66000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Video · ${formatDuration(picked.durationMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(NcSpace.sm)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x88000000))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Caption for a photo or a video, underneath the preview.
            if (media != null) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.sm)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x1AFFFFFF))
                        .padding(horizontal = NcSpace.md, vertical = NcSpace.sm),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    maxLines = 3,
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("Add a caption…", color = Color(0x99FFFFFF), fontSize = 13.sp)
                        }
                        inner()
                    }
                )
            }

            // ---------------------------------------------------------- controls
            if (media == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.md),
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    TEXT_BACKGROUNDS.forEach { (hex, color) ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (hex == background) 3.dp else 1.dp,
                                    color = if (hex == background) Color.White else Color(0x33FFFFFF),
                                    shape = CircleShape
                                )
                                .clickable { background = hex }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    TEXT_FONTS.forEach { (id, spec) ->
                        val selected = id == font
                        Text(
                            text = spec.second,
                            color = if (selected) Color.White else Color(0x99FFFFFF),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = spec.first,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                                .clickable { font = id }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.md),
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    TextButton(
                        onClick = {
                            media = null
                            onClearPostError()
                        }
                    ) {
                        Text("Remove", color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NcSpace.sm, bottom = NcSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComposerAction(
                    icon = Icons.Rounded.CameraAlt,
                    label = "Camera",
                    enabled = !busy,
                    onClick = { media = null; takePhoto() }
                )
                ComposerAction(
                    icon = Icons.Rounded.Collections,
                    label = "Gallery",
                    enabled = !busy,
                    onClick = {
                        galleryPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                if (preparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Text(
                        text = "Preparing…",
                        color = Color(0xCCFFFFFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            // --------------------------------------------------------- progress
            if (busy) {
                val progress = (postState as? StatusPost.Busy)?.progress
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = NcSpace.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Posting your status…",
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp
                        )
                        if (progress != null) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(NcSpace.xs))
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            color = NcTheme.extra.brandGradient[1],
                            trackColor = Color(0x33FFFFFF)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            color = NcTheme.extra.brandGradient[1],
                            trackColor = Color(0x33FFFFFF)
                        )
                    }
                }
            }

            if (failure != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33EF4444))
                        .padding(horizontal = NcSpace.md, vertical = NcSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = failure,
                        color = Color(0xFFFECACA),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearPostError) {
                        Text("Dismiss", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

    }
}

@Composable
private fun ComposerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NcSpace.md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White else Color(0x66FFFFFF),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0x66FFFFFF),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ------------------------------------------------------------------- helpers

/** Status media is capped: 24 MB of video is already a long story, and 96 MB is a bug. */
private const val MAX_STATUS_BYTES = 48 * 1024 * 1024

private fun videoDurationMs(context: android.content.Context, uri: Uri): Long = runCatching {
    val retriever = android.media.MediaMetadataRetriever()
    retriever.setDataSource(context, uri)
    val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
    retriever.release()
    ms
}.getOrDefault(0L)

/** A frame from the clip, so the preview is the actual video and not a black rectangle. */
private fun videoFrame(context: android.content.Context, uri: Uri): ImageBitmap? = runCatching {
    val retriever = android.media.MediaMetadataRetriever()
    retriever.setDataSource(context, uri)
    val frame = retriever.getFrameAtTime(1_000_000L)
    retriever.release()
    frame?.asImageBitmap()
}.getOrNull()

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = viewer.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NcTheme.extra.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = TimeFormat.shortAgo(viewer.viewedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NcTheme.extra.textMuted
                                )
                            }
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
