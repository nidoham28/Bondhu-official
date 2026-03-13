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
 * Compose UI via [StateFlow]s. [AndroidViewModel] is used intentionally —
 * starting a foreground service and binding to it both require an [Application]
 * context that must outlive any single activity.
 *
 * ## Service binding
 * [initPlayer] starts the service via [startForegroundService], then binds
 * using [PlayerService.ACTION_BIND]. That action causes [PlayerService.onBind]
 * to return a [PlayerService.PlayerBinder] rather than delegating to the
 * Media3 session path inside [androidx.media3.session.MediaSessionService].
 *
 * ## State bridging
 * Once bound, a collection job mirrors [PlayerService.uiState] into [uiState].
 * [player] exposes the [ExoPlayer] instance for wiring into a PlayerView.
 *
 * ## Service lifetime
 * The service is unbound in [onCleared] but is not stopped — playback continues
 * in the background. The notification dismiss action inside [PlayerService]
 * is responsible for calling [stopSelf].
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

    // ── ExoPlayer instance (exposed for PlayerView wiring) ────────────────────

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    // ── Service binding ───────────────────────────────────────────────────────

    private var playerService : PlayerService? = null
    private var isBound       : Boolean        = false
    private var stateJob      : Job?           = null

    /** Preserved so [retry] can resubmit the original page URL, not the resolved title. */
    private var lastStreamUrl : String         = ""

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service   = (binder as PlayerService.PlayerBinder).getService()
            playerService = service
            isBound       = true
            _player.value = service.exoPlayer

            // Mirror service state into our own flow so the UI has a single,
            // stable reference that survives activity recreations and rebinds.
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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts [PlayerService] as a foreground service and binds to it using
     * [PlayerService.ACTION_BIND]. Safe to call on activity recreation — the
     * service deduplicates loads for the same URL.
     *
     * @param streamUrl YouTube page URL forwarded to the service for extraction.
     * @param title     Optional display title used while stream metadata loads.
     */
    fun initPlayer(streamUrl: String, title: String?) {
        lastStreamUrl = streamUrl
        val context = getApplication<Application>()

        // Start intent carries the stream URL for onStartCommand.
        val startIntent = buildServiceIntent(context, streamUrl, title)
        context.startForegroundService(startIntent)

        // Bind intent uses ACTION_BIND so MediaSessionService.onBind routes
        // to our PlayerBinder instead of the Media3 session path.
        val bindIntent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_BIND
        }
        context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
    }

    fun play()                   { playerService?.play() }
    fun pause()                  { playerService?.pause() }
    fun seekTo(positionMs: Long) { playerService?.seekTo(positionMs) }

    /**
     * Resubmits [lastStreamUrl] to [PlayerService] when the player is in an
     * error state. Uses the original page URL rather than the resolved title,
     * which is not a valid stream URL.
     */
    fun retry() {
        val service = playerService ?: return
        if (_uiState.value.phase == PlayerUiState.Phase.Error && lastStreamUrl.isNotBlank()) {
            service.loadAndPlay(
                pageUrl = lastStreamUrl,
                title   = _uiState.value.title.takeIf { it.isNotBlank() },
            )
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildServiceIntent(
        context   : Context,
        streamUrl : String,
        title     : String?,
    ): Intent = Intent(context, PlayerService::class.java).apply {
        putExtra(NavigationHelper.EXTRA_STREAM_URL, streamUrl)
        title?.let { putExtra(NavigationHelper.EXTRA_TITLE, it) }
    }
}