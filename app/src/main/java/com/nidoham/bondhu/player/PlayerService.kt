@file:OptIn(UnstableApi::class)

package com.nidoham.bondhu.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import com.nidoham.bondhu.PlayerActivity
import com.nidoham.bondhu.player.state.PlayerUiState
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.extractor.stream.StreamExtractor
import com.nidoham.extractor.stream.Streams
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that owns the [ExoPlayer] instance and manages the full
 * stream lifecycle: metadata extraction → URL resolution → playback → notification.
 *
 * Extends [Service] directly. [PlayerNotificationManager] and several other
 * Media3 APIs used here are annotated with [UnstableApi]; the file-level
 * [OptIn] suppresses those warnings project-wide for this file.
 *
 * ## Lifecycle note
 * [onDestroy] releases [ExoPlayer] and cancels all coroutines. The notification
 * is detached automatically by [PlayerNotificationManager].
 */
@AndroidEntryPoint
class PlayerService : Service() {

    @Inject
    lateinit var streamExtractor: StreamExtractor

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    lateinit var exoPlayer: ExoPlayer
        private set

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    /** Tracks the last-loaded URL to skip redundant re-extractions on rebind. */
    private var currentUrl: String = ""

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = PlayerBinder()

    // ── Notification ──────────────────────────────────────────────────────────

    private lateinit var playerNotificationManager: PlayerNotificationManager

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "player_playback_channel"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()
        setupPlayerListener()
        setupNotificationChannel()
        setupNotificationManager()
    }

    /**
     * Reads [NavigationHelper.EXTRA_STREAM_URL] and [NavigationHelper.EXTRA_TITLE]
     * from the intent and delegates to [loadAndPlay] if the URL differs from the
     * currently loaded one (prevents duplicate extraction on config changes).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url   = intent?.getStringExtra(NavigationHelper.EXTRA_STREAM_URL) ?: return START_NOT_STICKY
        val title = intent.getStringExtra(NavigationHelper.EXTRA_TITLE)
        if (url != currentUrl) loadAndPlay(url, title)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        playerNotificationManager.setPlayer(null)
        exoPlayer.release()
        loadJob?.cancel()
        super.onDestroy()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun play()                   { exoPlayer.play() }
    fun pause()                  { exoPlayer.pause() }
    fun seekTo(positionMs: Long) { exoPlayer.seekTo(positionMs) }

    /**
     * Cancels any in-flight extraction, resets state to [PlayerUiState.Phase.Loading],
     * then fetches stream metadata for [pageUrl] and begins playback.
     *
     * @param pageUrl  YouTube video page URL (e.g. `https://youtube.com/watch?v=ID`).
     * @param title    Optional title shown in the notification and top bar while loading.
     *                 Overridden by the title returned from the extractor once available.
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

                    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: data.stream.title

                    withContext(Dispatchers.Main) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(playbackUrl))
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }

                    _uiState.value = PlayerUiState(
                        phase        = PlayerUiState.Phase.Ready,
                        title        = resolvedTitle,
                        uploaderName = data.stream.uploaderName,
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

    // ── ExoPlayer listener ────────────────────────────────────────────────────

    private fun setupPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
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

    // ── Notification ──────────────────────────────────────────────────────────

    // minSdk is 26 — NotificationChannel is always available, no SDK_INT guard needed.
    private fun setupNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows playback controls for the active stream."
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun setupNotificationManager() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        playerNotificationManager = PlayerNotificationManager
            .Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {

                override fun getCurrentContentTitle(player: Player): CharSequence =
                    _uiState.value.title.ifBlank { "Playing" }

                override fun createCurrentContentIntent(player: Player): PendingIntent =
                    contentIntent

                override fun getCurrentContentText(player: Player): CharSequence? =
                    _uiState.value.uploaderName.takeIf { it.isNotBlank() }

                override fun getCurrentLargeIcon(
                    player   : Player,
                    callback : PlayerNotificationManager.BitmapCallback,
                ): Bitmap? = null
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {

                override fun onNotificationPosted(
                    notificationId : Int,
                    notification   : Notification,
                    ongoing        : Boolean,
                ) {
                    if (ongoing) startForeground(notificationId, notification)
                    else         stopForeground(STOP_FOREGROUND_DETACH)
                }

                override fun onNotificationCancelled(
                    notificationId  : Int,
                    dismissedByUser : Boolean,
                ) {
                    stopSelf()
                }
            })
            .build()

        playerNotificationManager.setPlayer(exoPlayer)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Streams extension
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the best available playback URL from this [Streams] object.
 *
 * Priority chain (highest to lowest quality / compatibility):
 * 1. HLS manifest — adaptive, widely supported by ExoPlayer out of the box.
 * 2. DASH manifest — adaptive, higher ceiling but requires DASH parsing.
 * 3. Best progressive video stream — direct URL, sorted by resolution height.
 * 4. Best audio-only stream — fallback for audio-only content.
 *
 * Returns `null` if none of the above are available.
 */
private fun Streams.resolvePlaybackUrl(): String? =
    hlsUrl?.takeIf { it.isNotBlank() }
        ?: dashMpdUrl?.takeIf { it.isNotBlank() }
        ?: videoStreams.maxByOrNull { it.height }?.content?.takeIf { it.isNotBlank() }
        ?: audioStreams.maxByOrNull { it.averageBitrate }?.content?.takeIf { it.isNotBlank() }