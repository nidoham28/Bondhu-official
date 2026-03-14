package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.player.state.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_HIDE_DELAY_MS = 3_000L
private const val SEEK_AMOUNT_MS     = 10_000L

/**
 * Full-surface overlay that hosts all player controls and manages their
 * visibility lifecycle.
 *
 * ## Visibility rules
 * - Tapping anywhere on the video toggles the controls.
 * - Controls auto-hide after [AUTO_HIDE_DELAY_MS] (3 s) when the player is
 *   actively playing.
 * - Controls stay visible indefinitely when the player is paused or in an
 *   error/loading state.
 * - Any seek, play, or pause interaction resets the auto-hide timer.
 *
 * ## Double-tap seek
 * Double-tapping the left third of the surface seeks back 10 s; the right
 * third seeks forward 10 s. A brief animated icon flash confirms the action.
 *
 * @param uiState            Current player phase, title, and playback flags.
 * @param positionMs         Live playback position in milliseconds.
 * @param bufferedMs         Buffered position in milliseconds.
 * @param durationMs         Total stream duration in milliseconds.
 * @param isLandscape        Drives inset padding and layout adjustments in child bars.
 * @param onBack             Forwarded to [PlayerTopOverlay].
 * @param onPlay             Requests service play.
 * @param onPause            Requests service pause.
 * @param onSeek             Requests service seekTo.
 * @param onSetQuality       Forwarded to [PlayerBottomBar] for quality switching.
 * @param onToggleFullscreen Requests an orientation change from [PlayerActivity].
 * @param modifier           Applied to the root [Box].
 */
@Composable
fun PlayerControlsOverlay(
    uiState            : PlayerUiState,
    positionMs         : Long,
    bufferedMs         : Long,
    durationMs         : Long,
    isLandscape        : Boolean,
    onBack             : () -> Unit,
    onPlay             : () -> Unit,
    onPause            : () -> Unit,
    onSeek             : (Long) -> Unit,
    onSetQuality       : (Int) -> Unit,
    onToggleFullscreen : () -> Unit,
    modifier           : Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var rewindFlash     by remember { mutableStateOf(false) }
    var forwardFlash    by remember { mutableStateOf(false) }
    val scope           = rememberCoroutineScope()
    var hideJob         by remember { mutableStateOf<Job?>(null) }

    fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            if (uiState.isPlaying) controlsVisible = false
        }
    }

    fun showControls() {
        controlsVisible = true
        if (uiState.isPlaying) scheduleHide()
    }

    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying && controlsVisible) scheduleHide()
        else hideJob?.cancel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible && uiState.isPlaying) scheduleHide()
                    },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3f -> {
                                val newPos = (positionMs - SEEK_AMOUNT_MS).coerceAtLeast(0L)
                                onSeek(newPos)
                                scope.launch { rewindFlash = true; delay(700); rewindFlash = false }
                                showControls()
                            }
                            offset.x > size.width * 2f / 3f -> {
                                val newPos = (positionMs + SEEK_AMOUNT_MS).coerceAtMost(durationMs)
                                onSeek(newPos)
                                scope.launch { forwardFlash = true; delay(700); forwardFlash = false }
                                showControls()
                            }
                            else -> {
                                controlsVisible = !controlsVisible
                                if (controlsVisible && uiState.isPlaying) scheduleHide()
                            }
                        }
                    },
                )
            },
    ) {
        // ── Double-tap ripple indicators ──────────────────────────────────────
        AnimatedVisibility(
            visible  = rewindFlash,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp),
        ) {
            DoubleTapIndicator(icon = Icons.Rounded.Replay10, label = "-10s")
        }

        AnimatedVisibility(
            visible  = forwardFlash,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp),
        ) {
            DoubleTapIndicator(icon = Icons.Rounded.Forward10, label = "+10s")
        }

        // ── Controls overlay ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = controlsVisible,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                PlayerTopOverlay(
                    title       = uiState.title,
                    onBack      = onBack,
                    isLandscape = isLandscape,
                    modifier    = Modifier.align(Alignment.TopCenter),
                )

                if (uiState.phase == PlayerUiState.Phase.Ready) {
                    PlayerCenterControls(
                        isPlaying   = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        onPlay      = { onPlay();  showControls() },
                        onPause     = { onPause(); showControls() },
                        onRewind    = { onSeek((positionMs - SEEK_AMOUNT_MS).coerceAtLeast(0L)); showControls() },
                        onForward   = { onSeek((positionMs + SEEK_AMOUNT_MS).coerceAtMost(durationMs)); showControls() },
                        modifier    = Modifier.align(Alignment.Center),
                    )
                }

                if (uiState.phase == PlayerUiState.Phase.Ready) {
                    PlayerBottomBar(
                        positionMs           = positionMs,
                        bufferedMs           = bufferedMs,
                        durationMs           = durationMs,
                        isLandscape          = isLandscape,
                        availableQualities   = uiState.availableQualities,
                        selectedQualityIndex = uiState.selectedQualityIndex,
                        onSeek               = { onSeek(it); showControls() },
                        onSetQuality         = onSetQuality,
                        onToggleFullscreen   = onToggleFullscreen,
                        modifier             = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

// ── Double-tap flash indicator ─────────────────────────────────────────────────

@Composable
private fun DoubleTapIndicator(
    icon  : androidx.compose.ui.graphics.vector.ImageVector,
    label : String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = Color.White,
            modifier           = Modifier.size(32.dp),
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}