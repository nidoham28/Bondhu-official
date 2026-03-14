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
 * Binding registration is tracked by [isBindingRegistered], which is set
 * immediately after [Context.bindService] succeeds. This is distinct from
 * [playerService] being non-null (the connection being active), so that
 * [onCleared] can safely call [Context.unbindService] even after
 * [ServiceConnection.onServiceDisconnected] fires due to a service crash.
 *
 * ## State bridging
 * Once bound, a collection job mirrors [PlayerService.uiState] into [uiState].
 * [player] exposes the [ExoPlayer] instance for wiring into a PlayerView.
 *
 * ## Quality selection
 * [setQuality] delegates to [PlayerService.setQuality]. The call is a no-op
 * when the active source is HLS/DASH (i.e. [PlayerUiState.availableQualities]
 * is empty), since adaptive bitrate handles quality internally in those cases.
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

    private var playerService: PlayerService? = null

    /**
     * Tracks whether [Context.bindService] has been called and the binding is
     * still registered. Set to `true` immediately after [bindService] returns
     * successfully and cleared only after [Context.unbindService] is called.
     *
     * This must not be set inside [ServiceConnection.onServiceConnected], because
     * [ServiceConnection.onServiceDisconnected] resets the connection state on a
     * service crash while the binding itself remains registered. Using the callback
     * to gate [unbindService] would cause a binding leak on crash + ViewModel clear.
     */
    private var isBindingRegistered: Boolean = false

    private var stateJob: Job? = null

    /** Preserved so [retry] can resubmit the original page URL. */
    private var lastStreamUrl: String = ""

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlayerService.PlayerBinder).getService()
            playerService = service
            _player.value = service.exoPlayer

            // Cancel any stale collection job before starting a new one.
            stateJob?.cancel()
            stateJob = viewModelScope.launch {
                service.uiState.collect { _uiState.value = it }
            }
            Timber.d("PlayerService bound")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The service has crashed or been killed. The binding is still
            // registered — do NOT set isBindingRegistered = false here.
            // onCleared() will call unbindService() when the ViewModel is cleared.
            playerService = null
            _player.value = null
            stateJob?.cancel()
            Timber.d("PlayerService disconnected unexpectedly")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts [PlayerService] as a foreground service and binds to it. Safe to
     * call on activity recreation — if a binding is already registered the call
     * is ignored, and the service itself deduplicates loads for the same URL.
     *
     * @param streamUrl YouTube page URL forwarded to the service for extraction.
     * @param title     Optional display title used while stream metadata loads.
     */
    fun initPlayer(streamUrl: String, title: String?) {
        lastStreamUrl = streamUrl
        val context = getApplication<Application>()

        context.startForegroundService(buildServiceIntent(context, streamUrl, title))

        // Guard against accumulating duplicate bindings on activity recreation.
        if (isBindingRegistered) return

        val bindIntent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_BIND
        }
        if (context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)) {
            isBindingRegistered = true
        } else {
            Timber.e("bindService returned false — PlayerService unavailable")
        }
    }

    fun play()                   { playerService?.play() }
    fun pause()                  { playerService?.pause() }
    fun seekTo(positionMs: Long) { playerService?.seekTo(positionMs) }

    /**
     * Switches the active video stream to the quality at [index] in
     * [PlayerUiState.availableQualities]. Playback position is preserved
     * seamlessly inside [PlayerService.setQuality].
     *
     * No-op when [playerService] is null or the quality list is empty
     * (HLS/DASH sources use adaptive bitrate automatically).
     *
     * @param index Index into [PlayerUiState.availableQualities].
     */
    fun setQuality(index: Int) { playerService?.setQuality(index) }

    /**
     * Resubmits [lastStreamUrl] to [PlayerService] when the player is in an
     * error state. Uses the original page URL rather than the resolved title,
     * which is not a valid stream URL.
     */
    fun retry() {
        val service = playerService ?: return
        val state = _uiState.value
        if (state.phase == PlayerUiState.Phase.Error && lastStreamUrl.isNotBlank()) {
            service.loadAndPlay(
                pageUrl = lastStreamUrl,
                title   = state.title.takeIf { it.isNotBlank() },
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stateJob?.cancel()
        if (isBindingRegistered) {
            getApplication<Application>().unbindService(connection)
            isBindingRegistered = false
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