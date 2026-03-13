@file:OptIn(UnstableApi::class)

package com.nidoham.bondhu.player

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nidoham.bondhu.player.state.PlayerUiState
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.extractor.stream.StreamExtractor
import com.nidoham.extractor.stream.Streams
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that owns the [ExoPlayer] instance and manages the full
 * stream lifecycle: metadata extraction → URL resolution → playback.
 *
 * Extends [MediaSessionService] — the modern Media3 replacement for plain
 * [android.app.Service]. Benefits over the old approach:
 * - Notification and foreground promotion are handled automatically via
 *   [MediaSession]; no [androidx.media3.ui.PlayerNotificationManager] or
 *   manual [startForeground] calls are needed.
 * - The session is accessible to system UI, Wear OS, and Android Auto via
 *   [onGetSession].
 *
 * Internal consumers (e.g. the ViewModel) bind using [ACTION_BIND] to receive
 * the [PlayerBinder] and call [loadAndPlay] / [play] / [pause] directly.
 * External Media3 clients (MediaController) use the normal session token path
 * via the base-class [onBind] handler.
 *
 * ## Lifecycle note
 * [onDestroy] releases both [exoPlayer] and [mediaSession], and cancels the
 * [serviceScope] to prevent coroutine leaks.
 */
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    @Inject
    lateinit var streamExtractor: StreamExtractor

    // ── ExoPlayer + MediaSession ──────────────────────────────────────────────

    lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── Coroutines ────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    /** Prevents redundant re-extraction on config changes / rebinds. */
    private var currentUrl: String = ""

    // ── Binder (internal ViewModel binding) ───────────────────────────────────

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = PlayerBinder()

    companion object {
        /**
         * Intent action used by the ViewModel when binding to this service.
         * The base class reserves the default action for MediaController clients.
         */
        const val ACTION_BIND = "com.nidoham.bondhu.player.ACTION_BIND"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        exoPlayer    = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        setupPlayerListener()
    }

    /**
     * Returns the active [MediaSession] to Media3 / system clients.
     * Called by the base class whenever a [androidx.media3.session.MediaController]
     * connects.
     */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession = mediaSession

    /**
     * Routes binding requests:
     * - [ACTION_BIND] → [PlayerBinder] for ViewModel direct access.
     * - Anything else → delegated to [MediaSessionService.onBind] for
     *   standard MediaController / system UI clients.
     */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder
        else super.onBind(intent)

    /**
     * Reads [NavigationHelper.EXTRA_STREAM_URL] and [NavigationHelper.EXTRA_TITLE]
     * from the intent, then delegates to [loadAndPlay] when the URL is new.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val url   = intent?.getStringExtra(NavigationHelper.EXTRA_STREAM_URL)
            ?: return START_NOT_STICKY
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)
        if (url != currentUrl) loadAndPlay(url, title)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        exoPlayer.release()
        stopProgressUpdates()
        loadJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun play()                   { exoPlayer.play() }
    fun pause()                  { exoPlayer.pause() }
    fun seekTo(positionMs: Long) { exoPlayer.seekTo(positionMs) }

    /**
     * Cancels any in-flight extraction, resets state to [PlayerUiState.Phase.Loading],
     * then fetches stream metadata for [pageUrl] and begins playback.
     *
     * @param pageUrl YouTube video page URL (e.g. `https://youtube.com/watch?v=ID`).
     * @param title   Optional title shown while loading; overridden once extraction
     *                returns the real title.
     */
    fun loadAndPlay(pageUrl: String, title: String?) {
        loadJob?.cancel()
        currentUrl = pageUrl

        loadJob = serviceScope.launch {
            _uiState.value = PlayerUiState(
                phase = PlayerUiState.Phase.Loading,
                title = title.orEmpty(),
            )

            streamExtractor.fetchStream(pageUrl)
                .onSuccess { data ->
                    val playbackUrl = data.stream.resolvePlaybackUrl()
                    if (playbackUrl == null) {
                        Timber.w("No playable URL found for: $pageUrl")
                        _uiState.value = _uiState.value.copy(
                            phase = PlayerUiState.Phase.Error,
                            error = "No playable stream found for this video.",
                        )
                        return@onSuccess
                    }

                    val resolvedTitle = title
                        ?.takeIf { it.isNotBlank() }
                        ?: data.stream.title

                    withContext(Dispatchers.Main) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(playbackUrl))
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }

                    _uiState.value = PlayerUiState(
                        phase        = PlayerUiState.Phase.Ready,
                        title        = resolvedTitle,
                        uploaderName = data.stream.uploaderName,
                        uploaderUrl  = data.stream.uploaderAvatars
                            .maxByOrNull { it.height }?.url,
                        thumbnailUrl = data.stream.thumbnails
                            .maxByOrNull { it.height }?.url,
                        duration     = data.stream.duration,
                        isPlaying    = true,
                    )
                }
                .onFailure { error ->
                    Timber.e(error, "Stream extraction failed for: $pageUrl")
                    _uiState.value = _uiState.value.copy(
                        phase = PlayerUiState.Phase.Error,
                        error = error.localizedMessage ?: "Failed to load stream.",
                    )
                }
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = serviceScope.launch {
            while (true) {
                if (exoPlayer.isPlaying) {
                    _uiState.value = _uiState.value.copy(
                        duration = exoPlayer.duration.coerceAtLeast(0L) / 1000,
                        // We could add a currentPosition field to PlayerUiState if needed
                    )
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── ExoPlayer listener ────────────────────────────────────────────────────

    private fun setupPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> _uiState.value =
                        _uiState.value.copy(isBuffering = true)

                    Player.STATE_READY     -> _uiState.value =
                        _uiState.value.copy(isBuffering = false)

                    Player.STATE_ENDED     -> _uiState.value =
                        _uiState.value.copy(isPlaying = false, isBuffering = false)

                    else                   -> Unit
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e(error, "ExoPlayer playback error")
                _uiState.value = _uiState.value.copy(
                    phase = PlayerUiState.Phase.Error,
                    error = error.localizedMessage ?: "Playback error.",
                )
            }
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Streams extension
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the best available playback URL from this [Streams] object.
 *
 * Priority chain (highest → lowest):
 * 1. HLS manifest — adaptive, natively supported by ExoPlayer.
 * 2. DASH manifest — adaptive, higher ceiling but requires DASH parsing.
 * 3. Best progressive video stream — direct URL, sorted by height.
 * 4. Best audio-only stream — last resort for audio-only content.
 *
 * Returns `null` if none of the above are available.
 */
private fun Streams.resolvePlaybackUrl(): String? =
    hlsUrl?.takeIf { it.isNotBlank() }
        ?: dashMpdUrl?.takeIf { it.isNotBlank() }
        ?: videoStreams.maxByOrNull { it.height }?.content?.takeIf { it.isNotBlank() }
        ?: audioStreams.maxByOrNull { it.averageBitrate }?.content?.takeIf { it.isNotBlank() }