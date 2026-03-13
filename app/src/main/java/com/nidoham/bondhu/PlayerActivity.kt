package com.nidoham.bondhu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nidoham.bondhu.player.state.PlayerUiState
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.viewmodel.PlayerViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen video player activity.
 *
 * Reads [NavigationHelper.EXTRA_STREAM_URL] and [NavigationHelper.EXTRA_TITLE] from the
 * launching intent, then delegates all extraction and playback to [PlayerService] through
 * [PlayerViewModel]. The activity is intentionally thin: it starts the service, observes
 * state, and forwards user interactions back to the ViewModel.
 *
 * [PlayerView] (Media3) is wired to the [ExoPlayer] instance owned by [PlayerService]
 * rather than creating its own player — this ensures playback survives activity recreation.
 */
@AndroidEntryPoint
class PlayerActivity : BaseActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val streamUrl = intent.getStringExtra(NavigationHelper.EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)

        setContent {
            AppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val player  by viewModel.player.collectAsStateWithLifecycle()

                // Binds to the service exactly once on initial composition.
                LaunchedEffect(Unit) {
                    viewModel.initPlayer(streamUrl, title)
                }

                PlayerScreen(
                    uiState = uiState,
                    player  = player,
                    onBack  = { finish() },
                    onRetry = viewModel::retry,
                    onPlay  = viewModel::play,
                    onPause = viewModel::pause,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Player screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Root composable for the player UI.
 *
 * Renders one of three states based on [uiState.phase]: a loading spinner,
 * a full-screen error with retry, or the active [PlayerView]. The top bar sits
 * in a [Column] *above* the [AndroidView] — never overlapping it — so [PlayerView]'s
 * touch interceptor cannot consume back-button taps.
 *
 * @param uiState Current player state from [PlayerViewModel].
 * @param player  ExoPlayer instance owned by [PlayerService]; null until binding completes.
 * @param onBack  Called when the user taps the back arrow.
 * @param onRetry Called when the user taps "Try again" on the error state.
 * @param onPlay  Requests the service to resume playback.
 * @param onPause Requests the service to pause playback.
 */
@Composable
private fun PlayerScreen(
    uiState : PlayerUiState,
    player  : ExoPlayer?,
    onBack  : () -> Unit,
    onRetry : () -> Unit,
    onPlay  : () -> Unit,
    onPause : () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        PlayerTopBar(title = uiState.title, onBack = onBack)

        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (uiState.phase) {
                PlayerUiState.Phase.Idle,
                PlayerUiState.Phase.Loading -> PlayerLoadingState()

                PlayerUiState.Phase.Error   -> PlayerErrorState(
                    message = uiState.error ?: "An unknown error occurred.",
                    onRetry = onRetry,
                )

                PlayerUiState.Phase.Ready   -> PlayerReadyState(
                    player  = player,
                    onPlay  = onPlay,
                    onPause = onPause,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayerTopBar(title: String, onBack: () -> Unit) {
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
        if (title.isNotBlank()) {
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
}

@Composable
private fun PlayerLoadingState() {
    CircularProgressIndicator(
        modifier    = Modifier.size(36.dp),
        strokeWidth = 2.5.dp,
        color       = Color.White,
    )
}

@Composable
private fun PlayerErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier            = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text       = "Playback failed",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            textAlign  = TextAlign.Center,
        )
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick  = onRetry,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(
                text       = "Try again",
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Hosts a [PlayerView] wired to [player].
 * Falls back to the loading spinner until the service delivers the ExoPlayer instance.
 * The [update] lambda re-wires the player reference on service restarts without
 * recreating the [AndroidView].
 */
@Composable
private fun PlayerReadyState(player: ExoPlayer?, onPlay: () -> Unit, onPause: () -> Unit) {
    if (player == null) { PlayerLoadingState(); return }

    AndroidView(
        factory  = { ctx ->
            PlayerView(ctx).apply {
                this.player   = player
                useController = true
                keepScreenOn  = true
            }
        },
        update   = { view -> view.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}