package com.nidoham.bondhu.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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
 * Replaces the previous `ServiceConnection` + `PlayerBinder` approach with
 * [MediaBrowser] — the official Media3 client for
 * [androidx.media3.session.MediaLibraryService].
 *
 * [MediaBrowser] implements [Player], so it is passed directly to
 * `PlayerView` without exposing `ExoPlayer` across the process boundary.
 *
 * ## State architecture
 * [PlayerUiState] is assembled from two independent sources:
 *
 * - **[Player.Listener.onEvents]** — standard playback state that [MediaBrowser]
 *   delivers synchronously (playing, buffering, duration).
 *
 * - **[MediaBrowser.Listener.onExtrasChanged]** — custom service state
 *   (loading phase, title, uploader info, quality list, error) broadcast by
 *   [PlayerService] via `mediaLibrarySession.setSessionExtras(bundle)`.
 *   [PlayerUiState.fromBundle] deserializes it.
 *
 * ## Quality selection
 * Delegated as a [SessionCommand] with [PlayerService.CMD_SET_QUALITY].
 *
 * ## Retry
 * Sent as [PlayerService.CMD_RETRY], bypassing the URL-deduplication guard
 * in [PlayerService.onStartCommand].
 *
 * ## Connection lifecycle
 * [MediaBrowser.releaseFuture] in [onCleared] safely handles both the pending
 * and resolved future cases. The ViewModel does NOT stop the service on clear —
 * playback continues in the background.
 *
 * @param application Application context passed through [AndroidViewModel].
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── Player (MediaBrowser implements Player) ────────────────────────────────

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    // ── MediaBrowser ──────────────────────────────────────────────────────────

    private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null

    private inline val browser: MediaBrowser?
        get() = mediaBrowserFuture
            ?.takeIf { it.isDone && !it.isCancelled }
            ?.runCatching { get() }
            ?.getOrNull()

    private var stateJob: Job? = null

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Derives standard playback state from [MediaBrowser] using [Player.Listener.onEvents]
     * batching — one [_uiState] write per message-queue turn regardless of how
     * many individual events fire together.
     */
    private val playerListener = object : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            var state = _uiState.value

            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                state = state.copy(isPlaying = player.isPlaying)
            }

            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                state = when (player.playbackState) {
                    Player.STATE_BUFFERING -> state.copy(isBuffering = true)
                    Player.STATE_READY     -> state.copy(
                        isBuffering = false,
                        durationMs  = player.duration
                            .takeIf { it != C.TIME_UNSET }
                            ?.coerceAtLeast(0L)
                            ?: state.durationMs,
                    )
                    Player.STATE_ENDED     -> state.copy(
                        isPlaying         = false,
                        isBuffering       = false,
                        currentPositionMs = 0L,
                    )
                    Player.STATE_IDLE      -> state.copy(isBuffering = false)
                    else                   -> state
                }
            }

            if (state !== _uiState.value) _uiState.value = state
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Player error via MediaBrowser [code=${error.errorCode}]")
            _uiState.value = _uiState.value.copy(
                phase = PlayerUiState.Phase.Error,
                error = error.localizedMessage ?: "Playback error.",
            )
        }
    }

    /**
     * Receives custom service state broadcast by [PlayerService] via
     * `mediaLibrarySession.setSessionExtras(bundle)`.
     *
     * Merges service-owned fields into [_uiState] while preserving the
     * already-settled [PlayerUiState.isPlaying] and [PlayerUiState.isBuffering]
     * from [playerListener] to avoid stale overrides.
     */
    private val browserListener = object : MediaBrowser.Listener {

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            _uiState.value = PlayerUiState.fromBundle(extras).copy(
                isPlaying   = _uiState.value.isPlaying,
                isBuffering = _uiState.value.isBuffering,
            )
        }

        override fun onDisconnected(controller: MediaController) {
            _player.value = null
            Timber.w("MediaBrowser disconnected from PlayerService")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts [PlayerService] as a foreground service and connects [MediaBrowser]
     * to its [androidx.media3.session.MediaLibraryService.MediaLibrarySession].
     *
     * Safe to call on every activity creation — the service deduplicates loads
     * for the same URL and the [MediaBrowser] connection is established only once
     * per ViewModel lifetime.
     *
     * @param streamUrl YouTube page URL forwarded to the service for extraction.
     * @param title     Optional display title shown while stream metadata loads.
     */
    fun initPlayer(streamUrl: String, title: String?) {
        val context = getApplication<Application>()
        context.startForegroundService(buildServiceIntent(context, streamUrl, title))

        if (mediaBrowserFuture != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlayerService::class.java),
        )

        val future = MediaBrowser.Builder(context, sessionToken)
            .setListener(browserListener)
            .buildAsync()

        mediaBrowserFuture = future

        future.addListener(
            {
                val b = runCatching { future.get() }.getOrElse { e ->
                    Timber.e(e, "MediaBrowser connection failed")
                    return@addListener
                }
                b.addListener(playerListener)
                _player.value = b
                Timber.d("MediaBrowser connected to PlayerService")
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun play()  { browser?.play() }
    fun pause() { browser?.pause() }
    fun seekTo(positionMs: Long) { browser?.seekTo(positionMs) }

    fun togglePlayPause() {
        val b = browser ?: return
        if (b.isPlaying) b.pause() else b.play()
    }

    fun skipForward(deltaMs: Long = 10_000L) {
        val b   = browser ?: return
        val cap = b.duration.takeIf { it != C.TIME_UNSET } ?: return
        b.seekTo((b.currentPosition + deltaMs).coerceAtMost(cap))
    }

    fun skipBackward(deltaMs: Long = 10_000L) {
        val b = browser ?: return
        b.seekTo((b.currentPosition - deltaMs).coerceAtLeast(0L))
    }

    /**
     * Switches the active video stream to the quality at [index] in
     * [PlayerUiState.availableQualities] via a [SessionCommand].
     *
     * No-op when the quality list is empty (HLS/DASH sources manage quality
     * internally via adaptive bitrate).
     *
     * @param index Index into [PlayerUiState.availableQualities].
     */
    fun setQuality(index: Int) {
        browser?.sendCustomCommand(
            SessionCommand(PlayerService.CMD_SET_QUALITY, Bundle.EMPTY),
            Bundle().apply { putInt(PlayerService.ARG_QUALITY_INDEX, index) },
        )
    }

    /**
     * Retries extraction and playback after an error via a [SessionCommand],
     * bypassing the URL-deduplication guard in [PlayerService.onStartCommand].
     */
    fun retry() {
        if (_uiState.value.phase != PlayerUiState.Phase.Error) return
        browser?.sendCustomCommand(
            SessionCommand(PlayerService.CMD_RETRY, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        stateJob?.cancel()
        browser?.removeListener(playerListener)
        mediaBrowserFuture?.let { MediaBrowser.releaseFuture(it) }
        mediaBrowserFuture = null
        super.onCleared()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildServiceIntent(
        context  : Context,
        streamUrl: String,
        title    : String?,
    ): Intent = Intent(context, PlayerService::class.java).apply {
        putExtra(NavigationHelper.EXTRA_STREAM_URL, streamUrl)
        title?.let { putExtra(NavigationHelper.EXTRA_TITLE, it) }
    }
}