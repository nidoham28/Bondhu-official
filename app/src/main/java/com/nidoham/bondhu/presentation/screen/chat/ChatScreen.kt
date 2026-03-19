package com.nidoham.bondhu.presentation.screen.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.presentation.component.chat.ChatBackground
import com.nidoham.bondhu.presentation.component.chat.ChatInputBar
import com.nidoham.bondhu.presentation.component.chat.ChatTopBar
import com.nidoham.bondhu.presentation.component.chat.EmojiBottomSheet
import com.nidoham.bondhu.presentation.component.chat.MessageList
import com.nidoham.bondhu.presentation.component.chat.toWindowWidthClass
import com.nidoham.bondhu.presentation.viewmodel.ChatUiState
import com.nidoham.server.domain.message.Message
import kotlinx.coroutines.launch

/**
 * Root composable for the one-to-one chat conversation screen.
 *
 * Handles the layout structure, system insets, and coordination between
 * the message list, input bar, and background.
 *
 * @param uiState The current state of the chat UI.
 * @param onBack Callback for the back navigation button.
 * @param onInputChanged Callback when the input text field changes.
 * @param onSend Callback when the send button is clicked.
 * @param isMine Predicate to determine if a message belongs to the current user.
 * @param isReadByPeer Predicate to determine if a message has been read by the peer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    isMine: (Message) -> Boolean,
    isReadByPeer: (Message) -> Boolean,
) {
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Session-scoped list of recently used emojis for the current screen instance.
    val recentEmojis = remember { mutableStateListOf<String>() }
    var isEmojiSheetVisible by remember { mutableStateOf(false) }

    // Automatically scroll to the newest message whenever the list updates.
    // The ViewModel emits messages oldest-first, so the newest item is at the last index.
    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (!uiState.isLoading && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthSizeClass = maxWidth.toWindowWidthClass()

        // Layer 1: Full-screen wallpaper background.
        ChatBackground()

        // Layer 2: Main content scaffold with a transparent container to show the background.
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars,
            containerColor = Color.Transparent,
            topBar = {
                ChatTopBar(
                    peerName = uiState.peerName,
                    peerAvatarUrl = uiState.peerAvatarUrl,
                    isOnline = uiState.isPeerOnline,
                    statusText = uiState.lastSeen,
                    onNavigateBack = onBack,
                    windowSizeClass = widthSizeClass,
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding) // Apply insets from Scaffold (Status bar + TopBar)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding() // Apply keyboard insets
                ) {
                    // Message List Container
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            uiState.isLoading -> {
                                LoadingIndicator()
                            }
                            uiState.messages.isEmpty() -> {
                                // Placeholder for empty conversation state.
                            }
                            else -> {
                                MessageList(
                                    messages = uiState.messages,
                                    listState = listState,
                                    isMine = isMine,
                                    isReadByPeer = isReadByPeer,
                                    windowClass = widthSizeClass,
                                    peerAvatarUrl = uiState.peerAvatarUrl,
                                    peerName = uiState.peerName,
                                    isPeerTyping = uiState.isPeerTyping
                                )
                            }
                        }
                    }

                    // Input Bar
                    ChatInputBar(
                        messageText = uiState.inputText,
                        onMessageTextChange = onInputChanged,
                        onSend = onSend,
                        isSendError = uiState.isSendError,
                        windowSizeClass = widthSizeClass,
                        onEmojiClick = {
                            isEmojiSheetVisible = true
                            scope.launch { sheetState.show() }
                        }
                    )
                }
            }
        }
    }

    // Emoji Picker Bottom Sheet
    if (isEmojiSheetVisible) {
        EmojiBottomSheet(
            sheetState = sheetState,
            recentEmojis = recentEmojis,
            onDismiss = {
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            isEmojiSheetVisible = false
                        }
                    }
            },
            onEmojiSelected = { emoji ->
                updateRecentEmojis(recentEmojis, emoji)
                onInputChanged(uiState.inputText + emoji)
            }
        )
    }
}

/**
 * Helper composable for the centered loading indicator.
 */
@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
    }
}

/**
 * Updates the list of recently used emojis.
 * Moves the selected emoji to the front and caps the list size at 24.
 */
private fun updateRecentEmojis(recentEmojis: MutableList<String>, emoji: String) {
    recentEmojis.remove(emoji)
    recentEmojis.add(0, emoji)
    if (recentEmojis.size > 24) {
        recentEmojis.removeLastOrNull()
    }
}