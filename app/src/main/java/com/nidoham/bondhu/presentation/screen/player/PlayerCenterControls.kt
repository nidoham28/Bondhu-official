package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Horizontally centered playback controls: ← 10 s rewind, play/pause, 10 s forward →.
 *
 * The play/pause button is larger and has a semi-transparent circular background,
 * mirroring YouTube's visual hierarchy. Rewind and forward icons match the
 * Material [Icons.Rounded.Replay10] / [Icons.Rounded.Forward10] style.
 *
 * The entire row is only visible (via [AnimatedVisibility]) when the controls
 * overlay is shown — visibility is driven by the parent [PlayerControlsOverlay].
 *
 * @param isPlaying   Whether the player is currently playing; drives the icon toggle.
 * @param isBuffering When true, the play/pause button is hidden and the loading
 *                    overlay takes visual precedence (callers should render
 *                    [PlayerLoadingOverlay] on top).
 * @param onPlay      Called when the user taps play.
 * @param onPause     Called when the user taps pause.
 * @param onRewind    Called when the user taps rewind; parent seeks back 10 s.
 * @param onForward   Called when the user taps forward; parent seeks forward 10 s.
 * @param modifier    Applied to the root [Row].
 */
@Composable
fun PlayerCenterControls(
    isPlaying   : Boolean,
    isBuffering : Boolean,
    onPlay      : () -> Unit,
    onPause     : () -> Unit,
    onRewind    : () -> Unit,
    onForward   : () -> Unit,
    modifier    : Modifier = Modifier,
) {
    Row(
        modifier            = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment   = Alignment.CenterVertically,
    ) {
        // Rewind 10 s
        IconButton(
            onClick  = onRewind,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .size(44.dp),
        ) {
            Icon(
                imageVector        = Icons.Rounded.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint               = Color.White,
                modifier           = Modifier.size(36.dp),
            )
        }

        // Play / Pause — larger, with circular backdrop
        AnimatedVisibility(
            visible = !isBuffering,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            Box(
                modifier         = Modifier
                    .size(64.dp)
                    .background(Color(0x55000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick  = if (isPlaying) onPause else onPlay,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Rounded.Pause
                        else           Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint               = Color.White,
                        modifier           = Modifier.size(44.dp),
                    )
                }
            }
        }

        // Forward 10 s
        IconButton(
            onClick  = onForward,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .size(44.dp),
        ) {
            Icon(
                imageVector        = Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                tint               = Color.White,
                modifier           = Modifier.size(36.dp),
            )
        }
    }
}