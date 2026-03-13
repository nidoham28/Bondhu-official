package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-surface error state displayed over the player when stream extraction or
 * playback fails.
 *
 * Shows an error icon, a short heading, the raw [message] from the ViewModel,
 * and a "Try again" [TextButton] that triggers [onRetry].
 *
 * @param message  Human-readable error description forwarded from [PlayerUiState.error].
 * @param onRetry  Called when the user taps the retry button.
 * @param modifier Applied to the root [Column].
 */
@Composable
fun PlayerErrorOverlay(
    message  : String,
    onRetry  : () -> Unit,
    modifier : Modifier = Modifier,
) {
    Column(
        modifier            = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint               = Color.White.copy(alpha = 0.7f),
            modifier           = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text       = "Something went wrong",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onRetry) {
            Text(
                text       = "Try again",
                color      = YouTubeRed,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}