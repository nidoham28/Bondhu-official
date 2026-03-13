package com.nidoham.bondhu.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nidoham.bondhu.player.PlayerService
import com.nidoham.bondhu.player.state.PlayerUiState
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for [com.nidoham.bondhu.PlayerActivity].
 *
 * Manages the binding lifecycle for [PlayerService] and bridges its state to
 * Compose UI via [StateFlow]s. Using [AndroidViewModel] is intentional here —
 * starting a foreground service and binding to it both require an [Application]
 * context, which must outlive any single activity.
 *
 * ## State bridging
 * Once the [ServiceConnection] delivers a [PlayerService] reference, the ViewModel
 * launches a collection job that mirrors [PlayerService.uiState] into [uiState].
 * The [player] flow exposes the service's [ExoPlayer] instance so the Compose UI
 * can wire it into [androidx.media3.ui.PlayerView] via [AndroidView].
 *
 * ## Service lifetime
 * [initPlayer] starts the service as a foreground service (required on API 26+)
 * before binding. The service is unbound in [onCleared] but is *not* stopped —
 * playback continues in the background. Stopping is triggered by the notification
 * dismiss action inside [PlayerService].
 *
 * @param application Application context passed through [AndroidViewModel].
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    // ── UI state (mirrored from service) ──────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── ExoPlayer instance (exposed for AndroidView wiring) ───────────────────

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    // ── Service binding ───────────────────────────────────────────────────────

    private var playerService  : PlayerService? = null
    private var isBound        : Boolean        = false
    private var stateJob       : Job?           = null

    /** Cached so [retry] can re-submit the original URL rather than the resolved title. */
    private var lastStreamUrl  : String         = ""

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlayerService.PlayerBinder).getService()
            playerService = service
            isBound       = true
            _player.value = service.exoPlayer

            // Mirror service state into our own StateFlow so the UI has a
            // single, stable reference that survives rebinds.
            stateJob?.cancel()
            stateJob = viewModelScope.launch {
                service.uiState.collect { _uiState.value = it }
            }
            Timber.d("PlayerService bound")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playerService = null
            isBound       = false
            _player.value = null
            stateJob?.cancel()
            Timber.d("PlayerService disconnected")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Starts [PlayerService] as a foreground service and binds to it.
     *
     * Safe to call multiple times — the service ignores duplicate loads for the
     * same URL, so calling this on activity recreation is harmless.
     *
     * @param streamUrl YouTube page URL forwarded to the service for extraction.
     * @param title     Optional display title used while stream info is loading.
     */
    fun initPlayer(streamUrl: String, title: String?) {
        lastStreamUrl = streamUrl
        val context = getApplication<Application>()
        val intent  = buildServiceIntent(context, streamUrl, title)

        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun play()                   { playerService?.play() }
    fun pause()                  { playerService?.pause() }
    fun seekTo(positionMs: Long) { playerService?.seekTo(positionMs) }

    /**
     * Re-submits the original stream URL to [PlayerService] when the player is
     * in an error state. Uses [lastStreamUrl] — the URL passed to [initPlayer] —
     * rather than the resolved title, which is not a valid page URL.
     */
    fun retry() {
        val service = playerService ?: return
        val state   = _uiState.value
        if (state.phase == PlayerUiState.Phase.Error && lastStreamUrl.isNotBlank()) {
            service.loadAndPlay(lastStreamUrl, state.title.takeIf { it.isNotBlank() })
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stateJob?.cancel()
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildServiceIntent(
        context   : Context,
        streamUrl : String,
        title     : String?,
    ): Intent = Intent(context, PlayerService::class.java).apply {
        putExtra(NavigationHelper.EXTRA_STREAM_URL, streamUrl)
        title?.let { putExtra(NavigationHelper.EXTRA_TITLE, it) }
    }
}