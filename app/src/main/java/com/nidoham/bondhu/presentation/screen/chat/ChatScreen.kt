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
 * Responsibilities:
 *  - Renders the full-screen wallpaper ([ChatBackground]) as the bottom-most
 *    z-order layer; the [Scaffold] above it uses a transparent container color
 *    so the image shows through.
 *  - Hosts the [ChatTopBar], [MessageList], [ChatInputBar], and [EmojiBottomSheet].
 *  - Automatically scrolls to the newest message whenever the message count
 *    changes or the initial load completes. The list is ordered oldest-first
 *    (reversed by the ViewModel), so the newest item sits at [lastIndex].
 *  - Manages the emoji bottom-sheet lifecycle and a session-scoped
 *    recent-emoji list.
 *
 * This composable is intentionally free of business logic; all state lives in
 * the caller-supplied [uiState] and the provided callbacks.
 *
 * @param uiState        Snapshot of the current chat UI state.
 * @param onBack         Invoked when the user taps the back arrow.
 * @param onInputChanged Invoked on every keystroke in the text field.
 * @param onSend         Invoked when the user sends a message.
 * @param isMine         Returns true if the supplied [Message] was sent by
 *                       the local user.
 * @param isReadByPeer   Returns true if the peer has read the supplied [Message].
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
    val listState  = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope      = rememberCoroutineScope()

    // Session-scoped recent-emoji list. Owned here rather than as a shared
    // global so each screen instance maintains independent recency state.
    val recentEmojis = remember { mutableStateListOf<String>() }

    // rememberSavable is not needed here — sheet visibility does not need to
    // survive process death, and the overhead of serialization is unnecessary.
    var showEmojiSheet by remember { mutableStateOf(false) }

    // Scroll to the newest message (lastIndex) whenever the message list grows
    // or the initial load completes. The ViewModel emits the list oldest-first,
    // so index 0 is the oldest and lastIndex is always the newest item.
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
            containerColor      = Color.Transparent,
            topBar = {
                ChatTopBar(
                    peerName        = uiState.peerName,
                    peerAvatarUrl   = uiState.peerAvatarUrl,
                    isOnline        = uiState.isPeerOnline,
                    lastSeenTimestamp = uiState.lastSeen,
                    onNavigateBack  = onBack,
                    windowSizeClass = windowClass,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Apply both top and bottom scaffold insets. Bottom is
                    // required for navigation bar clearance when present;
                    // the keyboard is handled separately by imePadding().
                    .padding(
                        top    = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding()
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            uiState.isLoading -> CircularProgressIndicator(
                                modifier    = Modifier.align(Alignment.Center),
                                color       = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )

                            uiState.messages.isEmpty() -> {
                                // Empty state placeholder — add a prompt composable
                                // here when an empty-state design is available.
                            }

                            else -> MessageList(
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
                        onSend              = onSend,
                        isSendError         = uiState.isSendError,
                        windowSizeClass     = windowClass,
                        onEmojiClick        = {
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
            sheetState   = sheetState,
            recentEmojis = recentEmojis,
            onDismiss    = {
                scope.launch { sheetState.hide() }
                    .invokeOnCompletion { showEmojiSheet = false }
            },
            onEmojiSelected = { emoji ->
                // Maintain a capped, deduplicated recent-emoji list scoped to
                // this screen instance. Capacity is capped at 24 entries.
                recentEmojis.remove(emoji)
                recentEmojis.add(0, emoji)
                if (recentEmojis.size > 24) recentEmojis.removeLastOrNull()
                onInputChanged(uiState.inputText + emoji)
            },
        )
    }
}