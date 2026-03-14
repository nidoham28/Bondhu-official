package com.nidoham.bondhu.player

import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nidoham.bondhu.PlayerActivity
import com.nidoham.bondhu.player.state.PlayerUiState
import com.nidoham.bondhu.player.state.VideoQuality
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
 * ## Auto-stop fix
 * [setSessionActivity] is set on [MediaSession] pointing to [PlayerActivity],
 * giving `DefaultMediaNotificationProvider` a valid foreground `PendingIntent`.
 * [onTaskRemoved] only calls [stopSelf] when the player is genuinely idle.
 *
 * ## Audio + Video merging
 * Separate video-only and audio-only progressive streams are combined via
 * [MergingMediaSource]. HLS/DASH manifests carry multiplexed tracks and are
 * handed directly to [DefaultMediaSourceFactory].
 *
 * Resolution priority: HLS → DASH → Merged (video + audio) → AudioOnly.
 *
 * ## Quality selection
 * [setQuality] replaces the video track while preserving [currentAudioUrl] and
 * the current seek position. Quality options are sourced from
 * [Streams.videoOnlyStreams], sorted highest-first.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    @Inject lateinit var streamExtractor: StreamExtractor

    // ── ExoPlayer + MediaSession ──────────────────────────────────────────

    lateinit var exoPlayer: ExoPlayer
        private set
    private lateinit var mediaSession: MediaSession

    // ── Media source factories ────────────────────────────────────────────

    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    private lateinit var progressiveSourceFactory: ProgressiveMediaSource.Factory

    // ── System locks ──────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ── State ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * Audio-only URL paired with the current video stream. Preserved across
     * quality switches so [setQuality] can rebuild [MergingMediaSource] without
     * re-extracting the page. Empty when the active source is HLS/DASH or
     * audio-only.
     */
    private var currentAudioUrl: String = ""

    // ── Coroutines ────────────────────────────────────────────────────────

    // Dispatchers.Main.immediate avoids a redundant post when already on Main.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    private var currentUrl: String = ""

    // ── Binder ────────────────────────────────────────────────────────────

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = PlayerBinder()

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {

        const val ACTION_BIND = "com.nidoham.bondhu.player.ACTION_BIND"

        private const val MIN_BUFFER_MS                         = 15_000
        private const val MAX_BUFFER_MS                         = 50_000
        private const val BUFFER_FOR_PLAYBACK_MS                =  2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS =  5_000
        private const val HTTP_CONNECT_TIMEOUT_MS               = 10_000
        private const val HTTP_READ_TIMEOUT_MS                  = 15_000
        private const val PROGRESS_POLL_MS                      = 500L
        private const val CACHE_MAX_BYTES                       = 512L * 1024 * 1024

        /**
         * Generic mobile browser UA. Required by some CDNs that reject
         * non-browser requests; avoids 403/429 responses on stream URLs.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        @Volatile private var sharedCache: SimpleCache? = null

        private fun acquireCache(cacheDir: File, context: android.content.Context): SimpleCache =
            sharedCache ?: synchronized(this) {
                sharedCache ?: SimpleCache(
                    File(cacheDir, "exo_stream_cache"),
                    LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(context),
                ).also { sharedCache = it }
            }

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exoPlayer.playWhenReady || exoPlayer.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        stopProgressUpdates()
        loadJob?.cancel()
        serviceScope.cancel()
        mediaSession.release()
        // release() stops playback and frees all resources internally;
        // explicit stop()/clearMediaItems() before it are redundant.
        exoPlayer.release()
        super.onDestroy()
    }

    // ── Initialization ────────────────────────────────────────────────────

    private fun initPlayer() {
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(true)
            .build()

        val trackSelector = DefaultTrackSelector(this, AdaptiveTrackSelection.Factory()).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }

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
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)

        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(acquireCache(cacheDir, applicationContext))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        progressiveSourceFactory = ProgressiveMediaSource.Factory(cacheDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // AUDIO_CONTENT_TYPE_MOVIE is correct for video-with-audio content;
                    // MUSIC was semantically wrong and can affect audio focus behaviour.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()

        setupPlayerListener()
    }

    private fun acquireLocks() {
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bondhu:PlayerWakeLock")
            ?.also { it.acquire(3 * 60 * 60 * 1000L) }

        // WIFI_MODE_FULL_HIGH_PERF is deprecated from API 29.
        // WIFI_MODE_FULL_LOW_LATENCY is the current replacement on Q+.
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF

        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(wifiLockMode, "Bondhu:PlayerWifiLock")
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

    @Suppress("unused")
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    @Suppress("unused")
    fun skipForward(deltaMs: Long = 10_000L) {
        val cap = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtMost(cap))
    }

    @Suppress("unused")
    fun skipBackward(deltaMs: Long = 10_000L) {
        exoPlayer.seekTo((exoPlayer.currentPosition - deltaMs).coerceAtLeast(0L))
    }

    /**
     * Switches the active video stream to the quality at [index] in
     * [PlayerUiState.availableQualities].
     *
     * Only meaningful when [currentAudioUrl] is populated (i.e. a
     * [PlaybackSource.Merged] source is active). No-op for HLS/DASH or
     * audio-only sources where the quality list is empty.
     *
     * @param index Index into [PlayerUiState.availableQualities].
     */
    fun setQuality(index: Int) {
        val quality  = _uiState.value.availableQualities.getOrNull(index) ?: return
        val audioUrl = currentAudioUrl.takeIf { it.isNotBlank() } ?: return

        val savedPosition = exoPlayer.currentPosition.coerceAtLeast(0L)

        val merged = MergingMediaSource(
            progressiveSourceFactory.createMediaSource(
                MediaItem.Builder()
                    .setUri(quality.videoUrl)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            ),
            progressiveSourceFactory.createMediaSource(
                MediaItem.Builder()
                    .setUri(audioUrl)
                    .setMimeType(MimeTypes.AUDIO_MP4)
                    .build()
            ),
        )

        exoPlayer.setMediaSource(merged, savedPosition)
        exoPlayer.prepare()
        exoPlayer.play()

        _uiState.value = _uiState.value.copy(selectedQualityIndex = index)
        Timber.d("Quality switched to ${quality.label}")
    }

    /**
     * Cancels any in-flight extraction, resets state to [PlayerUiState.Phase.Loading],
     * resolves the playback source on an IO dispatcher, then hands it to ExoPlayer
     * on the main thread.
     *
     * @param pageUrl YouTube / Piped-compatible video page URL.
     * @param title   Optimistic title shown during loading.
     */
    fun loadAndPlay(pageUrl: String, title: String?) {
        loadJob?.cancel()
        currentUrl      = pageUrl
        currentAudioUrl = ""

        loadJob = serviceScope.launch {
            _uiState.value = PlayerUiState(
                phase = PlayerUiState.Phase.Loading,
                title = title.orEmpty(),
            )

            // Extraction is a network/IO operation; explicitly dispatch to IO
            // so we never block the main thread regardless of the extractor's
            // internal dispatcher handling.
            val result = withContext(Dispatchers.IO) {
                streamExtractor.fetchStream(pageUrl)
            }

            // Back on Dispatchers.Main.immediate here — safe to touch ExoPlayer.
            result
                .onSuccess { data ->
                    val source = data.stream.resolvePlaybackSource()
                    if (source == null) {
                        Timber.w("No playable source for: $pageUrl")
                        _uiState.value = _uiState.value.copy(
                            phase = PlayerUiState.Phase.Error,
                            error = "No playable stream found for this video.",
                        )
                        return@onSuccess
                    }

                    val resolvedTitle  = title?.takeIf { it.isNotBlank() } ?: data.stream.title
                    val qualityOptions = if (source is PlaybackSource.Merged)
                        buildQualityList(data.stream) else emptyList()

                    when (source) {
                        is PlaybackSource.Adaptive -> {
                            exoPlayer.setMediaItem(
                                MediaItem.Builder()
                                    .setUri(source.url)
                                    .setMimeType(source.mimeType)
                                    .build()
                            )
                        }
                        is PlaybackSource.Merged -> {
                            currentAudioUrl = source.audioUrl
                            exoPlayer.setMediaSource(
                                MergingMediaSource(
                                    progressiveSourceFactory.createMediaSource(
                                        MediaItem.Builder()
                                            .setUri(source.videoUrl)
                                            .setMimeType(MimeTypes.VIDEO_MP4)
                                            .build()
                                    ),
                                    progressiveSourceFactory.createMediaSource(
                                        MediaItem.Builder()
                                            .setUri(source.audioUrl)
                                            .setMimeType(MimeTypes.AUDIO_MP4)
                                            .build()
                                    ),
                                )
                            )
                        }
                        is PlaybackSource.AudioOnly -> {
                            exoPlayer.setMediaItem(
                                MediaItem.Builder()
                                    .setUri(source.url)
                                    .setMimeType(MimeTypes.AUDIO_MP4)
                                    .build()
                            )
                        }
                    }

                    exoPlayer.prepare()
                    exoPlayer.play()

                    _uiState.value = PlayerUiState(
                        phase                = PlayerUiState.Phase.Ready,
                        title                = resolvedTitle,
                        uploaderName         = data.stream.uploaderName,
                        uploaderUrl          = data.stream.uploaderAvatars.maxByOrNull { it.height }?.url,
                        thumbnailUrl         = data.stream.thumbnails.maxByOrNull { it.height }?.url,
                        durationMs           = data.stream.duration * 1000L,
                        isPlaying            = false,
                        availableQualities   = qualityOptions,
                        selectedQualityIndex = 0,
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
                            isBuffering = false,
                            durationMs  = rawDuration?.coerceAtLeast(0L)
                                ?: _uiState.value.durationMs,
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
                        _uiState.value = _uiState.value.copy(isBuffering = false)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer error [code=${error.errorCode}]")
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

// ── Playback source model ─────────────────────────────────────────────────────

private sealed class PlaybackSource {
    data class Adaptive(val url: String, val mimeType: String) : PlaybackSource()
    data class Merged(val videoUrl: String, val audioUrl: String) : PlaybackSource()
    data class AudioOnly(val url: String) : PlaybackSource()
}

// ── Streams resolution ────────────────────────────────────────────────────────

private fun Streams.resolvePlaybackSource(): PlaybackSource? {
    hlsUrl?.takeIf { it.isNotBlank() }?.let {
        return PlaybackSource.Adaptive(it, MimeTypes.APPLICATION_M3U8)
    }
    dashMpdUrl?.takeIf { it.isNotBlank() }?.let {
        return PlaybackSource.Adaptive(it, MimeTypes.APPLICATION_MPD)
    }

    val bestVideoUrl = videoOnlyStreams
        .maxByOrNull { it.height }
        ?.content
        ?.takeIf { it.isNotBlank() }

    val bestAudioUrl = audioStreams
        .maxByOrNull { it.averageBitrate }
        ?.content
        ?.takeIf { it.isNotBlank() }

    if (bestVideoUrl != null && bestAudioUrl != null) {
        return PlaybackSource.Merged(bestVideoUrl, bestAudioUrl)
    }

    // Simplified single-expression: no null AudioOnly possible here.
    return bestAudioUrl?.let { PlaybackSource.AudioOnly(it) }
}

// ── Quality list builder ──────────────────────────────────────────────────────

private fun buildQualityList(streams: Streams): List<VideoQuality> =
// videoOnlyStreams matches the source used in resolvePlaybackSource;
    // the original videoStreams reference was an inconsistency.
    streams.videoOnlyStreams
        .filter { it.content.isNotBlank() && it.height > 0 }
        .groupBy { it.height }
        .mapValues { (_, variants) -> variants.maxBy { it.bitrate } }
        .values
        .sortedByDescending { it.height }
        .map { VideoQuality(label = "${it.height}p", height = it.height, videoUrl = it.content) }