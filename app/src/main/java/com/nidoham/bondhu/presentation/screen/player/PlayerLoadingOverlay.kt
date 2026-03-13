package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centered YouTube-style buffering indicator.
 *
 * Displayed during [com.nidoham.bondhu.player.state.PlayerUiState.Phase.Loading]
 * and while [PlayerUiState.isBuffering] is true mid-playback. The spinner uses
 * a white stroke on a transparent background so it sits cleanly over the video.
 */
@Composable
fun PlayerLoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier    = Modifier.size(44.dp),
            strokeWidth = 3.dp,
            color       = Color.White,
        )
    }
}