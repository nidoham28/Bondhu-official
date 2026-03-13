package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Composable that hosts a Media3 [ExoPlayer] for the given [streamUrl].
 *
 * The top bar and [PlayerView] are laid out in a [Column] rather than overlapping
 * in a [Box]. This avoids [PlayerView]'s touch interception swallowing taps intended
 * for the back button — the root cause of unresponsive controls when mixing Compose
 * with Media3.
 *
 * The player is created once via [remember], released on disposal via [DisposableEffect],
 * and kept alive across recompositions.
 *
 * @param streamUrl Direct stream URL to load into the player.
 * @param title     Optional video title displayed in the top bar.
 * @param onBack    Called when the user taps the back arrow.
 */
@Composable
fun PlayerScreen(
    streamUrl : String,
    title     : String?,
    onBack    : () -> Unit,
) {
    val context = LocalContext.current

    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // Top bar — pure Compose surface, sits above AndroidView, always receives clicks.
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint               = Color.White,
                )
            }
            if (!title.isNullOrBlank()) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.padding(end = 16.dp),
                )
            }
        }

        // Player fills all remaining vertical space.
        AndroidView(
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    player        = exoPlayer
                    useController = true
                    keepScreenOn  = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}