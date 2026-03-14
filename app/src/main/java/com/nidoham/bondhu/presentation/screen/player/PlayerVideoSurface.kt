package com.nidoham.bondhu.presentation.screen.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nidoham.bondhu.player.state.PlayerUiState
import kotlinx.coroutines.delay

/**
 * Composable that wires a Media3 [PlayerView] to the service-owned [ExoPlayer]
 * and layers all control overlays on top via [PlayerControlsOverlay].
 *
 * ## Position and duration
 * [PlayerService] already polls [ExoPlayer.currentPosition] and [ExoPlayer.duration]
 * every 500 ms and writes the results into [PlayerUiState.currentPositionMs] and
 * [PlayerUiState.durationMs]. This composable reads those values directly from
 * [uiState] — no duplicate polling is performed here.
 *
 * ## Buffered position
 * [ExoPlayer.bufferedPosition] is not tracked in [PlayerUiState], so a lightweight
 * 500 ms local poll is kept for [bufferedMs] only. No [C.TIME_UNSET] guard is
 * needed — [ExoPlayer.bufferedPosition] always returns a valid non-negative value.
 *
 * ## AndroidView update
 * The [AndroidView] `update` lambda re-wires the player reference on every
 * recomposition, covering the case where the service restarts and delivers a
 * new [ExoPlayer] instance without recreating the view.
 *
 * @param uiState            Current player phase and metadata.
 * @param player             ExoPlayer instance owned by [PlayerService]; null until binding completes.
 * @param isLandscape        Forwarded to overlay children for inset-aware layout.
 * @param onBack             Back/collapse navigation callback.
 * @param onPlay             Service play callback.
 * @param onPause            Service pause callback.
 * @param onSeek             Service seekTo callback.
 * @param onRetry            Triggers re-extraction on error.
 * @param onToggleFullscreen Requests an orientation flip from [PlayerActivity].
 * @param modifier           Applied to the root [Box].
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerVideoSurface(
    uiState            : PlayerUiState,
    player             : ExoPlayer?,
    isLandscape        : Boolean,
    onBack             : () -> Unit,
    onPlay             : () -> Unit,
    onPause            : () -> Unit,
    onSeek             : (Long) -> Unit,
    onRetry            : () -> Unit,
    onToggleFullscreen : () -> Unit,
    modifier           : Modifier = Modifier,
) {
    // positionMs and durationMs are already maintained at 500 ms intervals by
    // PlayerService and flow through uiState — polling them here would be
    // redundant work on the main thread. Only bufferedMs needs a local poll.
    var bufferedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        while (player != null) {
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    Box(
        modifier         = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // ── PlayerView (AndroidView) ──────────────────────────────────────────
        AndroidView(
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // custom controls overlay below
                    keepScreenOn  = true
                    resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update   = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Phase-specific overlays ───────────────────────────────────────────
        when (uiState.phase) {
            PlayerUiState.Phase.Idle,
            PlayerUiState.Phase.Loading -> PlayerLoadingOverlay()

            PlayerUiState.Phase.Error   -> PlayerErrorOverlay(
                message = uiState.error ?: "An unknown error occurred.",
                onRetry = onRetry,
            )

            PlayerUiState.Phase.Ready   -> {
                // Mid-play buffering spinner — rendered above the controls overlay
                if (uiState.isBuffering) PlayerLoadingOverlay()
            }
        }

        // ── Controls overlay (always rendered; manages its own visibility) ────
        PlayerControlsOverlay(
            uiState            = uiState,
            positionMs         = uiState.currentPositionMs,
            bufferedMs         = bufferedMs,
            durationMs         = uiState.durationMs,
            isLandscape        = isLandscape,
            onBack             = onBack,
            onPlay             = onPlay,
            onPause            = onPause,
            onSeek             = onSeek,
            onToggleFullscreen = onToggleFullscreen,
            modifier           = Modifier.fillMaxSize(),
        )
    }
}