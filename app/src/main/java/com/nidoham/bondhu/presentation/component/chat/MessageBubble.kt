package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nidoham.server.domain.message.Message

/**
 * Orchestrates the layout of a single message in the conversation list,
 * delegating visual rendering to [SentMessageWidget] or [ReceivedMessageWidget]
 * and [DeletedMessageBubble] based on message ownership and deletion state.
 *
 * Responsibilities:
 * - Routes to [DeletedMessageBubble] when [Message.deleted] is `true`.
 * - Aligns the bubble to the right ([isMine] = `true`) or left ([isMine] = `false`).
 * - Renders a centred timestamp chip on tap, animated with a slide-and-fade
 *   entrance so it does not disrupt surrounding bubble positions.
 * - Attaches the small peer avatar to the left of incoming messages, matching
 *   the Instagram Direct layout where the avatar anchors to the bottom of a run.
 *
 * **Note:** Date-separator chips are the responsibility of [MessageList], not
 * this composable.
 *
 * @param message           The domain model to render.
 * @param isMine            `true` when the message was sent by the local user.
 * @param isReadByPeer      `true` when the peer has acknowledged the message.
 * @param isLastSentMessage `true` when this is the most recently sent message;
 *                          controls read-receipt indicator visibility.
 * @param bubbleMaxWidth    Upper bound on bubble width, typically 75 % of the
 *                          available column width from [MessageList].
 * @param peerAvatarUrl     Remote URL for the peer's profile photo.
 * @param peerName          Display name of the peer, used as avatar fallback.
 */
@Composable
internal fun MessageBubble(
    message: Message,
    isMine: Boolean,
    isReadByPeer: Boolean,
    isLastSentMessage: Boolean,
    bubbleMaxWidth: Dp,
    peerAvatarUrl: String,
    peerName: String
) {
    // Early-exit for deleted messages: renders a tombstone in place of content.

    /**
     * if (message.deleted) {
     *    DeletedMessageBubble(isMine)
     *    return
     *  }
     */

    var showTimestamp by remember { mutableStateOf(false) }
    val timestamp = remember(message.timestamp) { message.timestamp?.toDate()?.toTimeString() }

    Column(Modifier.fillMaxWidth()) {

        // Timestamp chip — slides in from above on tap; hidden by default.
        AnimatedVisibility(
            visible = showTimestamp && timestamp != null,
            enter   = fadeIn() + slideInVertically { -it / 2 },
            exit    = fadeOut()
        ) {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = timestamp ?: "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color     = Color.White.copy(alpha = 0.80f),
                        fontSize  = 11.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }

        if (isMine) {
            // Sent message — pinned to the right edge.
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.Bottom
            ) {
                SentMessageWidget(
                    message           = message,
                    isLastSentMessage = isLastSentMessage,
                    isReadByPeer      = isReadByPeer,
                    bubbleMaxWidth    = bubbleMaxWidth,
                    onTap             = { showTimestamp = !showTimestamp }
                )
            }
        } else {
            // Received message — pinned to the left edge with peer avatar.
            // The avatar anchors to the bottom of the bubble row, matching the
            // Instagram DM layout where it aligns with the last bubble in a run.
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment     = Alignment.Bottom
            ) {
                Box(Modifier.padding(end = 6.dp, bottom = 2.dp)) {
                    PeerAvatar(
                        avatarUrl = peerAvatarUrl,
                        name      = peerName,
                        size      = 29.dp,
                        isOnline  = false
                    )
                }
                ReceivedMessageWidget(
                    message        = message,
                    bubbleMaxWidth = bubbleMaxWidth,
                    onTap          = { showTimestamp = !showTimestamp }
                )
            }
        }
    }
}