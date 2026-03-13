package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bottom gradient bar rendered inside the controls overlay.
 *
 * Contains (left to right): current position / total duration label, a flexible
 * [PlayerSeekBar], an HD quality badge, and a fullscreen toggle button. A
 * vertical gradient fades from transparent at the top to semi-opaque black at
 * the bottom so the video remains partially visible behind these controls.
 *
 * In landscape the bar applies [navigationBarsPadding] to avoid clipping by the
 * gesture navigation bar.
 *
 * @param positionMs         Current playback position in milliseconds.
 * @param bufferedMs         Currently buffered position in milliseconds.
 * @param durationMs         Total stream duration in milliseconds.
 * @param isLandscape        When true, applies navigation bar inset padding.
 * @param onSeek             Forwarded to [PlayerSeekBar] for seek-by-position.
 * @param onToggleFullscreen Called when the fullscreen icon is tapped.
 * @param modifier           Applied to the root [Box].
 */
@Composable
fun PlayerBottomBar(
    positionMs          : Long,
    bufferedMs          : Long,
    durationMs          : Long,
    isLandscape         : Boolean,
    onSeek              : (Long) -> Unit,
    onToggleFullscreen  : () -> Unit,
    modifier            : Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color(0xCC000000)),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .then(if (isLandscape) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            // Seekbar
            PlayerSeekBar(
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                durationMs = durationMs,
                onSeek     = onSeek,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )

            // Time + quality + fullscreen
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Time label
                Text(
                    text     = "${positionMs.formatTime()} / ${durationMs.formatTime()}",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quality badge
                    IconButton(onClick = { /* quality picker */ }) {
                        Icon(
                            imageVector        = Icons.Rounded.Hd,
                            contentDescription = "Quality",
                            tint               = Color.White,
                        )
                    }

                    // Fullscreen toggle
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            imageVector        = if (isLandscape) Icons.Rounded.FullscreenExit
                            else              Icons.Rounded.Fullscreen,
                            contentDescription = if (isLandscape) "Exit fullscreen" else "Fullscreen",
                            tint               = Color.White,
                        )
                    }
                }
            }
        }
    }
}