@file:Suppress("UnsafeOptInUsageError")

package com.nidoham.bondhu.player

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that owns the [ExoPlayer] instance and manages the full
 * stream lifecycle: metadata extraction → URL resolution → playback.
 *
 * Extends [MediaSessionService] so the system handles foreground promotion and
 * the media notification automatically via [MediaSession]. No manual
 * [startForeground] or [androidx.media3.ui.PlayerNotificationManager] calls
 * are required.
 *
 * ## Key design points
 * - [SimpleCache] (512 MB LRU) backed [CacheDataSource] eliminates redundant
 *   network re-fetches on seeks and rebuffers.
 * - [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER] + decoder fallback
 *   enables the FFmpeg extension for MPEG-4 Part 2 and other non-native codecs.
 * - [DefaultTrackSelector] with [AdaptiveTrackSelection] and explicit H.264
 *   codec preference for maximum MPEG-4 compatibility.
 * - [DefaultLoadControl] with tuned buffer windows for smooth streaming on
 *   variable mobile connections.
 * - [DefaultBandwidthMeter] drives adaptive bitrate; resets on network-type change.
 * - [AudioAttributes] with audio-focus delegation and noisy-audio handling —
 *   ExoPlayer pauses automatically on headphone disconnect.
 * - [PowerManager.WakeLock] prevents CPU sleep during background playback.
 * - [WifiManager.WifiLock] keeps the Wi-Fi radio at full performance.
 * - [MediaItem] is built with an explicit MIME type hint so that
 *   [DefaultMediaSourceFactory] skips content-type sniffing.
 * - Progress polling runs at 500 ms and tracks [ExoPlayer.currentPosition];
 *   both position and duration are reported in milliseconds to match
 *   ExoPlayer's native unit.
 * - Recoverable network errors trigger a [ExoPlayer.prepare] retry instead
 *   of surfacing an error state to the UI.
 * - [onDestroy] order is correct: locks released → coroutines canceled →
 *   session released → player stopped and released.
 *
 * ## Binding
 * ViewModels bind with [ACTION_BIND] to receive [PlayerBinder].
 * System / MediaController clients use the base-class session-token path.
 *
 * ## Cache ownership
 * [SimpleCache] is a process-wide singleton held in [Companion]. Call
 * [releaseCache] from your [android.app.Application] teardown or a Hilt
 * eager-singleton module if needed.
 */
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    @Inject lateinit var streamExtractor: StreamExtractor

    // ── ExoPlayer + MediaSession ──────────────────────────────────────────

    lateinit var exoPlayer: ExoPlayer
        private set
    private lateinit var mediaSession: MediaSession

    // ── System locks ──────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock?  = null

    // ── State ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── Coroutines ────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    /** Prevents redundant re-extraction when the same URL is delivered again. */
    private var currentUrl: String = ""

    // ── Binder ────────────────────────────────────────────────────────────

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = PlayerBinder()

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {

        /** Intent action used by ViewModels when binding directly to this service. */
        const val ACTION_BIND = "com.nidoham.bondhu.player.ACTION_BIND"

        // Buffer tuning (milliseconds)
        private const val MIN_BUFFER_MS                         = 15_000
        private const val MAX_BUFFER_MS                         = 50_000
        private const val BUFFER_FOR_PLAYBACK_MS                =  2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS =  5_000

        // Network timeouts (milliseconds)
        private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
        private const val HTTP_READ_TIMEOUT_MS    = 15_000

        // Progress polling interval (milliseconds)
        private const val PROGRESS_POLL_MS = 500L

        // Maximum on-disk cache size
        private const val CACHE_MAX_BYTES = 512L * 1024 * 1024 // 512 MB

        @Volatile private var sharedCache: SimpleCache? = null

        /**
         * Returns — or lazily creates — the process-wide [SimpleCache].
         * Thread-safe via double-checked locking.
         *
         * Uses [StandaloneDatabaseProvider] to satisfy the non-deprecated
         * three-argument [SimpleCache] constructor introduced in Media3 1.x.
         *
         * @param cacheDir Base directory; a `exo_stream_cache` subdirectory is
         *                 created automatically.
         */
        private fun acquireCache(cacheDir: File, context: android.content.Context): SimpleCache =
            sharedCache ?: synchronized(this) {
                sharedCache ?: SimpleCache(
                    File(cacheDir, "exo_stream_cache"),
                    LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(context),
                ).also { sharedCache = it }
            }

        /**
         * Releases the [SimpleCache] singleton and frees its disk resources.
         * Call from [android.app.Application.onTerminate] or a Hilt component
         * teardown — never from inside the service itself.
         */
        fun releaseCache() {
            sharedCache?.release()
            sharedCache = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        initPlayer()
        acquireLocks()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession = mediaSession

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val url   = intent?.getStringExtra(NavigationHelper.EXTRA_STREAM_URL)
            ?: return START_NOT_STICKY
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)
        if (url != currentUrl) loadAndPlay(url, title)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Correct teardown order: release locks and cancel coroutines first
        // so no in-flight work touches the player after it is released.
        releaseLocks()
        stopProgressUpdates()
        loadJob?.cancel()
        serviceScope.cancel()
        mediaSession.release()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.release()
        super.onDestroy()
    }

    // ── Initialization ────────────────────────────────────────────────────

    /**
     * Constructs and wires the full ExoPlayer stack: codec support, adaptive
     * bitrate, cache-backed data source, audio focus, and buffer tuning.
     */
    private fun initPlayer() {
        // Drives adaptive bitrate selection; resets when the network type changes
        // (e.g. Wi-Fi → mobile) to avoid overestimating available bandwidth.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(true)
            .build()

        // AdaptiveTrackSelection picks the quality tier best suited to current
        // bandwidth. H.264 (AVC) is the preferred MPEG-4 codec; mixed MIME
        // adaptiveness allows seamless codec switching inside HLS/DASH manifests.
        val trackSelector = DefaultTrackSelector(
            this, AdaptiveTrackSelection.Factory()
        ).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }

        // EXTENSION_RENDERER_MODE_PREFER loads the FFmpeg extension (when present
        // on the classpath) before the platform decoder, adding support for
        // MPEG-4 Part 2, VP8, Opus, and other non-native formats.
        // setEnableDecoderFallback drops to the next available decoder instead
        // of throwing when a preferred decoder fails to initialize.
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)

        // CacheDataSource sits between ExoPlayer and the network.
        // FLAG_IGNORE_CACHE_ON_ERROR falls back to the network when a cached
        // segment is corrupt, preventing a hard playback failure.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(acquireCache(cacheDir, applicationContext))
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(this, httpDataSourceFactory)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            // Pauses automatically when a headphone or Bluetooth device disconnects.
            .setHandleAudioBecomingNoisy(true)
            // Delegates audio-focus management to ExoPlayer — no manual
            // AudioManager.requestAudioFocus calls required.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        setupPlayerListener()
    }

    /**
     * Acquires a [PowerManager.WakeLock] and a [WifiManager.WifiLock] to keep
     * the CPU and Wi-Fi radio alive during background playback.
     *
     * Requires `WAKE_LOCK`, `ACCESS_WIFI_STATE`, and `CHANGE_WIFI_STATE` in
     * `AndroidManifest.xml`.
     */
    private fun acquireLocks() {
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bondhu:PlayerWakeLock")
            ?.also { it.acquire(3 * 60 * 60 * 1000L) } // 3-hour safety timeout

        @Suppress("DEPRECATION") // No backward-compatible replacement below API 29
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Bondhu:PlayerWifiLock")
            ?.also { it.acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun play()  { exoPlayer.play() }
    fun pause() { exoPlayer.pause() }
    fun seekTo(positionMs: Long) { exoPlayer.seekTo(positionMs) }

    /** Toggles between play and pause without requiring callers to track state. */
    @Suppress("unused")
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    /**
     * Seeks forward by [deltaMs] milliseconds, clamped to stream duration.
     * @param deltaMs Defaults to 10 seconds.
     */
    @Suppress("unused")
    fun skipForward(deltaMs: Long = 10_000L) {
        val cap = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtMost(cap))
    }

    /**
     * Seeks backward by [deltaMs] milliseconds, clamped to zero.
     * @param deltaMs Defaults to 10 seconds.
     */
    @Suppress("unused")
    fun skipBackward(deltaMs: Long = 10_000L) {
        exoPlayer.seekTo((exoPlayer.currentPosition - deltaMs).coerceAtLeast(0L))
    }

    /**
     * Cancels any in-flight extraction, resets state to [PlayerUiState.Phase.Loading],
     * then resolves the playback URL and begins ExoPlayer playback.
     *
     * @param pageUrl YouTube (or Piped-compatible) video page URL.
     * @param title   Optimistic title shown during loading; replaced by the real
     *                title once extraction succeeds.
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
                    val streamInfo = data.stream.resolveStream()
                    if (streamInfo == null) {
                        Timber.w("No playable stream for: $pageUrl")
                        _uiState.value = _uiState.value.copy(
                            phase = PlayerUiState.Phase.Error,
                            error = "No playable stream found for this video.",
                        )
                        return@onSuccess
                    }

                    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: data.stream.title

                    withContext(Dispatchers.Main) {
                        // Stop current playback before loading new media to
                        // avoid a brief audio overlap on rapid track switches.
                        exoPlayer.stop()
                        exoPlayer.setMediaItem(streamInfo.toMediaItem())
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }

                    _uiState.value = PlayerUiState(
                        phase        = PlayerUiState.Phase.Ready,
                        title        = resolvedTitle,
                        uploaderName = data.stream.uploaderName,
                        uploaderUrl  = data.stream.uploaderAvatars.maxByOrNull { it.height }?.url,
                        thumbnailUrl = data.stream.thumbnails.maxByOrNull { it.height }?.url,
                        durationMs   = data.stream.duration * 1000L,
                        currentPositionMs = 0L,
                        // isPlaying stays false here; the ExoPlayer listener
                        // flips it to true once STATE_READY fires — no race condition.
                        isPlaying    = false
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

    // ── Progress polling ──────────────────────────────────────────────────

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = serviceScope.launch {
            while (true) {
                if (exoPlayer.isPlaying) {
                    val rawDuration = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
                    _uiState.value = _uiState.value.copy(
                        currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs        = rawDuration?.coerceAtLeast(0L)
                            ?: _uiState.value.durationMs,
                    )
                }
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── ExoPlayer listener ────────────────────────────────────────────────

    private fun setupPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _uiState.value = _uiState.value.copy(isBuffering = true)
                    }

                    Player.STATE_READY -> {
                        val rawDuration = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
                        _uiState.value = _uiState.value.copy(
                            isBuffering       = false,
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            durationMs        = rawDuration?.coerceAtLeast(0L)
                                ?: _uiState.value.durationMs
                        )
                    }

                    Player.STATE_ENDED -> {
                        _uiState.value = _uiState.value.copy(
                            isPlaying         = false,
                            isBuffering       = false,
                            currentPositionMs = 0L,
                        )
                    }

                    Player.STATE_IDLE -> {
                        TODO()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer error [code=${error.errorCode}]")

                // Transient network errors are recoverable — re-preparing lets
                // ExoPlayer retry from cache or network without surfacing an error
                // state to the user.
                val isRecoverable = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                )
                if (isRecoverable) {
                    Timber.d("Transient network error — retrying via prepare()")
                    exoPlayer.prepare()
                    return
                }

                _uiState.value = _uiState.value.copy(
                    phase = PlayerUiState.Phase.Error,
                    error = error.localizedMessage ?: "Playback error.",
                )
            }
        })
    }
}

// ── Streams extensions ────────────────────────────────────────────────────────

/**
 * Carries the resolved playback URL alongside its MIME type hint.
 *
 * Passing the MIME type to [MediaItem] lets [DefaultMediaSourceFactory] skip
 * content-type sniffing, which is both faster and more reliable for HLS/DASH.
 */
private data class StreamInfo(val url: String, val mimeType: String?) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(url)
        .apply { mimeType?.let { setMimeType(it) } }
        .build()
}

/**
 * Resolves the best available playback stream from this [Streams] object.
 *
 * Priority (highest → lowest):
 * 1. HLS manifest — adaptive, lowest latency to first frame.
 * 2. DASH manifest — adaptive, highest quality ceiling.
 * 3. Best progressive MP4 video stream — direct URL, sorted by height.
 * 4. Best audio-only MP4 stream — last resort for audio-only content.
 *
 * Returns `null` when none of the above are available.
 */
private fun Streams.resolveStream(): StreamInfo? =
    hlsUrl?.takeIf { it.isNotBlank() }
        ?.let { StreamInfo(it, MimeTypes.APPLICATION_M3U8) }
        ?: dashMpdUrl?.takeIf { it.isNotBlank() }
            ?.let { StreamInfo(it, MimeTypes.APPLICATION_MPD) }
        ?: videoStreams.maxByOrNull { it.height }?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { StreamInfo(it, MimeTypes.VIDEO_MP4) }
        ?: audioStreams.maxByOrNull { it.averageBitrate }?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { StreamInfo(it, MimeTypes.AUDIO_MP4) }