package com.nchat.app.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** The five bottom-bar destinations, in the same order as the reference app. */
enum class HomeTab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.Rounded.ChatBubble),
    Status("Status", Icons.Rounded.Circle),
    Groups("Groups", Icons.Rounded.Groups),
    Discover("Discover", Icons.Rounded.Explore),
    History("History", Icons.Rounded.History)
}

sealed interface NcScreen {
    data object Home : NcScreen

    /**
     * A conversation. [autoCallVideo] is null for a normal open, or true/false when the
     * chat should pop straight into a video / voice call (Discover's call buttons).
     */
    data class Conversation(
        val chatId: String,
        val title: String,
        val autoCallVideo: Boolean? = null,
        /**
         * A message to land on, from a notification tap. The screen scrolls to it and rings it so
         * the user can see *which* message the alert was about, instead of being dropped at the
         * bottom of the conversation to hunt for it.
         */
        val focusMessageId: String? = null
    ) : NcScreen

    data object Settings : NcScreen
}

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
