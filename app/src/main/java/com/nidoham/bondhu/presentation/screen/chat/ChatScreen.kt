package com.nidoham.bondhu.presentation.screen.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nidoham.bondhu.presentation.component.chat.recentEmojis
import com.nidoham.bondhu.presentation.component.chat.toWindowWidthClass
import com.nidoham.bondhu.presentation.viewmodel.ChatUiState
import kotlinx.coroutines.launch
import org.nidoham.server.domain.model.Message

/**
 * Root composable for the one-to-one chat conversation screen.
 *
 * Responsibilities:
 *  - Renders the full-screen wallpaper ([ChatBackground]) as the bottom-most
 *    z-order layer; the [Scaffold] above it uses a transparent container color
 *    so the image shows through.
 *  - Hosts the [ChatTopBar], [MessageList], [ChatInputBar], and [EmojiBottomSheet].
 *  - Automatically scrolls the message list to the newest item whenever the
 *    message count changes or the initial load completes.
 *  - Manages the emoji bottom-sheet lifecycle (show / hide / selected emoji
 *    appended to the input text).
 *
 * This composable is intentionally free of business logic; all state lives in
 * the caller-supplied [uiState] and the provided callbacks.
 *
 * @param uiState        Snapshot of the current chat UI state.
 * @param onBack         Invoked when the user taps the back arrow.
 * @param onInputChanged Invoked on every keystroke in the text field.
 * @param onSend         Invoked when the user sends a message.
 * @param isMine         Returns `true` if the supplied [Message] was sent by
 *                       the local user.
 * @param isReadByPeer   Returns `true` if the peer has read the supplied
 *                       [Message].
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
    val listState      = rememberLazyListState()
    var showEmojiSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState     = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope          = rememberCoroutineScope()

    // Auto-scroll to the latest message whenever new messages arrive or the
    // initial load finishes.
    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (!uiState.isLoading && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowClass = maxWidth.toWindowWidthClass()

        // Full-screen wallpaper — sits behind the Scaffold in the z-order.
        ChatBackground()

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            // Transparent so ChatBackground is visible through the Scaffold.
            containerColor = Color.Transparent,
            topBar = {
                ChatTopBar(
                    peerName        = uiState.peerName,
                    peerAvatarUrl   = uiState.peerAvatarUrl,
                    isOnline    = uiState.isPeerOnline,
                    lastSeenTimestamp    = uiState.lastSeen,
                    onNavigateBack      = onBack,
                    windowSizeClass = windowClass,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            uiState.isLoading          -> CircularProgressIndicator(
                                modifier    = Modifier.align(Alignment.Center),
                                color       = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )
                            uiState.messages.isEmpty() -> { /* Empty state — intentionally blank. */ }
                            else                       -> MessageList(
                                messages      = uiState.messages,
                                listState     = listState,
                                isMine        = isMine,
                                isReadByPeer  = isReadByPeer,
                                windowClass   = windowClass,
                                peerAvatarUrl = uiState.peerAvatarUrl,
                                peerName      = uiState.peerName,
                                isPeerTyping  = uiState.isPeerTyping,
                            )
                        }
                    }

                    ChatInputBar(
                        messageText         = uiState.inputText,
                        onMessageTextChange = onInputChanged,
                        onSend       = onSend,
                        isSendError  = uiState.isSendError,
                        windowSizeClass  = windowClass,
                        onEmojiClick = {
                            // State write happens on the main thread so the value
                            // is immediately visible to the if-block below.
                            // Only sheetState.show() requires a coroutine.
                            showEmojiSheet = true
                            scope.launch { sheetState.show() }
                        },
                    )
                }
            }
        }
    }

    if (showEmojiSheet) {
        EmojiBottomSheet(
            sheetState      = sheetState,
            recentEmojis    = recentEmojis,
            onDismiss       = {
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion { showEmojiSheet = false }
            },
            onEmojiSelected = { emoji ->
                // Maintain a capped, deduplicated recent-emoji list.
                recentEmojis.remove(emoji)
                recentEmojis.add(0, emoji)
                if (recentEmojis.size > 24) recentEmojis.removeLastOrNull()
                onInputChanged(uiState.inputText + emoji)
            },
        )
    }
}