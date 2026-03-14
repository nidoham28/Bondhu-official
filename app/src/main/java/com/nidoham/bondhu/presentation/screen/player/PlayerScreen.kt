package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.exoplayer.ExoPlayer
import com.nidoham.bondhu.player.state.PlayerUiState

/**
 * Root composable for the full-screen player experience.
 *
 * Switches layout based on [isLandscape]:
 *
 * **Portrait** — A non-scrollable 16:9 video surface sits at the top of the
 * screen, filling the full width. Below it, [PlayerInfoSection] shows the
 * title, uploader, and the [PlayerActionRow]. The entire column is scrollable
 * so future additions (comments, recommended videos) can be placed beneath.
 *
 * **Landscape** — [PlayerVideoSurface] expands to fill the entire screen.
 *
 * Both layouts delegate all video rendering and control interaction to
 * [PlayerVideoSurface], which owns the [AndroidView] and the
 * [PlayerControlsOverlay].
 *
 * @param uiState            Current player phase and stream metadata.
 * @param player             ExoPlayer instance owned by [PlayerService].
 * @param isLandscape        True when the device is in landscape orientation.
 * @param onBack             Back / collapse navigation.
 * @param onPlay             Service play delegate.
 * @param onPause            Service pause delegate.
 * @param onSeek             Service seekTo delegate.
 * @param onRetry            Re-triggers stream extraction on error.
 * @param onSetQuality       Triggers a quality-tier switch in [PlayerService].
 * @param onToggleFullscreen Requests an orientation change from [PlayerActivity].
 */
@Composable
fun PlayerScreen(
    uiState            : PlayerUiState,
    player             : ExoPlayer?,
    isLandscape        : Boolean,
    onBack             : () -> Unit,
    onPlay             : () -> Unit,
    onPause            : () -> Unit,
    onSeek             : (Long) -> Unit,
    onRetry            : () -> Unit,
    onSetQuality       : (Int) -> Unit,
    onToggleFullscreen : () -> Unit,
) {
    if (isLandscape) {
        PlayerVideoSurface(
            uiState            = uiState,
            player             = player,
            isLandscape        = true,
            onBack             = onBack,
            onPlay             = onPlay,
            onPause            = onPause,
            onSeek             = onSeek,
            onRetry            = onRetry,
            onSetQuality       = onSetQuality,
            onToggleFullscreen = onToggleFullscreen,
            modifier           = Modifier.fillMaxSize(),
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            PlayerVideoSurface(
                uiState            = uiState,
                player             = player,
                isLandscape        = false,
                onBack             = onBack,
                onPlay             = onPlay,
                onPause            = onPause,
                onSeek             = onSeek,
                onRetry            = onRetry,
                onSetQuality       = onSetQuality,
                onToggleFullscreen = onToggleFullscreen,
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            PlayerInfoSection(
                title        = uiState.title,
                uploaderName = uiState.uploaderName,
                uploaderUrl  = uiState.uploaderUrl,
                modifier     = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}