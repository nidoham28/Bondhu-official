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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nidoham.bondhu.player.state.PlayerUiState
import kotlinx.coroutines.delay

/**
 * Composable that wires a Media3 [PlayerView] to the service-owned [Player]
 * and layers all control overlays on top via [PlayerControlsOverlay].
 *
 * The [player] parameter is typed as [Player] — the stable Media3 interface —
 * rather than `ExoPlayer`. At runtime, it is backed by
 * [androidx.media3.session.MediaBrowser], which implements [Player].
 * [PlayerView.setPlayer] accepts [Player] directly so no cast is needed.
 *
 * ## Position and duration
 * [PlayerService] already polls [Player.currentPosition] and [Player.duration]
 * every 500 ms and writes the results into [PlayerUiState.currentPositionMs] and
 * [PlayerUiState.durationMs]. This composable reads those values directly from
 * [uiState] — no duplicate polling is performed here.
 *
 * ## Buffered position
 * [Player.bufferedPosition] is not tracked in [PlayerUiState], so a lightweight
 * 500 ms local poll is kept for [bufferedMs] only.
 *
 * @param uiState            Current player phase and metadata.
 * @param player             [Player] instance exposed by [PlayerViewModel];
 *                           null until the MediaBrowser session is connected.
 * @param isLandscape        Forwarded to overlay children for inset-aware layout.
 * @param onBack             Back/collapse navigation callback.
 * @param onPlay             Service play callback.
 * @param onPause            Service pause callback.
 * @param onSeek             Service seekTo callback.
 * @param onRetry            Triggers re-extraction on error.
 * @param onSetQuality       Forwarded to [PlayerControlsOverlay] for quality switching.
 * @param onToggleFullscreen Requests an orientation flip from [PlayerActivity].
 * @param modifier           Applied to the root [Box].
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerVideoSurface(
    uiState            : PlayerUiState,
    player             : Player?,
    isLandscape        : Boolean,
    onBack             : () -> Unit,
    onPlay             : () -> Unit,
    onPause            : () -> Unit,
    onSeek             : (Long) -> Unit,
    onRetry            : () -> Unit,
    onSetQuality       : (Int) -> Unit,
    onToggleFullscreen : () -> Unit,
    modifier           : Modifier = Modifier,
) {
    var bufferedMs by remember { mutableLongStateOf(0L) }

    // bufferedPosition is defined on the Player interface — no cast needed.
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
        AndroidView(
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn  = true
                    resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            // PlayerView.setPlayer() accepts Player — no cast required.
            update   = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        when (uiState.phase) {
            PlayerUiState.Phase.Idle,
            PlayerUiState.Phase.Loading -> PlayerLoadingOverlay()

            PlayerUiState.Phase.Error   -> PlayerErrorOverlay(
                message = uiState.error ?: "An unknown error occurred.",
                onRetry = onRetry,
            )

            PlayerUiState.Phase.Ready   -> {
                if (uiState.isBuffering) PlayerLoadingOverlay()
            }
        }

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
            onSetQuality       = onSetQuality,
            onToggleFullscreen = onToggleFullscreen,
            modifier           = Modifier.fillMaxSize(),
        )
    }
}