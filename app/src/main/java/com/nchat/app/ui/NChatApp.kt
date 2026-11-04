package com.nchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nchat.app.core.designsystem.NChatTheme
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.core.navigation.NcScreen
import com.nchat.app.feature.chats.ConversationScreen
import com.nchat.app.feature.settings.SettingsScreen

@Composable
fun NChatApp() {
    NChatTheme {
        val navigator = remember { NcNavigator() }
        BackHandler(enabled = navigator.canPop) { navigator.pop() }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = navigator.current,
                transitionSpec = {
                    if (targetState is NcScreen.Home) {
                        (slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(280)) { it } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(280)) { -it / 6 } + fadeOut(tween(180)))
                    }
                },
                label = "screenTransition",
                modifier = Modifier.fillMaxSize()
            ) { screen ->
                when (screen) {
                    is NcScreen.Home -> HomeScreen(
                        navigator = navigator,
                        onOpenChat = { chat ->
                            navigator.push(NcScreen.Conversation(chat.id, chat.title))
                        },
                        onOpenSettings = { navigator.push(NcScreen.Settings) },
                        modifier = Modifier.fillMaxSize()
                    )

                    is NcScreen.Conversation -> ConversationScreen(
                        chatId = screen.chatId,
                        chatTitle = screen.title,
                        onBack = { navigator.pop() },
                        modifier = Modifier.fillMaxSize()
                    )

                    is NcScreen.Settings -> SettingsScreen(
                        onBack = { navigator.pop() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
