package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType

// ─── Instagram colour tokens ──────────────────────────────────────────────────

/**
 * Bubble fill for incoming messages.
 *
 * Light mode → soft light-grey (#EFEFEF) — matches Instagram's white-background
 * received bubble.
 * Dark  mode → charcoal (#262626) — matches Instagram's dark-mode received
 * bubble on a near-black canvas.
 */
private val ReceivedBubbleColorLight = Color(0xFFEFEFEF)
private val ReceivedBubbleColorDark  = Color(0xFF262626)

/**
 * Bubble corner radii for a received message.
 *
 * Instagram convention: the top-start corner (top-left in LTR) carries a small
 * "tail" radius of 4 dp to anchor the bubble to the peer-avatar side; all
 * other corners are heavily rounded.
 */
private val ReceivedBubbleShape = RoundedCornerShape(
    topStart    = 4.dp,
    topEnd      = 20.dp,
    bottomStart = 20.dp,
    bottomEnd   = 20.dp
)

// ─── ReceivedMessageWidget ────────────────────────────────────────────────────

/**
 * Renders a single **received** (incoming) message bubble in the Instagram
 * Direct Messages style.
 *
 * Responsibilities:
 *  • Applies the correct fill colour for the current theme (light/dark) without
 *    any gradient — Instagram received bubbles are always a flat, neutral grey.
 *  • Renders text at a consistent 15 sp regardless of emoji-only content, so
 *    emojis are treated as inline characters rather than oversized stickers.
 *  • Exposes an [onTap] callback so the parent can toggle the timestamp chip.
 *
 * Note: The peer avatar that sits to the left of this widget is rendered by the
 * parent [MessageBubble] composable rather than here, keeping layout concerns
 * cleanly separated from the bubble's visual presentation.
 *
 * @param message        The domain [Message] to render.
 * @param bubbleMaxWidth Upper bound for the bubble width, typically 75 % of the
 *                       available column width.
 * @param onTap          Invoked when the user taps the bubble; used by the
 *                       parent to show/hide the timestamp chip.
 */
@Composable
fun ReceivedMessageWidget(
    message:        Message,
    bubbleMaxWidth: Dp,
    onTap:          () -> Unit
) {
    val isDark      = isSystemInDarkTheme()
    val bubbleColor = if (isDark) ReceivedBubbleColorDark else ReceivedBubbleColorLight
    val textColor   = if (isDark) Color.White else Color(0xFF111111)

    Box(
        modifier = Modifier
            .widthIn(min = 60.dp, max = bubbleMaxWidth)
            .shadow(elevation = 1.dp, shape = ReceivedBubbleShape)
            .clip(ReceivedBubbleShape)
            .background(bubbleColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onTap
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        ReceivedBubbleContent(message = message, textColor = textColor)
    }
}

// ─── Received Bubble Content ──────────────────────────────────────────────────

@Composable
private fun ReceivedBubbleContent(message: Message, textColor: Color) {
    Column {
        when (message.toType()) {
            MessageType.IMAGE -> Text(
                text      = "📷 Photo",
                style     = MaterialTheme.typography.bodyMedium.copy(
                    color    = textColor.copy(alpha = 0.80f),
                    fontSize = 15.sp
                ),
                textAlign = TextAlign.Start
            )
            else -> if (message.content.isNotEmpty()) {
                Text(
                    text      = message.content,
                    style     = MaterialTheme.typography.bodyMedium.copy(
                        color    = textColor,
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
                    color    = textColor.copy(alpha = 0.50f),
                    fontSize = 10.sp
                )
            )
        }
    }
}