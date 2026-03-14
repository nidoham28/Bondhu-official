package com.nidoham.bondhu.player

import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.MainCoroutineDispatcher
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
 * stream lifecycle: metadata extraction -> URL resolution -> playback.
 *
 * ## MediaLibraryService
 * Extends [MediaLibraryService] (a superset of `MediaSessionService`) to satisfy
 * the Media3 browse/search protocol. [libraryCallback] exposes a minimal root-only
 * browse tree — sufficient for lock-screen controls and Android Auto without
 * requiring a full content library.
 *
 * ## State broadcasting
 * Whenever [_uiState] changes, [broadcastState] serializes the new snapshot into
 * a [Bundle] and pushes it to all connected [androidx.media3.session.MediaBrowser]
 * clients via [MediaLibrarySession.setSessionExtras]. Clients deserialize via
 * [PlayerUiState.fromBundle].
 *
 * ## Custom commands
 * [CMD_SET_QUALITY] and [CMD_RETRY] are registered in [onConnect] and dispatched
 * in [onCustomCommand], replacing the old `PlayerBinder` IPC approach with the
 * official [SessionCommand] protocol.
 *
 * ## Audio + Video merging
 * Separate video-only and audio-only progressive streams are combined via
 * [MergingMediaSource]. HLS/DASH manifests are handed directly to
 * [DefaultMediaSourceFactory] which handles multiplexed tracks internally.
 *
 * Resolution priority: HLS -> DASH -> Merged (video + audio) -> AudioOnly.
 *
 * ## Quality selection
 * [setQuality] replaces the video track while preserving [currentAudioUrl] and
 * the current seek position. Quality options are sourced from
 * [Streams.videoOnlyStreams], sorted highest-first.
 *
 * ## Player listener batching
 * [Player.Listener.onEvents] batches all property changes that fire in the same
 * message-queue turn into a single [_uiState] write, avoiding redundant
 * [broadcastState] emissions on simultaneous state transitions.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaLibraryService() {

    @Inject lateinit var streamExtractor: StreamExtractor

    // ── ExoPlayer + MediaLibrarySession ───────────────────────────────────

    lateinit var exoPlayer: ExoPlayer
        private set
    private lateinit var mediaLibrarySession: MediaLibrarySession

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

    // Cast required: Dispatchers.Main is statically typed as CoroutineDispatcher,
    // but on Android it is always a MainCoroutineDispatcher at runtime.
    // The .immediate sub-dispatcher avoids a redundant post when already on Main.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + (Dispatchers.Main as MainCoroutineDispatcher).immediate
    )
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    /**
     * Last URL submitted via [loadAndPlay]. Read by [libraryCallback] to
     * resubmit on [CMD_RETRY] without an extra field in the callback closure.
     */
    var lastStreamUrl: String = ""
        private set

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {

        /** Stable root media ID for the browse tree. */
        private const val ROOT_ID = "com.nidoham.bondhu.player.ROOT"

        // ── Custom SessionCommand actions ──────────────────────────────────

        /** Custom [SessionCommand] action: switch video quality. */
        const val CMD_SET_QUALITY   = "com.nidoham.bondhu.player.CMD_SET_QUALITY"

        /** Custom [SessionCommand] action: retry after error. */
        const val CMD_RETRY         = "com.nidoham.bondhu.player.CMD_RETRY"

        /** Bundle key for the quality index argument in [CMD_SET_QUALITY]. */
        const val ARG_QUALITY_INDEX = "quality_index"

        // ── SessionExtras / PlayerUiState bundle keys ──────────────────────

        const val KEY_PHASE            = "phase"
        const val KEY_TITLE            = "title"
        const val KEY_UPLOADER_NAME    = "uploader_name"
        const val KEY_UPLOADER_URL     = "uploader_url"
        const val KEY_THUMBNAIL_URL    = "thumbnail_url"
        const val KEY_DURATION_MS      = "duration_ms"
        const val KEY_CURRENT_POS_MS   = "current_pos_ms"
        const val KEY_ERROR            = "error"
        const val KEY_QUALITY_LABELS   = "quality_labels"
        const val KEY_QUALITY_HEIGHTS  = "quality_heights"
        const val KEY_QUALITY_URLS     = "quality_urls"
        const val KEY_SELECTED_QUALITY = "selected_quality"

        // ── Buffer / network tuning ────────────────────────────────────────

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
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36" +
                    " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    // ── MediaLibrarySession.Callback ──────────────────────────────────────

    /**
     * Handles the Media3 browse protocol and custom [SessionCommand] dispatch.
     *
     * [onConnect] builds a [SessionCommands] set that includes only the two
     * custom actions this service supports. The API no longer provides a
     * pre-built "all default commands" constant — building from scratch is the
     * correct pattern in current Media3.
     *
     * [onCustomCommand] dispatches each registered action to the appropriate
     * service method, replacing the old `PlayerBinder` IPC approach entirely.
     *
     * [onGetLibraryRoot] returns a single root node so browse-protocol clients
     * (Android Auto, WearOS) can complete a session handshake without error.
     * This service exposes no content library — [onGetChildren] returns an
     * empty list and [onGetItem] returns [LibraryResult.RESULT_ERROR_NOT_SUPPORTED].
     */
    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session   : MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // SessionCommands.Builder starts empty; add each custom command explicitly.
            // DEFAULT_SESSION_AND_PLAYER_COMMANDS was removed from the public API —
            // constructing the set from scratch is the current correct pattern.
            val sessionCommands = SessionCommands.Builder()
                .add(SessionCommand(CMD_SET_QUALITY, Bundle.EMPTY))
                .add(SessionCommand(CMD_RETRY,       Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session      : MediaSession,
            controller   : MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args         : Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_QUALITY -> {
                    val index = args.getInt(ARG_QUALITY_INDEX, -1)
                    if (index >= 0) setQuality(index)
                }
                CMD_RETRY -> {
                    if (lastStreamUrl.isNotBlank()) {
                        loadAndPlay(
                            pageUrl = lastStreamUrl,
                            title   = _uiState.value.title.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session : MediaLibrarySession,
            browser : MediaSession.ControllerInfo,
            params  : LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId(ROOT_ID).build(),
                    params,
                )
            )

        override fun onGetChildren(
            session  : MediaLibrarySession,
            browser  : MediaSession.ControllerInfo,
            parentId : String,
            page     : Int,
            pageSize : Int,
            params   : LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))

        override fun onGetItem(
            session : MediaLibrarySession,
            browser : MediaSession.ControllerInfo,
            mediaId : String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
            )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        initPlayer()
        acquireLocks()
        // Collect _uiState and push every change to connected MediaBrowser clients.
        serviceScope.launch {
            _uiState.collect { broadcastState(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onBind(intent: Intent?): IBinder? =
        super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val url   = intent?.getStringExtra(NavigationHelper.EXTRA_STREAM_URL)
            ?: return START_NOT_STICKY
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)
        if (url != lastStreamUrl) loadAndPlay(url, title)
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
        mediaLibrarySession.release()
        // release() stops playback and frees all resources internally.
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
                    // AUDIO_CONTENT_TYPE_MOVIE is correct for video-with-audio content.
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

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()

        setupPlayerListener()
    }

    private fun acquireLocks() {
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bondhu:PlayerWakeLock")
            ?.also { it.acquire(3 * 60 * 60 * 1000L) }

        // WIFI_MODE_FULL_HIGH_PERF is deprecated from API 29.
        // WIFI_MODE_FULL_LOW_LATENCY is the correct replacement on Q+.
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

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun skipForward(deltaMs: Long = 10_000L) {
        val cap = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: return
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtMost(cap))
    }

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
        lastStreamUrl   = pageUrl
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

            // Back on Main.immediate here — safe to touch ExoPlayer.
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

    // ── State broadcasting ────────────────────────────────────────────────

    /**
     * Serializes [state] into a [Bundle] and pushes it to all connected
     * [androidx.media3.session.MediaBrowser] clients via
     * [MediaLibrarySession.setSessionExtras].
     *
     * Invoked automatically from the [_uiState] collector started in [onCreate].
     * Runs on [MainCoroutineDispatcher.immediate] — safe to call [setSessionExtras]
     * here without posting to a different thread.
     *
     * @param state The latest [PlayerUiState] snapshot to broadcast.
     */
    private fun broadcastState(state: PlayerUiState) {
        val qualities = state.availableQualities
        val extras = Bundle().apply {
            putString(KEY_PHASE,           state.phase.name)
            putString(KEY_TITLE,           state.title)
            putString(KEY_UPLOADER_NAME,   state.uploaderName)
            putString(KEY_UPLOADER_URL,    state.uploaderUrl)
            putString(KEY_THUMBNAIL_URL,   state.thumbnailUrl)
            putLong  (KEY_DURATION_MS,     state.durationMs)
            putLong  (KEY_CURRENT_POS_MS,  state.currentPositionMs)
            putString(KEY_ERROR,           state.error)
            putInt   (KEY_SELECTED_QUALITY,state.selectedQualityIndex)
            putStringArray(KEY_QUALITY_LABELS,  qualities.map { it.label }.toTypedArray())
            putIntArray   (KEY_QUALITY_HEIGHTS, qualities.map { it.height }.toIntArray())
            putStringArray(KEY_QUALITY_URLS,    qualities.map { it.videoUrl }.toTypedArray())
        }
        mediaLibrarySession.setSessionExtras(extras)
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

            /**
             * Batches all player property changes that fire in the same
             * message-queue turn into a single [_uiState] write, preventing
             * redundant [broadcastState] emissions when multiple events fire
             * together (e.g. STATE_READY + isPlaying on seek-resume).
             */
            override fun onEvents(player: Player, events: Player.Events) {
                var state = _uiState.value

                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    state = state.copy(isPlaying = player.isPlaying)
                    if (player.isPlaying) startProgressUpdates() else stopProgressUpdates()
                }

                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    state = when (player.playbackState) {
                        Player.STATE_BUFFERING -> state.copy(isBuffering = true)
                        Player.STATE_READY     -> {
                            val rawDuration = player.duration.takeIf { it != C.TIME_UNSET }
                            state.copy(
                                isBuffering = false,
                                durationMs  = rawDuration?.coerceAtLeast(0L) ?: state.durationMs,
                            )
                        }
                        Player.STATE_ENDED     -> state.copy(
                            isPlaying         = false,
                            isBuffering       = false,
                            currentPositionMs = 0L,
                        )
                        Player.STATE_IDLE      -> state.copy(isBuffering = false)
                        else                   -> state
                    }
                }

                // Referential check avoids a StateFlow emit + broadcastState call
                // when nothing actually changed.
                if (state !== _uiState.value) _uiState.value = state
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

    return bestAudioUrl?.let { PlaybackSource.AudioOnly(it) }
}

// ── Quality list builder ──────────────────────────────────────────────────────

private fun buildQualityList(streams: Streams): List<VideoQuality> =
    streams.videoOnlyStreams
        .filter { it.content.isNotBlank() && it.height > 0 }
        .groupBy { it.height }
        .mapValues { (_, variants) -> variants.maxBy { it.bitrate } }
        .values
        .sortedByDescending { it.height }
        .map { VideoQuality(label = "${it.height}p", height = it.height, videoUrl = it.content) }