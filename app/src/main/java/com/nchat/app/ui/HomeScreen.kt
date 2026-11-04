package com.nchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.navigation.HomeTab
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.core.navigation.NcScreen
import com.nchat.app.data.ServiceLocator
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
    val friendGraph by ServiceLocator.friendRepository.friends.collectAsStateWithLifecycle()
    val friends = remember(friendGraph) {
        friendGraph.filter { it.relation == FriendRelation.Friend }
    }
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
            AnimatedContent(
                targetState = navigator.tab,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "tabContent"
            ) { tab ->
                when (tab) {
                    HomeTab.Chats -> ChatsScreen(
                        viewModel = chatsViewModel,
                        listState = chatsListState,
                        onOpenChat = onOpenChat,
                        onOpenSettings = onOpenSettings,
                        onNewChat = { navigator.selectTab(HomeTab.Discover) }
                    )

                    HomeTab.Status -> StatusScreen(
                        statuses = ServiceLocator.statuses,
                        listState = statusListState
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
                        friends = friends,
                        listState = discoverListState,
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
                                isOnline = friend.isOnline
                            )
                        }
                    )

                    HomeTab.History -> HistoryScreen(
                        calls = ServiceLocator.calls,
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
                    handle = shown.handle.removePrefix("@"),
                    about = if (shown.isOnline) "Online now" else "Hey there! I am using NChat.",
                    isOnline = shown.isOnline,
                    emoji = null,
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
    val isOnline: Boolean
)
