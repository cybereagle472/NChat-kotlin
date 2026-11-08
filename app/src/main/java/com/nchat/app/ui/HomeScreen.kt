package com.nchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.navigation.HomeTab
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.core.navigation.NcScreen
import com.nchat.app.data.ServiceLocator
import com.nchat.app.network.ApiLog
import kotlinx.coroutines.delay
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import com.nchat.app.feature.chats.ChatsScreen
import com.nchat.app.feature.chats.ChatsViewModel
import com.nchat.app.feature.discover.DiscoverScreen
import com.nchat.app.feature.groups.GroupsScreen
import com.nchat.app.feature.history.HistoryScreen
import com.nchat.app.feature.profile.ProfileViewScreen
import com.nchat.app.feature.status.StatusScreen
import com.nchat.app.ui.components.NcBottomBar

/** Small floating "Connecting…" / "Reconnecting…" chip (see [HomeScreen]). */
@Composable
private fun ReconnectPill(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE61E293B))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "●", color = Color(0xFFFBBF24), fontSize = 9.sp)
        Text(
            text = label,
            color = Color(0xFFF8FAFC),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun HomeScreen(
    navigator: NcNavigator,
    onOpenChat: (Chat) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chatsViewModel: ChatsViewModel = viewModel(
        factory = ChatsViewModel.factory(ServiceLocator.chatRepository)
    )

    val groups by ServiceLocator.chatRepository.groups.collectAsStateWithLifecycle()
    val statuses by ServiceLocator.statusRepository.statuses.collectAsStateWithLifecycle()
    val postState by ServiceLocator.statusRepository.post.collectAsStateWithLifecycle()
    val calls by ServiceLocator.callRepository.history.collectAsStateWithLifecycle()
    val friendGraph by ServiceLocator.friendRepository.friends.collectAsStateWithLifecycle()
    val blockedIds by ServiceLocator.friendRepository.blockedIds.collectAsStateWithLifecycle()
    val chats by ServiceLocator.chatRepository.chats.collectAsStateWithLifecycle()
    // Blocked contacts are hidden from Friends/Requests/Find (their chat row stays put, so the
    // conversation is right where you left it when you unblock).
    val visibleGraph = remember(friendGraph, blockedIds) {
        friendGraph.filterNot { it.id in blockedIds }
    }
    // Conversation history and current friendship are independent. Keep existing threads in
    // Chats, but do not synthesize a friendship from a chat row (otherwise unfriend is undone).
    val discoverGraph = visibleGraph
    val friends = remember(visibleGraph) { visibleGraph.filter { it.relation == FriendRelation.Friend } }
    val friendGraphError by ServiceLocator.friendRepository.graphError.collectAsStateWithLifecycle()
    // Why the Friends tab has nothing to show when nothing failed — see the KDoc on
    // `FriendRepository.graphNote` ("server answered 0 friends" vs "rows we could not read").
    val friendGraphNote by ServiceLocator.friendRepository.graphNote.collectAsStateWithLifecycle()
    val findResults by ServiceLocator.friendRepository.searchResults.collectAsStateWithLifecycle()
    val me by ServiceLocator.profileRepository.profile.collectAsStateWithLifecycle()
    val pendingSent by ServiceLocator.friendRepository.pendingSent.collectAsStateWithLifecycle()
    val friendActionError by ServiceLocator.friendRepository.actionError.collectAsStateWithLifecycle()
    val friendSearchError by ServiceLocator.friendRepository.searchError.collectAsStateWithLifecycle()
    // Everyone this account already has a 1:1 conversation with: Find offers them Message
    // instead of Add, even when the server holds no friendship row for them.
    val chatPeerIds = remember(chats) { chats.filterNot { it.isGroup }.map { it.id }.toSet() }

    /*
     * A completely empty home screen is the worst thing this app can show, because a failed
     * request, an expired session and an account with no data all look the same. Once the first
     * load has settled with nothing on screen, say which one it was — the last failing call and
     * its status, or that the server answered fine and simply has nothing for this account — and
     * offer Retry and Sign out right there.
     */
    var syncDiag by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    // The realtime socket coming up means the account is reachable again: pull everything once
    // more instead of leaving the user on the empty state they were shown while it was down.
    val connection by ServiceLocator.connection.collectAsStateWithLifecycle()
    /*
     * A quiet, honest indicator.
     *
     * Nothing in the app could tell the user whether realtime was actually up, so "it arrives
     * a few seconds late" had no explanation on screen. WhatsApp shows exactly this: a small
     * banner while the connection is being (re)established. It waits 1.2 s before appearing,
     * so an ordinary reconnect — which is now immediate — never flashes it.
     */
    var showReconnect by remember { mutableStateOf(false) }
    /*
     * The device's own internet state, so the pill can say the true thing.
     *
     * "Reconnecting…" while the phone is in airplane mode is a lie the user can see through;
     * WhatsApp says "Waiting for network" for exactly this case. It also shows immediately
     * offline instead of after the 1.2 s grace, because there is no ambiguity to wait out.
     */
    val deviceOnline by ServiceLocator.network.collectAsStateWithLifecycle()
    LaunchedEffect(connection, deviceOnline) {
        if (!deviceOnline) {
            showReconnect = true
            return@LaunchedEffect
        }
        if (connection == com.nchat.app.network.ConnectionState.Connected) {
            showReconnect = false
            return@LaunchedEffect
        }
        delay(1_200)
        showReconnect = true
    }
    LaunchedEffect(connection) {
        if (connection != com.nchat.app.network.ConnectionState.Connected) return@LaunchedEffect
        /*
         * Each source is checked on its own.
         *
         * This used to bail out entirely as soon as *anything* was on screen
         * (`if (chats.isNotEmpty() || friendGraph.isNotEmpty()) return`), so an account whose
         * chats loaded but whose friend list did not never retried the friend list — the one
         * combination that leaves the Friends tab empty forever while everything else looks
         * fine.
         */
        if (chats.isEmpty()) ServiceLocator.chatRepository.refresh()
        ServiceLocator.friendRepository.refresh()
        if (statuses.isEmpty()) ServiceLocator.statusRepository.refresh()
        if (calls.isEmpty()) ServiceLocator.callRepository.refresh()
    }
    LaunchedEffect(chats.size, friendGraph.size, retryTick) {
        if (chats.isNotEmpty() || friendGraph.isNotEmpty()) {
            syncDiag = null
            return@LaunchedEffect
        }
        delay(2_500)
        val failure = ApiLog.lastFailure()
        syncDiag = if (failure != null) {
            val outcome = failure.status?.let { "HTTP $it" } ?: (failure.error ?: "no answer")
            "Can't load your account: ${failure.method} ${failure.path} → $outcome"
        } else {
            "Signed in as ${ServiceLocator.tokens.email ?: "this account"}, but the server " +
                "returned no chats, groups or friends."
        }
    }

    // A friend action that the server refused (409 already friends, network, …) is shown
    // once instead of vanishing — the reference reverts its optimistic state in the same case.
    LaunchedEffect(friendActionError) {
        friendActionError?.let {
            NcToaster.show(it)
            ServiceLocator.friendRepository.clearActionError()
        }
    }
    val mutedAuthors by ServiceLocator.statusRepository.mutedAuthors.collectAsStateWithLifecycle()
    var profileTarget by remember { mutableStateOf<ProfileTarget?>(null) }
    BackHandler(enabled = profileTarget != null) { profileTarget = null }
    val chatsListState = rememberLazyListState()
    val statusListState = rememberLazyListState()
    val groupsListState = rememberLazyListState()
    val discoverListState = rememberLazyListState()
    val historyListState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        NcBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Swapping tabs is a plain composition swap: no cross-fade, no entry
            // animations. The cross-fade used to keep both trees alive for 180ms and
            // replayed every list's staggered entrance, which is what made switching
            // feel slow.
            Box(modifier = Modifier.weight(1f)) {
                val tab = navigator.tab
                when (tab) {
                    HomeTab.Chats -> ChatsScreen(
                        viewModel = chatsViewModel,
                        listState = chatsListState,
                        onOpenChat = onOpenChat,
                        onOpenSettings = onOpenSettings,
                        onNewChat = { navigator.selectTab(HomeTab.Discover) }
                    )

                    HomeTab.Status -> StatusScreen(
                        statuses = statuses,
                        listState = statusListState,
                        myAvatarUrl = me.avatarUrl,
                        postState = postState,
                        onCreateText = { text, background, font ->
                            ServiceLocator.statusRepository.createText(text, background, font)
                        },
                        onCreateMedia = { bytes, mimeType, contentType, caption, fileName, durationMs ->
                            ServiceLocator.statusRepository.createMedia(
                                bytes = bytes,
                                mimeType = mimeType,
                                contentType = contentType,
                                caption = caption,
                                fileName = fileName,
                                durationMs = durationMs
                            )
                        },
                        onClearPostError = { ServiceLocator.statusRepository.clearPostError() },
                        onDeleteStatus = { id -> ServiceLocator.statusRepository.delete(id) },
                        mutedAuthorIds = mutedAuthors,
                        onMuteAuthor = { ServiceLocator.statusRepository.mute(it) },
                        onUnmuteAuthor = { ServiceLocator.statusRepository.unmute(it) },
                        loadViewers = { id -> ServiceLocator.statusRepository.viewers(id) },
                        onMarkViewed = { id -> ServiceLocator.statusRepository.markViewed(id) },
                        onReplyToAuthor = { status ->
                            // A status reply is a normal direct message: open that person's chat.
                            onOpenChat(
                                ServiceLocator.chatRepository.openChat(
                                    personId = status.authorId,
                                    title = status.authorName,
                                    isOnline = false
                                )
                            )
                        }
                    )

                    HomeTab.Groups -> GroupsScreen(
                        groups = groups,
                        friends = friends,
                        listState = groupsListState,
                        onOpenGroup = { group ->
                            navigator.push(NcScreen.Conversation(group.id, group.name))
                        },
                        onCreateGroup = { name, description, members ->
                            ServiceLocator.chatRepository.createGroup(name, description, members)
                        }
                    )

                    HomeTab.Discover -> DiscoverScreen(
                        // The *whole* graph, not just accepted friends: the screen derives the
                        // Requests and Find tabs from it, so filtering here silently emptied
                        // both of them.
                        friends = discoverGraph,
                        listState = discoverListState,
                        // A blocked contact is hidden from Find too; unblock them from
                        // Settings ▸ Privacy ▸ Blocked users or from the chat itself.
                        searchResults = findResults.filterNot { it.id in blockedIds },
                        searchError = friendSearchError,
                        onSearch = { ServiceLocator.friendRepository.search(it) },
                        sentRequestIds = pendingSent,
                        chatIds = chatPeerIds,
                        loadError = friendGraphError,
                        emptyHint = friendGraphNote,
                        onRetry = {
                            ServiceLocator.friendRepository.refresh()
                            ServiceLocator.chatRepository.refresh()
                        },
                        onAddFriend = { ServiceLocator.friendRepository.sendRequest(it) },
                        onCancelRequest = { ServiceLocator.friendRepository.cancelRequest(it) },
                        onAcceptRequest = { ServiceLocator.friendRepository.accept(it) },
                        onDeclineRequest = { ServiceLocator.friendRepository.decline(it) },
                        onUnfriend = { ServiceLocator.friendRepository.unfriend(it) },
                        onMessage = { friend ->
                            onOpenChat(
                                ServiceLocator.chatRepository.openChat(
                                    personId = friend.id,
                                    title = friend.name,
                                    isOnline = friend.isOnline
                                )
                            )
                        },
                        onCall = { friend, video ->
                            val chat = ServiceLocator.chatRepository.openChat(
                                personId = friend.id,
                                title = friend.name,
                                isOnline = friend.isOnline
                            )
                            navigator.push(
                                NcScreen.Conversation(chat.id, chat.title, autoCallVideo = video)
                            )
                        },
                        onViewProfile = { friend ->
                            profileTarget = ProfileTarget(
                                id = friend.id,
                                name = friend.name,
                                handle = friend.handle,
                                isOnline = friend.isOnline,
                                avatarUrl = friend.avatarUrl
                            )
                        }
                    )

                    HomeTab.History -> HistoryScreen(
                        calls = calls,
                        listState = historyListState,
                        onCall = { call, video ->
                            val chat = ServiceLocator.chatRepository.openChat(
                                personId = call.personId,
                                title = call.name,
                                isOnline = false
                            )
                            navigator.push(
                                NcScreen.Conversation(chat.id, chat.title, autoCallVideo = video)
                            )
                        },
                        onMessage = { call ->
                            onOpenChat(
                                ServiceLocator.chatRepository.openChat(
                                    personId = call.personId,
                                    title = call.name,
                                    isOnline = false
                                )
                            )
                        },
                        onViewProfile = { call ->
                            profileTarget = ProfileTarget(
                                id = call.personId,
                                name = call.name,
                                handle = "@${call.personId}",
                                isOnline = false
                            )
                        }
                    )
                }
            }

            NcBottomBar(
                selected = navigator.tab,
                onSelect = navigator::selectTab
            )
        }
        }

        if (showReconnect) {
            ReconnectPill(
                label = when {
                    !deviceOnline -> "Waiting for network"
                    connection == com.nchat.app.network.ConnectionState.Connecting -> "Connecting…"
                    else -> "Reconnecting…"
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )
        }

        syncDiag?.let { text ->
            HomeSyncBanner(
                text = text,
                onRetry = {
                    syncDiag = null
                    ServiceLocator.chatRepository.refresh()
                    ServiceLocator.friendRepository.refresh()
                    ServiceLocator.statusRepository.refresh()
                    ServiceLocator.callRepository.refresh()
                    retryTick++
                },
                onSignOut = { ServiceLocator.authRepository.signOut() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        val shown = profileTarget
        AnimatedVisibility(
            visible = shown != null,
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (shown != null) {
                ProfileViewScreen(
                    name = shown.name,
                    id = shown.id,
                    handle = shown.handle.removePrefix("@"),
                    about = if (shown.isOnline) "Online now" else "Hey there! I am using NChat.",
                    isOnline = shown.isOnline,
                    emoji = null,
                    imageUrl = shown.avatarUrl,
                    onBack = { profileTarget = null },
                    onMessage = {
                        profileTarget = null
                        onOpenChat(
                            ServiceLocator.chatRepository.openChat(
                                personId = shown.id,
                                title = shown.name,
                                isOnline = shown.isOnline
                            )
                        )
                    },
                    onCall = { video ->
                        profileTarget = null
                        val chat = ServiceLocator.chatRepository.openChat(
                            personId = shown.id,
                            title = shown.name,
                            isOnline = shown.isOnline
                        )
                        navigator.push(
                            NcScreen.Conversation(chat.id, chat.title, autoCallVideo = video)
                        )
                    }
                )
            }
        }
    }
}

/** Whoever the profile overlay is currently showing. */
private data class ProfileTarget(
    val id: String,
    val name: String,
    val handle: String,
    val isOnline: Boolean,
    val avatarUrl: String? = null
)

/**
 * Shown over the tab content when nothing loaded: the reason, a Retry, and a way out.
 *
 * Deliberately loud — an empty NChat is never a normal state, and the previous silence is what
 * made "the session expired" look like "all my chats were deleted".
 */
@Composable
private fun HomeSyncBanner(
    text: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFF7ED))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "Nothing loaded from the server",
            color = Color(0xFF9A3412),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            color = Color(0xFF7C2D12),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            BannerAction(text = "Retry", background = Color(0xFFFED7AA), onClick = onRetry)
            BannerAction(text = "Sign out", background = Color(0xFFE7E5E4), onClick = onSignOut)
        }
    }
}

@Composable
private fun BannerAction(text: String, background: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color(0xFF7C2D12),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
