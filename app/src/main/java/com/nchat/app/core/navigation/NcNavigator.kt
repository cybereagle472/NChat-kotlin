package com.nchat.app.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** Top level destinations reachable from the bottom bar. */
enum class HomeTab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.Rounded.Chat),
    Updates("Updates", Icons.Rounded.DonutLarge),
    Communities("Communities", Icons.Rounded.Groups),
    Calls("Calls", Icons.Rounded.Call)
}

/** Every destination the app can show. */
sealed interface NcScreen {
    data object Home : NcScreen

    data class Conversation(val chatId: String, val title: String) : NcScreen

    data object Settings : NcScreen
}

/**
 * Small state driven navigator. A hand written stack keeps tab state and transitions
 * predictable, and avoids pulling a navigation library in before the backend exists.
 */
@Stable
class NcNavigator(initialTab: HomeTab = HomeTab.Chats) {

    var tab: HomeTab by mutableStateOf(initialTab)
        private set

    private val backStack = mutableStateListOf<NcScreen>(NcScreen.Home)

    val current: NcScreen
        get() = backStack.last()

    val canPop: Boolean
        get() = backStack.size > 1

    fun selectTab(value: HomeTab) {
        if (backStack.size > 1) {
            backStack.removeRange(1, backStack.size)
        }
        tab = value
    }

    fun push(screen: NcScreen) {
        if (backStack.last() == screen) return
        backStack.add(screen)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}
