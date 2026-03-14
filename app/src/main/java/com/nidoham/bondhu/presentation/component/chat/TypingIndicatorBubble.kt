package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Total duration of one full three-dot wave cycle, in milliseconds. */
private const val TYPING_CYCLE_MS = 1200

/**
 * Animated three-dot typing indicator displayed below the message list while
 * the peer is composing a reply.
 *
 * Each dot pulses its opacity in a staggered wave pattern (150 ms apart) using
 * an [androidx.compose.animation.core.InfiniteTransition]. The bubble adopts
 * the same shape and color tokens as [ReceivedMessageWidget] so it appears
 * visually consistent with incoming messages.
 *
 * This composable is shown and hidden by [MessageList] via an [AnimatedVisibility]
 * wrapper driven by the `isPeerTyping` flag from the ViewModel.
 *
 * @param peerAvatarUrl Remote URL of the peer's profile photo, passed to
 *                      [PeerAvatar] for the small avatar anchored to the left.
 * @param peerName      Display name of the peer, used as the avatar fallback
 *                      initial when no photo is available.
 */
@Composable
internal fun TypingIndicatorBubble(peerAvatarUrl: String, peerName: String) {
    val isDark      = isSystemInDarkTheme()
    val bubbleColor = if (isDark) Color(0xFF262626) else Color(0xFFEFEFEF)
    val bubbleShape = RoundedCornerShape(
        topStart    = 4.dp,
        topEnd      = 20.dp,
        bottomStart = 20.dp,
        bottomEnd   = 20.dp
    )
    val dotColor = if (isDark) Color.White else Color(0xFF555555)

    val infiniteTransition = rememberInfiniteTransition(label = "typing_indicator")

    // Each dot is staggered by 150 ms to create a rolling wave effect.
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue  = 1f,
        label        = "dot_1_alpha",
        animationSpec = infiniteRepeatable(
            animation  = keyframes {
                durationMillis = TYPING_CYCLE_MS
                0.25f at 0; 1f at 200; 0.25f at 500
            },
            repeatMode = RepeatMode.Restart
        )
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue  = 1f,
        label        = "dot_2_alpha",
        animationSpec = infiniteRepeatable(
            animation  = keyframes {
                durationMillis = TYPING_CYCLE_MS
                0.25f at 0; 0.25f at 150; 1f at 350; 0.25f at 650
            },
            repeatMode = RepeatMode.Restart
        )
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue  = 1f,
        label        = "dot_3_alpha",
        animationSpec = infiniteRepeatable(
            animation  = keyframes {
                durationMillis = TYPING_CYCLE_MS
                0.25f at 0; 0.25f at 300; 1f at 500; 0.25f at 800
            },
            repeatMode = RepeatMode.Restart
        )
    )

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.Bottom
    ) {
        Box(Modifier.padding(end = 6.dp, bottom = 2.dp)) {
            PeerAvatar(peerAvatarUrl, peerName, size = 29.dp, isOnline = false)
        }
        Box(
            modifier = Modifier
                .shadow(1.dp, bubbleShape)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}