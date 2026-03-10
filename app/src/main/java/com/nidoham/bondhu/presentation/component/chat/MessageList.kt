package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nidoham.server.domain.model.Message

/**
 * Scrollable, animated list of conversation messages grouped by date.
 *
 * Rendering pipeline:
 * 1. [Message] objects are grouped into date buckets via [groupByDate],
 *    which produces labels such as "TODAY", "YESTERDAY", or "JAN 5".
 * 2. A [DateChip] separator is emitted before each group.
 * 3. Each message is rendered as a [MessageBubble] that routes internally to
 *    [SentMessageWidget] or [ReceivedMessageWidget] based on ownership.
 * 4. A [TypingIndicatorBubble] is appended at the tail of the list and shown
 *    or hidden via [AnimatedVisibility] driven by [isPeerTyping].
 *
 * Bubble width is capped at 75 % of available width on compact screens,
 * narrowing further on larger windows to prevent uncomfortably long lines.
 *
 * @param messages      Chronologically ordered list of messages to display.
 * @param listState     External [LazyListState] so the parent can programmatically
 *                      scroll to the latest message on new arrivals.
 * @param isMine        Returns `true` when a message was sent by the local user.
 * @param isReadByPeer  Returns `true` when the peer has read the given message.
 * @param windowClass   Adaptive layout breakpoint; controls padding and bubble width.
 * @param peerAvatarUrl Remote URL for the peer's profile photo.
 * @param peerName      Display name of the peer.
 * @param isPeerTyping  When `true`, the typing indicator bubble is displayed
 *                      at the bottom of the list.
 */
@Composable
fun MessageList(
    messages: List<Message>,
    listState: LazyListState,
    isMine: (Message) -> Boolean,
    isReadByPeer: (Message) -> Boolean,
    windowClass: WindowWidthClass,
    peerAvatarUrl: String,
    peerName: String,
    isPeerTyping: Boolean = false
) {
    val horizontalPadding = when (windowClass) {
        WindowWidthClass.Compact  -> 8.dp
        WindowWidthClass.Medium   -> 24.dp
        WindowWidthClass.Expanded -> 80.dp
    }

    // The ID of the most recently sent message; used to determine where to
    // place the read-receipt indicator inside SentMessageWidget.
    val lastSentMessageId = remember(messages) {
        messages.lastOrNull { isMine(it) }?.messageId
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bubbleMaxWidth = when (windowClass) {
            WindowWidthClass.Compact  -> maxWidth * 0.75f
            WindowWidthClass.Medium   -> maxWidth * 0.65f
            WindowWidthClass.Expanded -> maxWidth * 0.50f
        }

        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding      = PaddingValues(vertical = 12.dp)
        ) {
            val groupedMessages = messages.groupByDate()

            groupedMessages.forEach { (dateLabel, dayMessages) ->
                item(key = "date_$dateLabel") {
                    DateChip(dateLabel)
                }
                items(dayMessages, key = { it.messageId }) { message ->
                    // `appeared` drives the entrance animation; flipping it to
                    // true on the first composition triggers slideInVertically.
                    var appeared by remember { mutableStateOf(false) }
                    LaunchedEffect(message.messageId) { appeared = true }

                    AnimatedVisibility(
                        visible = appeared,
                        enter   = slideInVertically { it / 2 } + fadeIn()
                    ) {
                        MessageBubble(
                            message           = message,
                            isMine            = isMine(message),
                            isReadByPeer      = isReadByPeer(message),
                            isLastSentMessage = message.messageId == lastSentMessageId,
                            bubbleMaxWidth    = bubbleMaxWidth,
                            peerAvatarUrl     = peerAvatarUrl,
                            peerName          = peerName
                        )
                    }
                }
            }

            item(key = "typing_indicator") {
                AnimatedVisibility(
                    visible = isPeerTyping,
                    enter   = slideInVertically { it / 2 } + fadeIn(),
                    exit    = fadeOut()
                ) {
                    TypingIndicatorBubble(
                        peerAvatarUrl = peerAvatarUrl,
                        peerName      = peerName
                    )
                }
            }
        }
    }
}