package com.nchat.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.navigation.HomeTab
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.Chat
import com.nchat.app.feature.calls.CallsScreen
import com.nchat.app.feature.chats.ChatsScreen
import com.nchat.app.feature.chats.ChatsViewModel
import com.nchat.app.feature.communities.CommunitiesScreen
import com.nchat.app.feature.updates.UpdatesScreen
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
    val chatsState by chatsViewModel.uiState.collectAsStateWithLifecycle()

    val chatsListState = rememberLazyListState()
    val updatesListState = rememberLazyListState()
    val communitiesListState = rememberLazyListState()
    val callsListState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = navigator.tab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.985f)) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.985f))
            },
            label = "tabContent"
        ) { tab ->
            when (tab) {
                HomeTab.Chats -> ChatsScreen(
                    viewModel = chatsViewModel,
                    listState = chatsListState,
                    onOpenChat = onOpenChat,
                    onOpenSettings = onOpenSettings
                )

                HomeTab.Updates -> UpdatesScreen(
                    stories = ServiceLocator.stories,
                    channels = ServiceLocator.channels,
                    listState = updatesListState
                )

                HomeTab.Communities -> CommunitiesScreen(
                    communities = ServiceLocator.communities,
                    listState = communitiesListState
                )

                HomeTab.Calls -> CallsScreen(
                    calls = ServiceLocator.calls,
                    listState = callsListState
                )
            }
        }

        NcBottomBar(
            selected = navigator.tab,
            onSelect = navigator::selectTab,
            chatUnread = chatsState.unreadCount
        )
    }
}
