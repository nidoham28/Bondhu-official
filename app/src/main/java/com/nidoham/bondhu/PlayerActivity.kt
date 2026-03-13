package com.nidoham.bondhu

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * from the intent and delegates all extraction and playback to [PlayerService]
 * through [PlayerViewModel]. The activity itself is intentionally thin: it
 * manages orientation, system UI visibility, and wires the Compose tree.
 *
 * ## Orientation behaviour
 * Portrait shows the standard layout with system bars visible. Landscape hides
 * all system bars via [WindowInsetsControllerCompat] for a true immersive
 * experience. [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] prevents
 * the display from dimming during playback in either orientation.
 *
 * ## Config changes
 * `orientation`, `screenSize`, and `screenLayout` are handled in-process
 * (declared in the manifest) so the activity is never recreated on rotation.
 * The Compose tree reacts to the new [LocalConfiguration] automatically.
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
                val uiState    by viewModel.uiState.collectAsStateWithLifecycle()
                val player     by viewModel.player.collectAsStateWithLifecycle()
                val config      = LocalConfiguration.current
                val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

                // Hide or restore system bars whenever orientation changes.
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

                // Bind to PlayerService once on initial composition.
                LaunchedEffect(Unit) {
                    viewModel.initPlayer(streamUrl, title)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    PlayerScreen(
                        uiState            = uiState,
                        player             = player,
                        isLandscape        = isLandscape,
                        onBack             = {
                            if (isLandscape) {
                                // Return to portrait explicitly rather than delegating
                                // to SCREEN_ORIENTATION_UNSPECIFIED, which is ambiguous
                                // on devices with rotation lock enabled.
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
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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