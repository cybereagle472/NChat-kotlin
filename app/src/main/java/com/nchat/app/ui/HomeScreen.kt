package com.nchat.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.navigation.HomeTab
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.Chat
import com.nchat.app.feature.chats.ChatsScreen
import com.nchat.app.feature.chats.ChatsViewModel
import com.nchat.app.feature.discover.DiscoverScreen
import com.nchat.app.feature.groups.GroupsScreen
import com.nchat.app.feature.history.HistoryScreen
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

    val chatsListState = rememberLazyListState()
    val statusListState = rememberLazyListState()
    val groupsListState = rememberLazyListState()
    val discoverListState = rememberLazyListState()
    val historyListState = rememberLazyListState()

    NcBackdrop(modifier = modifier.fillMaxSize()) {
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
                        groups = ServiceLocator.groups,
                        listState = groupsListState,
                        onCreateGroup = {}
                    )

                    HomeTab.Discover -> DiscoverScreen(
                        friends = ServiceLocator.friends,
                        listState = discoverListState,
                        onMessage = {}
                    )

                    HomeTab.History -> HistoryScreen(
                        calls = ServiceLocator.calls,
                        listState = historyListState
                    )
                }
            }

            NcBottomBar(
                selected = navigator.tab,
                onSelect = navigator::selectTab
            )
        }
    }
}
