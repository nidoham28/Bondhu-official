package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.nidoham.server.domain.message.Message
import com.nidoham.server.util.MessageType

// ─── WhatsApp colour tokens ──────────────────────────────────────────────────

/** WhatsApp-style dark-mode sent bubble: teal-green. */
private val SentBubbleColorDark = Color(0xFF005C4B) // WhatsApp Dark Sent

/** WhatsApp-style light-mode sent bubble: light green. */
private val SentBubbleColorLight = Color(0xFFE7FFDB) // WhatsApp Light Sent


/**
 * Bubble corner radii for a sent message.
 *
 * WhatsApp convention: all four corners are heavily rounded except the
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
 * Renders a single **sent** (outgoing) message bubble in a WhatsApp-inspired
 * style.
 *
 * Responsibilities:
 *  • Applies a solid teal-green (dark) or light-green (light) background.
 *  • Renders text at a consistent 16 sp regardless of emoji-only content, so
 *    emojis are treated as inline characters rather than oversized stickers.
 *  • Displays the WhatsApp-style double-tick read-receipt beneath the last sent
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
    message: Message,
    isLastSentMessage: Boolean,
    isReadByPeer:      Boolean,
    bubbleMaxWidth:    Dp,
    onTap:             () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isDark) SentBubbleColorDark else SentBubbleColorLight
    val contentColor = if (isDark) Color(0xFFE9EDEF) else Color(0xFF111B21)

    Column(horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = bubbleMaxWidth)
                .shadow(elevation = 2.dp, shape = SentBubbleShape)
                .clip(SentBubbleShape)
                .background(bubbleColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onTap
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SentBubbleContent(message, contentColor)
        }

        if (isLastSentMessage) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.padding(end = 4.dp)) {
                WaReadReceipt(isRead = isReadByPeer)
            }
        }
    }
}

// ─── WhatsApp-style read receipt ────────────────────────────────────────────

@Composable
private fun WaReadReceipt(isRead: Boolean) {
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

// ─── Sent Bubble Content ──────────────────────────────────────────────────────

@Composable
private fun SentBubbleContent(message: Message, contentColor: Color) {
    Column {
        when (message.type) {
            MessageType.IMAGE.name.lowercase() -> Text(
                text      = "📷 Photo",
                style     = MaterialTheme.typography.bodyMedium.copy(
                    color    = contentColor.copy(alpha = 0.85f),
                    fontSize = 16.sp
                ),
                textAlign = TextAlign.Start
            )
            else -> if (message.content.isNotEmpty()) {
                Text(
                    text      = message.content,
                    style     = MaterialTheme.typography.bodyMedium.copy(
                        color    = contentColor,
                        fontSize = 16.sp
                    ),
                    textAlign = TextAlign.Start
                )
            }
        }
        // FIX: message.isEdited does not exist on Message. The edited state is
        //      represented by editedAt: Timestamp?, which is null when unedited.
        if (message.editedAt != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text  = "edited",
                style = MaterialTheme.typography.labelSmall.copy(
                    color    = contentColor.copy(alpha = 0.55f),
                    fontSize = 10.sp
                ),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
