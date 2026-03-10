package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nidoham.bondhu.ui.theme.LocalCustomColors
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType

// ─── Instagram colour tokens ──────────────────────────────────────────────────

/** Instagram DM gradient: indigo-purple → vivid pink, applied left→right. */
private val IgSentGradient = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF833AB4),   // Instagram purple
        0.50f to Color(0xFFC13584),   // mid pink-purple
        1.00f to Color(0xFFE1306C)    // Instagram hot-pink
    ),
    start = Offset(0f, 0f),
    end   = Offset(Float.POSITIVE_INFINITY, 0f)
)

/**
 * Bubble corner radii for a sent message.
 *
 * Instagram convention: all four corners are heavily rounded except the
 * bottom-end (bottom-right in LTR), which carries a small "tail" radius of
 * 4 dp to anchor the bubble visually to the sender side.
 */
private val SentBubbleShape = RoundedCornerShape(
    topStart    = 20.dp,
    topEnd      = 20.dp,
    bottomStart = 20.dp,
    bottomEnd   = 4.dp
)

// ─── SentMessageWidget ────────────────────────────────────────────────────────

/**
 * Renders a single **sent** (outgoing) message bubble in the Instagram Direct
 * Messages style.
 *
 * Responsibilities:
 *  • Applies the Instagram purple→pink gradient background.
 *  • Renders text at a consistent 15 sp regardless of emoji-only content, so
 *    emojis are treated as inline characters rather than oversized stickers.
 *  • Displays the double-tick read-receipt indicator beneath the last sent
 *    message in the conversation.
 *  • Exposes an [onTap] callback so the parent can toggle the timestamp chip.
 *
 * @param message           The domain [Message] to render.
 * @param isLastSentMessage Whether this is the most recent sent message
 *                          (controls read-receipt visibility).
 * @param isReadByPeer      Whether the peer has read this message.
 * @param bubbleMaxWidth    Upper bound for the bubble width, typically 75 % of
 *                          the available column width.
 * @param onTap             Invoked when the user taps the bubble; used by the
 *                          parent to show/hide the timestamp chip.
 */
@Composable
fun SentMessageWidget(
    message:           Message,
    isLastSentMessage: Boolean,
    isReadByPeer:      Boolean,
    bubbleMaxWidth:    Dp,
    onTap:             () -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {

        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = bubbleMaxWidth)
                .shadow(elevation = 2.dp, shape = SentBubbleShape)
                .clip(SentBubbleShape)
                .background(IgSentGradient)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onTap
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SentBubbleContent(message)
        }

        // Read-receipt ticks — shown only under the last sent message once it
        // has been acknowledged by the server (timestamp != null).
        if (isLastSentMessage && message.timestamp != null) {
            Spacer(Modifier.height(3.dp))
            IgReadReceipt(isRead = isReadByPeer)
        }
    }
}

// ─── Sent Bubble Content ──────────────────────────────────────────────────────

@Composable
private fun SentBubbleContent(message: Message) {
    Column {
        when (message.toType()) {
            MessageType.IMAGE -> Text(
                text      = "📷 Photo",
                style     = MaterialTheme.typography.bodyMedium.copy(
                    color    = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp
                ),
                textAlign = TextAlign.Start
            )
            else -> if (message.content.isNotEmpty()) {
                Text(
                    text      = message.content,
                    style     = MaterialTheme.typography.bodyMedium.copy(
                        color    = Color.White,
                        fontSize = 15.sp
                    ),
                    textAlign = TextAlign.Start
                )
            }
        }
        if (message.isEdited) {
            Spacer(Modifier.height(2.dp))
            Text(
                text  = "edited",
                style = MaterialTheme.typography.labelSmall.copy(
                    color    = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp
                )
            )
        }
    }
}

// ─── Instagram-style read receipt ────────────────────────────────────────────

@Composable
private fun IgReadReceipt(isRead: Boolean) {
    val customColors = LocalCustomColors.current
    val tint = if (isRead) customColors.info else MaterialTheme.colorScheme.outline

    // Double-tick overlay: two Done icons staggered by 5 dp horizontally.
    Box(modifier = Modifier.width(20.dp).height(14.dp)) {
        Icon(
            imageVector        = Icons.Default.Done,
            contentDescription = null,
            tint               = tint,
            modifier           = Modifier.size(14.dp)
        )
        Icon(
            imageVector        = Icons.Default.Done,
            contentDescription = if (isRead) "Read" else "Sent",
            tint               = tint,
            modifier           = Modifier.size(14.dp).offset(x = 5.dp)
        )
    }
}