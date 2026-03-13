package com.nidoham.bondhu

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.player.PlayerScreen
import com.nidoham.bondhu.presentation.viewmodel.PlayerViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen video player activity.
 *
 * Reads [NavigationHelper.EXTRA_STREAM_URL] and [NavigationHelper.EXTRA_TITLE]
 * from the intent and delegates all extraction/playback to [PlayerService]
 * through [PlayerViewModel]. The activity itself is intentionally thin: it
 * manages orientation, system UI visibility, and wires the Compose tree.
 *
 * ## Orientation behaviour
 * - **Portrait** — standard layout with status bar visible.
 * - **Landscape** — system bars are hidden via [WindowInsetsControllerCompat]
 *   to achieve a true full-screen (immersive) experience, matching YouTube.
 *   [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] is set for both orientations
 *   so the display never dims during playback.
 *
 * ## Fullscreen toggle
 * The [PlayerScreen] exposes an `onToggleFullscreen` callback. When called in
 * portrait it rotates to landscape, and vice versa, via
 * [requestedOrientation]. Android then recreates the layout for the new
 * orientation; [PlayerViewModel] survives this via [ViewModel] + service binding.
 */
@AndroidEntryPoint
class PlayerActivity : BaseActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val streamUrl = intent.getStringExtra(NavigationHelper.EXTRA_STREAM_URL) ?: run {
            finish()
            return
        }
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)

        setContent {
            AppTheme {
                val uiState     by viewModel.uiState.collectAsStateWithLifecycle()
                val player      by viewModel.player.collectAsStateWithLifecycle()
                val config       = LocalConfiguration.current
                val isLandscape  = config.orientation == Configuration.ORIENTATION_LANDSCAPE

                // Hide/show system bars based on orientation.
                LaunchedEffect(isLandscape) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    if (isLandscape) {
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                // Bind to PlayerService exactly once on initial composition.
                LaunchedEffect(Unit) {
                    viewModel.initPlayer(streamUrl, title)
                }

                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
                    PlayerScreen(
                        uiState            = uiState,
                        player             = player,
                        isLandscape        = isLandscape,
                        onBack             = {
                            if (isLandscape) {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            } else {
                                finish()
                            }
                        },
                        onPlay             = viewModel::play,
                        onPause            = viewModel::pause,
                        onSeek             = viewModel::seekTo,
                        onRetry            = viewModel::retry,
                        onToggleFullscreen = {
                            requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        },
                    )
                }

            }
        }
    }
}