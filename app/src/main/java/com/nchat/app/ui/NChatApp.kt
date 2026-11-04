package com.nchat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nchat.app.core.designsystem.NChatTheme
import com.nchat.app.core.designsystem.NcToastHost
import com.nchat.app.core.navigation.NcNavigator
import com.nchat.app.core.navigation.NcScreen
import com.nchat.app.data.ServiceLocator
import com.nchat.app.feature.auth.AuthScreen
import com.nchat.app.feature.auth.ProfileSetupScreen
import com.nchat.app.feature.chats.ConversationScreen
import com.nchat.app.feature.settings.SettingsScreen
import kotlinx.coroutines.delay

/** Where the app is in the reference's launch flow. */
private enum class LaunchStage { Auth, ProfileSetup, Main }

@Composable
fun NChatApp() {
    NChatTheme {
        val auth = ServiceLocator.authRepository
        val session by auth.session.collectAsStateWithLifecycle()
        val profileComplete by auth.profileComplete.collectAsStateWithLifecycle()

        val stage = when {
            session == null -> LaunchStage.Auth
            !profileComplete -> LaunchStage.ProfileSetup
            else -> LaunchStage.Main
        }

        // Backend builds: open the realtime socket once signed in, pull the account, and keep
        // the presence heartbeat alive while the app is in the foreground. Mock builds skip
        // all of it, so the offline demo behaves exactly as before.
        LaunchedEffect(session) {
            if (session == null) {
                ServiceLocator.disconnectRealtime()
                return@LaunchedEffect
            }
            if (!ServiceLocator.isRemote) return@LaunchedEffect
            ServiceLocator.connectRealtime()
            ServiceLocator.profileRepository.refresh()
            while (true) {
                ServiceLocator.reportPresence()
                delay(60_000)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = stage, animationSpec = tween(260), label = "launchStage") { current ->
            when (current) {
                LaunchStage.Auth -> AuthScreen()

                LaunchStage.ProfileSetup -> ProfileSetupScreen(
                    email = session.orEmpty(),
                    onComplete = { fullName, username, bio ->
                        ServiceLocator.profileRepository.update(fullName, username, bio)
                        auth.completeProfile()
                    }
                )

                LaunchStage.Main -> HomeRoot()
            }
        }

        // One host for the whole app: confirmations float above the tab bar.
        NcToastHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp)
        )
        }
    }
}

@Composable
private fun HomeRoot() {
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
                    modifier = Modifier.fillMaxSize(),
                    autoCallVideo = screen.autoCallVideo
                )

                is NcScreen.Settings -> SettingsScreen(
                    onBack = { navigator.pop() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
