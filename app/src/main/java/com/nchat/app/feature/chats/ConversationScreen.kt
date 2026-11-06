@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.nchat.app.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.network.MediaFiles
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.nchat.app.core.audio.VoiceRecorder
import com.nchat.app.core.audio.VoiceWaveform
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.util.MediaPreparer
import com.nchat.app.core.util.PickedFile
import com.nchat.app.core.util.readPickedFile
import com.nchat.app.core.designsystem.NcTypingDots
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.ServiceLocator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import com.nchat.app.data.local.StarredMessage
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.feature.groups.GroupInfoScreen
import com.nchat.app.feature.profile.ProfileViewScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(
    chatId: String,
    chatTitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoCallVideo: Boolean? = null,
    viewModel: ConversationViewModel = viewModel(
        key = chatId,
        factory = ConversationViewModel.factory(
            chatId,
            chatTitle,
            ServiceLocator.chatRepository,
            ServiceLocator.drafts
        )
    )
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()

    /*
     * Liveness fallback.
     *
     * While the realtime socket is up, every message arrives as an event and this does
     * nothing. The moment it is not — a network handover, a slept socket, a dropped frame —
     * an open conversation re-reads itself every few seconds, so the screen is never more than
     * a beat behind the server instead of silently frozen until something else triggers a
     * refresh. Three seconds is short enough to feel live and long enough to be invisible to
     * the battery; the loop stops the instant the socket is back and on leaving the screen.
     */
    val connection by ServiceLocator.connection.collectAsStateWithLifecycle()
    /*
     * The device's own internet state.
     *
     * The fallback poll below is a network round trip every three seconds; with no radio it is
     * three seconds of guaranteed failure and a little battery, forever. Offline there is
     * nothing to poll for anyway — the socket's reconnect (and its resync) is what catches up
     * the moment the network is back.
     */
    val deviceOnline by ServiceLocator.network.collectAsStateWithLifecycle()
    LaunchedEffect(chatId, connection, deviceOnline) {
        if (!deviceOnline) return@LaunchedEffect
        if (connection == com.nchat.app.network.ConnectionState.Connected) return@LaunchedEffect
        while (true) {
            delay(3_000)
            ServiceLocator.chatRepository.refreshConversation(chatId)
        }
    }
    val activeMessageId by viewModel.activeMessageId.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    // Plain HashMap on purpose: bubble bounds are only read when a menu opens, and a
    // snapshot-backed map would invalidate readers on every layout pass while scrolling.
    val anchors = remember { HashMap<String, Rect>() }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var reactionTargetId by remember { mutableStateOf<String?>(null) }
    var callKind by remember {
        mutableStateOf(
            autoCallVideo?.let { video -> if (video) CallKind.Video else CallKind.Audio }
        )
    }
    var profileOpen by remember { mutableStateOf(false) }
    var groupInfoOpen by remember { mutableStateOf(false) }
    var forwardingId by remember { mutableStateOf<String?>(null) }
    var mediaViewer by remember { mutableStateOf<Message?>(null) }
    /*
     * Chat-header power features: search, the ⋮ options, Starred messages, and selection mode.
     * Selection lives in the view model so a recomposition (a new message arriving, a rotation)
     * never drops what the user ticked.
     */
    var searching by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var starredOpen by remember { mutableStateOf(false) }
    var forwardingSelection by remember { mutableStateOf(false) }
    var bulkDeleteOpen by remember { mutableStateOf(false) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val starredAll by ServiceLocator.stars.items.collectAsStateWithLifecycle()
    val starredIds = remember(starredAll, chatId) {
        starredAll.filter { it.chatId == chatId }.map { it.messageId }.toSet()
    }
    // The ring around a jumped-to message fades by itself.
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(1_800)
            highlightId = null
        }
    }
    var editing by remember { mutableStateOf<Message?>(null) }
    val chats by ServiceLocator.chatRepository.chats.collectAsStateWithLifecycle()
    val groups by ServiceLocator.chatRepository.groups.collectAsStateWithLifecycle()
    val friendGraph by ServiceLocator.friendRepository.friends.collectAsStateWithLifecycle()
    // Blocking is a pause, not a deletion: the chat and every message stay exactly as they
    // were, the composer is replaced by an Unblock banner, and nothing is removed from the list.
    val blockedIds by ServiceLocator.friendRepository.blockedIds.collectAsStateWithLifecycle()
    val blockedPeople by ServiceLocator.friendRepository.blockedPeople.collectAsStateWithLifecycle()
    val blockedPerson = remember(blockedPeople, chatId) {
        blockedPeople.firstOrNull { it.id == chatId }
    }
    val isBlocked = chatId in blockedIds
    val groupFriends = remember(friendGraph) {
        friendGraph.filter { it.relation == FriendRelation.Friend }
    }
    val group = groups.firstOrNull { it.id == chatId }
    val isGroupChat = chat?.isGroup == true || group != null
    // Group chats name who is typing; direct chats keep the anonymous "typing…" label.
    // The name comes from the event's `sender_id`, resolved against the roster — it used to be
    // guessed from whoever spoke last, which credited the wrong person most of the time.
    val typingMember = remember(chat?.isTyping, chat?.typingUserId, isGroupChat, groupFriends) {
        if (chat?.isTyping == true && isGroupChat) {
            val who = chat?.typingUserId
            groupFriends.firstOrNull { it.id == who }?.name
                ?: group?.members?.firstOrNull { it.userId == who }?.name
                ?: groupFriends.firstOrNull()?.name
        } else {
            null
        }
    }

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // Attachments are real files: pick → read → upload → send (`POST /media/upload`).
    var pendingKind by remember { mutableStateOf<MessageKind?>(null) }
    fun deliverPicked(uri: Uri?) {
        val kind = pendingKind
        pendingKind = null
        if (uri == null || kind == null) return
        val picked = readPickedFile(context, uri)
        if (picked == null) {
            NcToaster.show("That file is too large (max 24 MB)")
            return
        }
        // Photos are re-encoded before upload: EXIF (GPS, device) is dropped, the file drops
        // from megabytes to a few hundred KB, and a thumbnail travels with it.
        val file = if (MediaPreparer.canCompress(picked.mimeType)) {
            val prepared = MediaPreparer.prepare(context, uri, picked.name)
            if (prepared == null) {
                picked
            } else {
                PickedFile(
                    name = prepared.fileName,
                    mimeType = prepared.mimeType,
                    bytes = prepared.bytes,
                    thumbnail = prepared.thumbnail
                )
            }
        } else if (kind == MessageKind.Video) {
            // A clip gets a poster frame so the bubble shows the video, not a black tile.
            picked.copy(thumbnail = MediaPreparer.videoPoster(context, uri))
        } else {
            picked
        }
        viewModel.onSendMedia(kind, file)
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> deliverPicked(uri) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> deliverPicked(uri) }
    val firstUnreadId by viewModel.firstUnreadId.collectAsStateWithLifecycle()
    val timeline = remember(messages, firstUnreadId) { buildTimeline(messages, firstUnreadId) }
    val typingRow = if (chat?.isTyping == true) 1 else 0
    val scope = rememberCoroutineScope()
    // Calls need the microphone (and camera for video) before the peer connection can open
    // them, so the call buttons ask first — see CallPermissions.kt.
    val callGate = rememberCallPermissionGate()
    // Auto-scroll only when the reader is parked at the bottom, or sent the message
    // themselves; otherwise new traffic must never yank the viewport away.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var didInitialScroll by remember { mutableStateOf(false) }

    LaunchedEffect(timeline.size, typingRow) {
        val last = timeline.lastIndex + typingRow
        if (last < 0) return@LaunchedEffect
        if (!didInitialScroll) {
            didInitialScroll = true
            // Opening a chat with unread messages parks the viewport on the resume marker.
            val marker = timeline.indexOfFirst { it is TimelineItem.UnreadDivider }
            listState.scrollToItem(if (marker >= 0) marker else last)
            return@LaunchedEffect
        }
        if (atBottom || messages.lastOrNull()?.isMine == true) {
            listState.animateScrollToItem(last)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(NcTheme.extra.bodyGradient))
        ) {
            if (selection.isEmpty()) {
                ConversationHeader(
                    chatTitle = chatTitle,
                    chat = chat,
                    groupMemberCount = group?.memberCount,
                    typingMember = typingMember,
                    offline = !deviceOnline,
                    onBack = onBack,
                    onStartCall = { video -> callKind = if (video) CallKind.Video else CallKind.Audio },
                    onSearch = {
                        optionsOpen = false
                        searching = true
                    },
                    onOptions = { optionsOpen = true },
                    onOpenProfile = {
                        if (chat?.isGroup == true || group != null) {
                            groupInfoOpen = true
                        } else {
                            profileOpen = true
                        }
                    }
                )
            } else {
                SelectionHeader(
                    count = selection.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllMessages,
                    onForward = { forwardingSelection = true },
                    onCopy = {
                        val body = messages
                            .filter { it.id in selection }
                            .joinToString("\n") { it.snippet() }
                            .trim()
                        if (body.isEmpty()) {
                            NcToaster.show("Nothing to copy")
                        } else {
                            clipboard.setText(AnnotatedString(body))
                            NcToaster.show(
                                if (selection.size == 1) "Message copied" else "${selection.size} messages copied"
                            )
                            viewModel.clearSelection()
                        }
                    },
                    onDelete = { bulkDeleteOpen = true }
                )
            }

            if (timeline.isEmpty()) {
                EmptyConversation(
                    title = chatTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = timeline,
                    key = { it.key },
                    // Reuse slots per row kind instead of tearing down composables.
                    contentType = { item ->
                        when (item) {
                            is TimelineItem.Separator -> 0
                            is TimelineItem.UnreadDivider -> 1
                            is TimelineItem.Bubble -> 2
                        }
                    }
                ) { item ->
                    when (item) {
                        is TimelineItem.Separator -> DateSeparatorPill(label = item.label)
                        is TimelineItem.UnreadDivider -> UnreadDivider()
                        is TimelineItem.Bubble -> {
                            val message = item.message
                            // Keyed lambdas keep each bubble skippable while the list scrolls.
                            val onLongPress = remember(message.id) {
                                { viewModel.onSelectMessage(message.id) }
                            }
                            val onMediaClick = remember(message.id) {
                                { mediaViewer = message }
                            }
                            val onRetrySend = remember(message.id) {
                                { viewModel.onRetrySend(message.id) }
                            }
                            val onSwipeReply = remember(message.id) {
                                { viewModel.quote(message.id) }
                            }
                            val onQuickReact = remember(message.id) {
                                { viewModel.reactTo(message.id, "\u2764\uFE0F") }
                            }
                            val onToggleSelect = remember(message.id) {
                                { viewModel.toggleSelection(message.id) }
                            }
                            Box(
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    anchors[message.id] = coordinates.boundsInWindow()
                                }
                            ) {
                                MessageBubble(
                                    message = message,
                                    onLongPress = onLongPress,
                                    onMediaClick = onMediaClick,
                                    onRetrySend = onRetrySend,
                                    onSwipeReply = onSwipeReply,
                                    onQuickReact = onQuickReact,
                                    starred = message.id in starredIds,
                                    selectionMode = selection.isNotEmpty(),
                                    selected = message.id in selection,
                                    onToggleSelect = onToggleSelect,
                                    highlighted = message.id == highlightId,
                                    showAuthor = isGroupChat,
                                    showAvatar = item.showSender
                                )
                            }
                        }
                    }
                }
                if (chat?.isTyping == true) {
                    item(key = "typing") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .shadow(1.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                                    .background(Color.White)
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (typingMember != null) {
                                        Text(
                                            text = "$typingMember is typing",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF7C3AED),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Box(modifier = Modifier.size(6.dp))
                                    }
                                    NcTypingDots()
                                }
                            }
                        }
                    }
                }
            }
            }

            if (isBlocked) {
                BlockedComposerBar(
                    name = blockedPerson?.name ?: chatTitle,
                    onUnblock = {
                        ServiceLocator.friendRepository.unblock(chatId)
                        NcToaster.show("Unblocked — you can pick up where you left off")
                    }
                )
            } else {
            ComposerBar(
                textFlow = viewModel.composerText,
                replyTo = replyTo,
                onTextChange = viewModel::onComposerChange,
                onSend = {
                    viewModel.onSend()
                    keyboard?.hide()
                },
                onCancelReply = viewModel::onCancelReply,
                onSendAttachment = { kind ->
                    keyboard?.hide()
                    when (kind) {
                        MessageKind.Photo -> {
                            pendingKind = MessageKind.Photo
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }

                        MessageKind.Video -> {
                            pendingKind = MessageKind.Video
                            mediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }

                        MessageKind.Document -> {
                            pendingKind = MessageKind.Document
                            documentPicker.launch(arrayOf("*/*"))
                        }

                        // Every option in the popup opens a real picker above.
                        else -> Unit
                    }
                },
                onSendVoice = viewModel::onSendVoice,
                onRecordingChange = { recording ->
                    ServiceLocator.chatRepository.setRecording(chatId, recording)
                }
            )
            }
        }

        AnimatedVisibility(
            visible = !atBottom && timeline.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = NcSpace.lg, bottom = 96.dp),
            enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f)
        ) {
            ScrollToLatestButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((timeline.lastIndex + typingRow).coerceAtLeast(0))
                    }
                }
            )
        }

    val activeMessage = activeMessageId?.let { id -> messages.firstOrNull { it.id == id } }
    if (activeMessageId != null && activeMessage != null) {
        MessageActionMenu(
            anchor = anchors[activeMessageId],
            showCopy = activeMessage.text.isNotBlank() && !activeMessage.deleted,
            showReply = !activeMessage.deleted,
            showEdit = canEditMessage(activeMessage),
            onEdit = {
                editing = activeMessage
                viewModel.onSelectMessage(null)
            },
            onDismiss = { viewModel.onSelectMessage(null) },
            onReact = viewModel::onReaction,
            reactedEmojis = activeMessage.reactions
                .filter { it.reactedByMe }
                .map { it.emoji }
                .toSet(),
            onShowAllReactions = {
                reactionTargetId = activeMessage.id
                viewModel.onSelectMessage(null)
            },
            onReply = viewModel::onReply,
            isStarred = activeMessage.id in starredIds,
            onStar = {
                ServiceLocator.stars.toggle(
                    StarredMessage(
                        chatId = chatId,
                        messageId = activeMessage.id,
                        authorName = activeMessage.authorName,
                        preview = activeMessage.snippet(),
                        at = activeMessage.timestamp
                    )
                )
                val wasStarred = activeMessage.id in starredIds
                viewModel.onSelectMessage(null)
                NcToaster.show(if (wasStarred) "Removed from starred" else "Message starred")
            },
            onSelect = { viewModel.startSelection(activeMessage.id) },
            onCopy = {
                clipboard.setText(AnnotatedString(activeMessage.text))
                viewModel.onSelectMessage(null)
                NcToaster.show("Message copied")
            },
            onForward = {
                if (activeMessage.deleted) {
                    NcToaster.show("This message was deleted")
                } else {
                    forwardingId = activeMessage.id
                }
                viewModel.onSelectMessage(null)
            },
            onDelete = {
                pendingDelete = activeMessage
                viewModel.onSelectMessage(null)
            }
        )
    }

        if (groupInfoOpen && group != null) {
            GroupInfoScreen(
                group = group,
                friends = groupFriends,
                selfName = ServiceLocator.profile.fullName,
                onBack = { groupInfoOpen = false },
                onRename = viewModel::onRenameGroup,
                onAddMembers = viewModel::onAddMembers,
                onLeave = {
                    groupInfoOpen = false
                    viewModel.onLeaveGroup()
                    onBack()
                },
                selfId = ServiceLocator.tokens.userId.orEmpty(),
                onRemoveMember = viewModel::onRemoveMember,
                onSetMemberRole = viewModel::onSetMemberRole,
                onDeleteGroup = viewModel::onDeleteGroup
            )
        }

        forwardingId?.let { messageId ->
            ForwardDialog(
                chats = chats,
                onDismiss = { forwardingId = null },
                onForward = { targets ->
                    viewModel.onForward(messageId, targets)
                    forwardingId = null
                    NcToaster.show(
                        if (targets.size == 1) "Forwarded to 1 chat" else "Forwarded to ${targets.size} chats"
                    )
                }
            )
        }

        if (profileOpen) {
            ProfileViewScreen(
                name = chatTitle,
                id = if (chat?.isGroup == true) "" else chatId,
                handle = chatTitle.lowercase().replace(" ", ""),
                about = if (chat?.isGroup == true) "Group conversation" else "Hey there! I am using NChat.",
                isOnline = chat?.isOnline == true,
                emoji = chat?.avatarEmoji,
                imageUrl = chat?.avatarUrl,
                onBack = { profileOpen = false },
                onMessage = { profileOpen = false },
                onCall = { video ->
                    profileOpen = false
                    callKind = if (video) CallKind.Video else CallKind.Audio
                }
            )
        }

        /*
         * Calls are NOT rendered here any more.
         *
         * They used to live inside the conversation: tapping the header opened a local overlay
         * that rang for 1.6 s and then declared itself connected, and the in-chat group variant
         * "rang" a roster that never heard about it. A call has to outlive the screen (you accept
         * it from the Chats tab, you stay on the line while you read another chat), so the state
         * machine lives in `CallRepository` and the overlay is drawn once by `HomeRoot`.
         */
        callKind?.let { kind ->
            // Group calls are not part of the reference backend's working set (the web app skips
            // them too); say so instead of opening a screen that cannot connect.
            if (chat?.isGroup == true || group != null) {
                LaunchedEffect(kind) { callKind = null }
                LaunchedEffect(Unit) {
                    NcToaster.show("Group calls aren't available yet")
                }
            } else {
                LaunchedEffect(kind) {
                    val video = kind == CallKind.Video
                    callKind = null
                    callGate(video) {
                        ServiceLocator.callRepository.dial(
                            personId = chatId,
                            name = chatTitle,
                            avatarUrl = chat?.avatarUrl,
                            video = video
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { doomed ->
        DeleteMessageDialog(
            canDeleteForEveryone = canDeleteForEveryone(doomed, isGroupChat),
            onDismiss = { pendingDelete = null },
            // The confirmation is the server's, not the tap's: `deleteMessage` reports what
            // actually happened (and puts the message back if the worker refused it).
            onDeleteForEveryone = {
                viewModel.onDeleteMessage(doomed.id, forEveryone = true)
                pendingDelete = null
            },
            onDeleteForMe = {
                viewModel.onDeleteMessage(doomed.id, forEveryone = false)
                pendingDelete = null
            }
        )
    }

    reactionTargetId?.let { targetId ->
        val target = messages.firstOrNull { it.id == targetId }
        ReactionPickerSheet(
            reactedEmojis = target?.reactions
                ?.filter { it.reactedByMe }
                ?.map { it.emoji }
                ?.toSet()
                .orEmpty(),
            onReact = { emoji ->
                viewModel.reactTo(targetId, emoji)
                reactionTargetId = null
            },
            onDismiss = { reactionTargetId = null }
        )
    }

    if (optionsOpen) {
        ChatOptionsSheet(
            onDismiss = { optionsOpen = false },
            onSearch = {
                optionsOpen = false
                searching = true
            },
            onStarred = {
                optionsOpen = false
                starredOpen = true
            }
        )
    }

    if (starredOpen) {
        StarredMessagesSheet(
            items = starredAll.filter { it.chatId == chatId },
            onDismiss = { starredOpen = false },
            onPick = { starred ->
                starredOpen = false
                val index = timeline.indexOfFirst {
                    it is TimelineItem.Bubble && it.message.id == starred.messageId
                }
                if (index >= 0) {
                    highlightId = starred.messageId
                    scope.launch { listState.animateScrollToItem(index) }
                } else {
                    NcToaster.show("That message is no longer loaded")
                }
            },
            onUnstar = { starred -> ServiceLocator.stars.unstar(starred.chatId, starred.messageId) }
        )
    }

    if (searching) {
        ChatSearchOverlay(
            messages = messages,
            onDismiss = { searching = false },
            onPick = { picked ->
                searching = false
                val index = timeline.indexOfFirst {
                    it is TimelineItem.Bubble && it.message.id == picked.id
                }
                if (index >= 0) {
                    highlightId = picked.id
                    scope.launch { listState.animateScrollToItem(index) }
                }
            }
        )
    }

    if (forwardingSelection) {
        ForwardDialog(
            chats = chats,
            onDismiss = { forwardingSelection = false },
            onForward = { targets ->
                val count = selection.size
                viewModel.forwardSelectedMessages(targets)
                forwardingSelection = false
                NcToaster.show(
                    if (count == 1) "Forwarded 1 message" else "Forwarded $count messages"
                )
            }
        )
    }

    if (bulkDeleteOpen) {
        DeleteMessagesDialog(
            count = selection.size,
            onDismiss = { bulkDeleteOpen = false },
            onDelete = { forEveryone ->
                val count = selection.size
                bulkDeleteOpen = false
                viewModel.deleteSelectedMessages(forEveryone)
                NcToaster.show(
                    if (forEveryone) {
                        if (count == 1) "Message deleted for everyone" else "$count messages deleted for everyone"
                    } else {
                        if (count == 1) "Message deleted" else "$count messages deleted"
                    }
                )
            }
        )
    }

    mediaViewer?.let { message ->
        val gallery = remember(messages) {
            messages.filter { it.kind == MessageKind.Photo || it.kind == MessageKind.Video }
        }
        MediaViewerDialog(
            items = gallery,
            initialIndex = gallery.indexOfFirst { it.id == message.id }.coerceAtLeast(0),
            onDismiss = { mediaViewer = null }
        )
    }

    editing?.let { message ->
        EditMessageDialog(
            original = message,
            onDismiss = { editing = null },
            onSave = { text ->
                viewModel.onEditMessage(message.id, text)
                editing = null
                NcToaster.show("Message edited")
            }
        )
    }
}

/** Own text messages can be edited for 15 minutes, matching `MessageActions.tsx`. */
private fun canEditMessage(message: Message): Boolean =
    message.isMine &&
        !message.deleted &&
        message.kind == MessageKind.Text &&
        System.currentTimeMillis() - message.timestamp < 15 * 60 * 1000L

/**
 * Full-screen photo / video viewer.
 *
 * Opening one photo gives you the whole chat's photos, exactly like WhatsApp: swipe sideways to
 * walk the conversation's media without closing the viewer. Photos zoom under a pinch and snap
 * back with a double tap; while a photo is zoomed the pager stops stealing horizontal drags so
 * panning works instead of flipping the page. Video keeps the real player and does not page.
 */
@Composable
private fun MediaViewerDialog(
    items: List<Message>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    var zoomed by remember { mutableStateOf(false) }
    // Every page starts un-zoomed, and leaving the zoomed page re-enables swiping.
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20F172A))
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = NcSpace.md, vertical = 92.dp)
            ) { page ->
                val message = items[page]
                val isVideo = message.kind == MessageKind.Video
                val source = message.mediaUrl ?: message.thumbnailUrl
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isVideo) {
                                Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B)))
                            } else {
                                Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isVideo && source != null -> VideoPlayer(
                            url = source,
                            modifier = Modifier.fillMaxSize()
                        )

                        source != null -> ZoomablePhoto(
                            url = source,
                            modifier = Modifier.fillMaxSize(),
                            onZoomChanged = { zoomed = it }
                        )

                        else -> Icon(
                            imageVector = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.Image,
                            contentDescription = null,
                            tint = Color(0xE6FFFFFF),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }

            val current = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close preview",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = NcSpace.md)
                ) {
                    Text(
                        text = current.authorName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(TimeFormat.clock(current.timestamp))
                            append(" · ")
                            append(if (current.kind == MessageKind.Video) "Video" else "Photo")
                        },
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                if (items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** A photo that pinches to zoom and double-taps back to fit. */
@Composable
private fun ZoomablePhoto(
    url: String,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }

    fun setScale(next: Float) {
        scale = next.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        onZoomChanged(scale > 1.01f)
    }

    Box(
        modifier = modifier
            .pointerInput(url) {
                detectTransformGestures { _, pan, zoom, _ ->
                    setScale(scale * zoom)
                    if (scale > 1.01f) offset += pan
                }
            }
            .pointerInput(url) {
                // A tap-only detector: `detectTransformGestures` above only consumes once the
                // finger has moved past touch slop, so a still double tap still reaches this.
                detectTapGestures(
                    onDoubleTap = { setScale(if (scale > 1.01f) 1f else 2.5f) }
                )
            }
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Photo",
            // Fit, not Crop: the full picture is the point of the viewer.
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

/**
 * A real video player for the full-screen viewer.
 *
 * `VideoView` is the standard platform player, but it takes a URL and opens it itself — with no
 * `Authorization` header, on a backend whose media reads are private (`GET /media/file/<key>`
 * answers only a signed-in caller). The same is true of the voice bubble, and it is fixed the
 * same way: [MediaFiles] fetches the file through the authenticated connection and the player is
 * handed a local path. A spinner covers the download, and a clip that cannot be fetched says so
 * instead of showing a black rectangle.
 */
@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    var file by remember(url) { mutableStateOf(MediaFiles.existingLocal(url)) }
    var failure by remember(url) { mutableStateOf<String?>(null) }
    // A plain holder, not state: assigning during composition would recompose for nothing.
    val view = remember(url) { arrayOfNulls<VideoView>(1) }

    LaunchedEffect(url) {
        if (file != null) return@LaunchedEffect
        MediaFiles.local(url).fold(
            onSuccess = { downloaded -> file = downloaded },
            onFailure = { error ->
                failure = error.message ?: "the download failed"
                NcToaster.show("Couldn't play this video — $failure")
            }
        )
    }

    DisposableEffect(url) {
        onDispose {
            runCatching { view[0]?.stopPlayback() }
            view[0] = null
        }
    }

    val local = file
    when {
        failure != null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = failure ?: "",
                color = Color(0xE6FFFFFF),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(NcSpace.lg)
            )
        }

        local == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }

        else -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.fromFile(local))
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        start()
                    }
                    setOnErrorListener { _, _, _ ->
                        NcToaster.show("Couldn't play this video")
                        true
                    }
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    view[0] = this
                }
            }
        )
    }
}

/** Compose-state edit sheet: prefilled body, Save disabled until something changes. */
@Composable
private fun EditMessageDialog(
    original: Message,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember(original.id) { mutableStateOf(original.text) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = NcSpace.xxl)
                    .fillMaxWidth()
                    .shadow(20.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(NcSpace.lg)
            ) {
                Text(
                    text = "Edit message",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
                )
                Box(
                    modifier = Modifier
                        .padding(top = NcSpace.md)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 1_024) draft = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF1E293B)),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NcSpace.lg),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    val canSave = draft.isNotBlank() && draft.trim() != original.text.trim()
                    Text(
                        text = "Save",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canSave) Color(0xFF7C3AED) else Color(0xFFCBD5E1),
                        modifier = Modifier
                            .padding(start = NcSpace.sm)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = canSave) { onSave(draft) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** Centered "unread messages" marker: where the conversation was left off. */
@Composable
private fun UnreadDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0x338B5CF6))
        )
        Text(
            text = "Unread messages",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF7C3AED),
            modifier = Modifier
                .padding(horizontal = NcSpace.sm)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1F8B5CF6))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0x338B5CF6))
        )
    }
}

/** Floating glass pill that returns the reader to the newest message. */
@Composable
private fun ScrollToLatestButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(42.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Color(0x140F172A), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Jump to latest",
            tint = Color(0xFF7C3AED),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Prompt shown in a conversation that has no messages yet. */
@Composable
private fun EmptyConversation(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = NcSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(NcTheme.extra.brandGradient)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubble,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Text(
            text = "Start the conversation",
            color = Color(0xFF1E293B),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = NcSpace.md)
        )
        Text(
            text = "Say hello to $title",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Whether "delete for everyone" is offered.
 *
 * The two backends do not agree, and the UI has to follow each of them: a **group** message can
 * be recalled for an hour (`groups.ts:513-521` rejects anything older with a 400), while a
 * **1:1** message has no window at all — `messages.ts:247` only checks that the caller is the
 * sender. The old code applied the group's hour to both, so a direct message older than an hour
 * was unrecallable on the phone while the web app happily recalled it.
 */
private fun canDeleteForEveryone(message: Message, isGroup: Boolean): Boolean =
    message.isMine &&
        !message.deleted &&
        (!isGroup || System.currentTimeMillis() - message.timestamp < 60 * 60 * 1000L)

/**
 * `GroupMessageActions` delete confirmation: pick "Delete for Everyone" (own message,
 * under an hour), "Delete for Me", or back out.
 */
@Composable
private fun DeleteMessageDialog(
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(NcSpace.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEE2E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "Delete Message?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NcTheme.extra.textSecondary,
                    modifier = Modifier.padding(top = NcSpace.md)
                )
                Text(
                    text = if (canDeleteForEveryone) {
                        "Choose how you want to delete this message"
                    } else {
                        "This message will be deleted for you only"
                    },
                    fontSize = 13.sp,
                    color = NcTheme.extra.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = NcSpace.xs, bottom = NcSpace.lg)
                )

                if (canDeleteForEveryone) {
                    DialogButton(
                        label = "Delete for Everyone",
                        background = Color(0xFFEF4444),
                        contentColor = Color.White,
                        onClick = onDeleteForEveryone
                    )
                }
                DialogButton(
                    label = "Delete for Me",
                    background = Color.Transparent,
                    contentColor = Color(0xFFDC2626),
                    border = Color(0xFFFECACA),
                    onClick = onDeleteForMe
                )
                DialogButton(
                    label = "Cancel",
                    background = Color.Transparent,
                    contentColor = NcTheme.extra.textSecondary,
                    border = Color(0xFFE2E8F0),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    background: Color,
    contentColor: Color,
    border: Color? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NcSpace.sm)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(
                if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(12.dp)) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConversationHeader(
    chatTitle: String,
    chat: Chat?,
    groupMemberCount: Int?,
    typingMember: String?,
    onBack: () -> Unit,
    onStartCall: (video: Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onSearch: () -> Unit = {},
    onOptions: () -> Unit = {},
    /** The device has no internet right now — see `NetworkMonitor`. */
    offline: Boolean = false
) {
    val isActivity = !offline && (typingMember != null || chat?.isTyping == true)
    val status = when {
        // No radio: nothing on screen is live, and saying "online" would be a guess from
        // whatever the last refresh said. WhatsApp says this same thing in the header.
        offline -> "waiting for network"
        typingMember != null -> "$typingMember typing…"
        chat?.isTyping == true -> "typing…"
        chat?.isGroup == true -> groupMemberCount?.let { "$it members · tap for info" } ?: "group chat"
        chat?.isOnline == true -> "online"
        else -> "last seen recently"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
            .shadow(8.dp)
            .statusBarsPadding()
            .padding(horizontal = NcSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenProfile)
                .padding(end = NcSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
        NcAvatar(
            name = chatTitle,
            emoji = chat?.avatarEmoji,
            imageUrl = chat?.avatarUrl,
            size = 40.dp,
            onGradient = true
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chatTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status,
                    color = if (isActivity) Color(0xFF86EFAC) else Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isActivity) {
                    NcTypingDots(
                        color = Color(0xFF86EFAC),
                        dotSize = 4.dp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        }

        HeaderAction(Icons.Rounded.Search, "Search in chat", onClick = onSearch)
        HeaderAction(Icons.Rounded.Videocam, "Video call", onClick = { onStartCall(true) })
        HeaderAction(Icons.Rounded.Call, "Voice call", onClick = { onStartCall(false) })
        HeaderAction(Icons.Rounded.MoreVert, "More options", onClick = onOptions)
    }
}

/**
 * The header while messages are ticked: count on the left, actions on the right.
 *
 * Everything the bulk actions need is here — forward, copy, delete, select-all — so selection
 * mode never needs a menu of its own.
 */
@Composable
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
            .shadow(8.dp)
            .statusBarsPadding()
            .padding(horizontal = NcSpace.sm, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel selection",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = if (count == 1) "1 selected" else "$count selected",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )
        if (count > 0) {
            HeaderAction(Icons.Rounded.Forward, "Forward", onClick = onForward)
            HeaderAction(Icons.Rounded.ContentCopy, "Copy", onClick = onCopy)
        }
        HeaderAction(Icons.Rounded.SelectAll, "Select all", onClick = onSelectAll)
        HeaderAction(Icons.Rounded.Delete, "Delete", onClick = onDelete)
    }
}

/** "Delete for me" or "delete for everyone" for a multi-selection. */
@Composable
private fun DeleteMessagesDialog(
    count: Int,
    onDismiss: () -> Unit,
    onDelete: (forEveryone: Boolean) -> Unit
) {
    val extra = NcTheme.extra
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(vertical = NcSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (count == 1) "Delete message?" else "Delete $count messages?",
                color = extra.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Delete for everyone also removes them from the other side.",
                color = extra.textTertiary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = NcSpace.lg, vertical = 6.dp)
            )
            DialogChoice("Delete for me") { onDelete(false) }
            DialogChoice("Delete for everyone", destructive = true) { onDelete(true) }
            DialogChoice("Cancel") { onDismiss() }
        }
    }
}

@Composable
private fun DialogChoice(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (destructive) Color(0xFFDC2626) else NcTheme.extra.textPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NcSpace.lg, vertical = 13.dp)
    )
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * What replaces the composer while a contact is blocked.
 *
 * The messages above stay exactly where they were — nothing is deleted when you block, so
 * unblocking drops you back into the same conversation at the same message (WhatsApp,
 * Telegram and Messenger all behave this way). New messages from them are held back by
 * [com.nchat.app.data.repository.RemoteChatRepository] and reappear on the next load.
 */
@Composable
private fun BlockedComposerBar(name: String, onUnblock: () -> Unit) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = NcSpace.lg, vertical = NcSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        Icon(
            imageVector = Icons.Rounded.Block,
            contentDescription = null,
            tint = extra.textTertiary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "You blocked $name",
                color = extra.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Your chat and history are kept. Unblock to continue.",
                color = extra.textTertiary,
                fontSize = 11.sp
            )
        }
        Text(
            text = "Unblock",
            color = Color(0xFF7C3AED),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF3E8FF))
                .clickable(onClick = onUnblock)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ComposerBar(
    textFlow: StateFlow<String>,
    replyTo: Message?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onSendAttachment: (MessageKind) -> Unit,
    /**
     * @param waveform the recorded amplitude as a JSON array (`VoiceWaveform`), stored in
     *   `thumbnail_url` so the bubble — here and on the web — draws the real shape of the clip.
     */
    onSendVoice: (
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String,
        waveform: String?
    ) -> Unit,
    /**
     * Live "recording a voice message" state for the other side
     * (`POST /messages/recording` / `POST /groups/:id/recording`).
     *
     * The repository has had `setRecording` all along and the incoming `recording` events were
     * already rendered — but nothing ever *sent* them, so a peer never saw the mic indicator.
     */
    onRecordingChange: (Boolean) -> Unit = {}
) {
    // Collected here, not in the screen: typing then only recomposes the composer.
    val text by textFlow.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val recorder = remember { VoiceRecorder(appContext) }
    var recordDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Recording only ever starts from the gesture, so a grant arriving mid-gesture is
        // treated as "hold the mic again" rather than silently capturing nothing.
        recordDenied = !granted
        if (!granted) NcToaster.show("Microphone access is needed for voice messages")
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            onRecordingChange(false)
        }
    }
    var mediaOpen by remember { mutableStateOf(false) }
    var voicePhase by remember { mutableStateOf<VoicePhase?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var cancelProgress by remember { mutableStateOf(0f) }
    var lockProgress by remember { mutableStateOf(0f) }
    val bars = remember { mutableStateListOf<Float>() }
    /** Raw `MediaRecorder` amplitudes, one per tick — the waveform the bubble is drawn from. */
    val clipSamples = remember { mutableListOf<Int>() }
    /** Loudest sample so far, so the live bars are scaled to this clip and not to the device. */
    var clipPeak by remember { mutableStateOf(0) }

    val density = LocalDensity.current
    val lockThreshold = with(density) { 56.dp.toPx() }
    val cancelThreshold = with(density) { 96.dp.toPx() }
    val minVoiceMs = 1000L

    fun discardVoice() {
        recorder.cancel()
        voicePhase = null
        elapsedMs = 0L
        cancelProgress = 0f
        lockProgress = 0f
        bars.clear()
        clipSamples.clear()
        clipPeak = 0
        onRecordingChange(false)
    }

    fun sendVoice() {
        val clip = recorder.stop()
        val duration = if (elapsedMs > 0L) elapsedMs else 0L
        // Sampled before `discardVoice()` wipes them.
        val waveform = VoiceWaveform.json(clipSamples.toList())
        discardVoice()
        if (clip == null) {
            NcToaster.show("Couldn't record — check the microphone and try again")
            return
        }
        // Never send 0:00: the label is what the other side shows, and a held-but-instant tap
        // is already filtered above by `minVoiceMs`.
        val label = formatVoiceDuration(duration.coerceAtLeast(1_000L))
        onSendVoice(clip.bytes, clip.mimeType, clip.fileName, label, waveform)
    }

    // Tell the other side the moment the recorder is live, and only then.
    LaunchedEffect(voicePhase) {
        val phase = voicePhase
        onRecordingChange(phase != null && phase != VoicePhase.Paused)
    }

    // Tick the timer and grow the waveform only while the recorder is live.
    LaunchedEffect(voicePhase) {
        val phase = voicePhase
        if (phase == null || phase == VoicePhase.Paused) return@LaunchedEffect
        while (true) {
            delay(100)
            elapsedMs += 100
            /*
             * `getMaxAmplitude()` is the loudest sample since the previous read, so one read per
             * tick is one real amplitude. The strip used to draw `Random.nextFloat()` — a
             * decorative animation that had nothing to do with what was being said, and nothing
             * to do with the waveform the other side saw, because there was none.
             */
            val amplitude = recorder.peakAmplitude()
            clipSamples.add(amplitude)
            if (amplitude > clipPeak) clipPeak = amplitude
            val level = if (clipPeak > 0) {
                (amplitude.toFloat() / clipPeak).coerceIn(0.08f, 1f)
            } else {
                0.08f
            }
            bars.add(level)
            if (bars.size > 20) bars.removeAt(0)
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (mediaOpen) 45f else 0f,
        animationSpec = tween(220),
        label = "plusRotation"
    )
    val hasText = text.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md),
        verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        AnimatedVisibility(
            visible = mediaOpen,
            enter = fadeIn(tween(120)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(120))
        ) {
            MediaPopup(
                onPick = { kind ->
                    mediaOpen = false
                    onSendAttachment(kind)
                }
            )
        }

        AnimatedVisibility(
            visible = replyTo != null,
            enter = fadeIn(tween(120)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(120))
        ) {
            replyTo?.let { quoted -> ReplyComposerStrip(quoted = quoted, onCancel = onCancelReply) }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
        // The input row stays composed even while recording: the mic button owns the
        // drag gesture, and a node that leaves composition would drop the pointer.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (voicePhase != null) 0f else 1f)
                .shadow(6.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(NcTheme.extra.composerGradient))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x26FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .rotate(rotation)
                        .clickable { mediaOpen = !mediaOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (mediaOpen) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = "Attach",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Type a message...",
                            color = Color(0x99FFFFFF),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        maxLines = 4
                    )
                }

                AnimatedContent(
                    targetState = hasText,
                    transitionSpec = {
                        (scaleIn(tween(150), initialScale = 0.7f) + fadeIn(tween(150))) togetherWith
                            (scaleOut(tween(120), targetScale = 0.7f) + fadeOut(tween(120)))
                    },
                    label = "sendButton"
                ) { showSend ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showSend) Color.White else Color(0x33FFFFFF))
                            .then(
                                if (!showSend) {
                                    Modifier.pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val startX = down.position.x
                                            val startY = down.position.y
                                            elapsedMs = 0L
                                            bars.clear()
                                            clipSamples.clear()
                                            clipPeak = 0
                                            cancelProgress = 0f
                                            lockProgress = 0f
                                            val granted = ContextCompat.checkSelfPermission(
                                                appContext,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                                return@awaitEachGesture
                                            }
                                            if (!recorder.start()) {
                                                NcToaster.show("Microphone unavailable")
                                                return@awaitEachGesture
                                            }
                                            voicePhase = VoicePhase.Holding
                                            var locked = false
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: break
                                                if (!change.pressed) break
                                                if (!locked) {
                                                    val lift = startY - change.position.y
                                                    val slideLeft = startX - change.position.x
                                                    lockProgress = (lift / lockThreshold)
                                                        .coerceIn(0f, 1f)
                                                    cancelProgress = (slideLeft / cancelThreshold)
                                                        .coerceIn(0f, 1f)
                                                    if (lift >= lockThreshold) {
                                                        locked = true
                                                        lockProgress = 0f
                                                        cancelProgress = 0f
                                                        voicePhase = VoicePhase.Locked
                                                    }
                                                }
                                                change.consume()
                                            }
                                            if (!locked) {
                                                val cancelled = cancelProgress >= 0.9f
                                                val tooShort = elapsedMs < minVoiceMs
                                                when {
                                                    !cancelled && !tooShort -> sendVoice()
                                                    tooShort && !cancelled -> {
                                                        // A tap (or a very short hold) is almost always an
                                                        // accident; say so instead of swallowing it, which
                                                        // is what made the mic feel broken.
                                                        discardVoice()
                                                        NcToaster.show("Hold the mic to record, slide up to lock")
                                                    }
                                                    else -> discardVoice()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .clickable(enabled = showSend, onClick = onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showSend) Icons.Rounded.Send else Icons.Rounded.Mic,
                            contentDescription = if (showSend) {
                                "Send message"
                            } else {
                                "Hold to record, slide up to lock, slide left to cancel"
                            },
                            tint = if (showSend) NcTheme.extra.typing else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

            voicePhase?.let { phase ->
                VoiceRecordingStrip(
                    state = VoiceUiState(
                        phase = phase,
                        durationMs = elapsedMs,
                        waveform = bars,
                        cancelProgress = cancelProgress,
                        lockProgress = lockProgress
                    ),
                    onDelete = { discardVoice() },
                    // A real pause: the recorder stops capturing (the strip's timer already
                    // only counts live time), and the phase only changes when it agrees.
                    onTogglePause = {
                        if (phase == VoicePhase.Paused) {
                            if (recorder.resume()) voicePhase = VoicePhase.Locked
                        } else if (recorder.pause()) {
                            voicePhase = VoicePhase.Paused
                        }
                    },
                    onSend = { sendVoice() }
                )
            }
        }
    }
}

/** Quote strip above the input while replying, mirroring the reference reply preview. */
@Composable
private fun ReplyComposerStrip(quoted: Message, onCancel: () -> Unit) {
    val extra = NcTheme.extra
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF2FFFFFF))
            .border(1.dp, extra.rowBorder, RoundedCornerShape(16.dp))
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(NcTheme.extra.brandGradient))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Replying to ${quoted.authorName}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = NcTheme.extra.typing
            )
            Text(
                // A voice note, a photo, a document: the strip says what is being quoted
                // instead of showing an empty line for anything that is not plain text.
                text = quoted.snippet(),
                fontSize = 12.sp,
                color = extra.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel reply",
                tint = extra.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** `chat-media-popup` from the reference: Photos / Videos / Files above the input. */
@Composable
private fun MediaPopup(onPick: (MessageKind) -> Unit) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
    ) {
        MediaOption(
            label = "Photos",
            icon = Icons.Rounded.Image,
            tint = Color(0xFF9333EA),
            background = Brush.linearGradient(listOf(Color(0xFFF3E8FF), Color(0xFFE9D5FF))),
            onClick = { onPick(MessageKind.Photo) }
        )
        MediaOption(
            label = "Videos",
            icon = Icons.Rounded.Videocam,
            tint = Color(0xFFDB2777),
            background = Brush.linearGradient(listOf(Color(0xFFFCE7F3), Color(0xFFFBCFE8))),
            onClick = { onPick(MessageKind.Video) }
        )
        MediaOption(
            label = "Files",
            icon = Icons.Rounded.Description,
            tint = Color(0xFF2563EB),
            background = Brush.linearGradient(listOf(Color(0xFFDBEAFE), Color(0xFFBFDBFE))),
            onClick = { onPick(MessageKind.Document) }
        )
    }
}

@Composable
private fun MediaOption(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    background: Brush,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NcSpace.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF374151)
        )
    }
}

/** Which call surface the header buttons open. */
private enum class CallKind { Audio, Video }

private sealed interface TimelineItem {
    val key: String

    data class Separator(val label: String) : TimelineItem {
        override val key: String = "sep-$label"
    }

    data class Bubble(val message: Message, val showSender: Boolean) : TimelineItem {
        override val key: String = message.id
    }

    /** "Resume here" marker inserted before the first message that was unread on open. */
    data object UnreadDivider : TimelineItem {
        override val key: String = "unread-divider"
    }
}

private fun buildTimeline(messages: List<Message>, firstUnreadId: String? = null): List<TimelineItem> {
    val timeline = mutableListOf<TimelineItem>()
    var lastLabel: String? = null
    var previousAuthor: String? = null
    var dividerPlaced = false
    messages.forEach { message ->
        val label = TimeFormat.daySeparator(message.timestamp)
        val newDay = label != lastLabel
        if (newDay) {
            timeline += TimelineItem.Separator(label)
            lastLabel = label
        }
        if (!dividerPlaced && firstUnreadId != null && message.id == firstUnreadId) {
            timeline += TimelineItem.UnreadDivider
            dividerPlaced = true
        }
        // Avatar only on the first bubble of a sender run (or after a day separator).
        timeline += TimelineItem.Bubble(message, showSender = newDay || previousAuthor != message.authorId)
        previousAuthor = message.authorId
    }
    return timeline
}
