package com.nidoham.bondhu.player.state

import android.os.Bundle
import com.nidoham.bondhu.player.PlayerService

/**
 * Immutable snapshot of the player's presentable state.
 *
 * Shared between [com.nidoham.bondhu.player.PlayerService] (producer) and
 * [com.nidoham.bondhu.presentation.viewmodel.PlayerViewModel] (consumer).
 * The service serializes this into a [Bundle] via [PlayerService.broadcastState]
 * and clients reconstruct it via [fromBundle].
 *
 * @param phase              Current lifecycle phase of the player.
 * @param title              Video title; empty string while loading.
 * @param uploaderName       Channel or uploader display name.
 * @param uploaderUrl        Avatar URL for the uploader; null if unavailable.
 * @param thumbnailUrl       Video thumbnail URL; null if unavailable.
 * @param durationMs         Total stream duration in milliseconds; 0 while unknown.
 * @param currentPositionMs  Current playback position in milliseconds.
 * @param isPlaying          True when ExoPlayer is actively playing (not buffering).
 * @param isBuffering        True when ExoPlayer is in STATE_BUFFERING.
 * @param error              Human-readable error message; non-null only in [Phase.Error].
 * @param availableQualities Non-empty only for [PlaybackSource.Merged] streams.
 * @param selectedQualityIndex Index into [availableQualities] for the active track.
 */
data class PlayerUiState(
    val phase               : Phase          = Phase.Idle,
    val title               : String         = "",
    val uploaderName        : String         = "",
    val uploaderUrl         : String?        = null,
    val thumbnailUrl        : String?        = null,
    val durationMs          : Long           = 0L,
    val currentPositionMs   : Long           = 0L,
    val isPlaying           : Boolean        = false,
    val isBuffering         : Boolean        = false,
    val error               : String?        = null,
    var availableQualities  : List<VideoQuality> = emptyList(),
    val selectedQualityIndex: Int            = 0,
) {

    /** Lifecycle phase of the player. */
    enum class Phase { Idle, Loading, Ready, Error }

    companion object {

        /**
         * Reconstructs a [PlayerUiState] from the [Bundle] broadcast by
         * [PlayerService.broadcastState] via [MediaLibrarySession.setSessionExtras].
         *
         * Standard playback fields ([isPlaying], [isBuffering]) are intentionally
         * omitted — callers should merge those from the live [Player.Listener]
         * rather than from this snapshot to avoid stale values.
         *
         * @param extras The extras bundle received in
         *   [androidx.media3.session.MediaBrowser.Listener.onExtrasChanged].
         * @return A fully populated [PlayerUiState]; falls back to [Phase.Idle]
         *   if the phase string is absent or unrecognized.
         */
        fun fromBundle(extras: Bundle): PlayerUiState {
            val phaseStr = extras.getString(PlayerService.KEY_PHASE)
                ?: Phase.Idle.name
            val phase = runCatching { Phase.valueOf(phaseStr) }
                .getOrDefault(Phase.Idle)

            val labels  = extras.getStringArray(PlayerService.KEY_QUALITY_LABELS)
                ?: emptyArray()
            val heights = extras.getIntArray(PlayerService.KEY_QUALITY_HEIGHTS)
                ?: IntArray(0)
            val urls    = extras.getStringArray(PlayerService.KEY_QUALITY_URLS)
                ?: emptyArray()

            val qualities = labels.indices.map { i ->
                VideoQuality(
                    label    = labels.getOrElse(i)  { "${heights.getOrElse(i) { 0 }}p" },
                    height   = heights.getOrElse(i) { 0 },
                    videoUrl = urls.getOrElse(i)    { "" },
                )
            }

            return PlayerUiState(
                phase                = phase,
                title                = extras.getString(PlayerService.KEY_TITLE).orEmpty(),
                uploaderName         = extras.getString(PlayerService.KEY_UPLOADER_NAME).orEmpty(),
                uploaderUrl          = extras.getString(PlayerService.KEY_UPLOADER_URL),
                thumbnailUrl         = extras.getString(PlayerService.KEY_THUMBNAIL_URL),
                durationMs           = extras.getLong(PlayerService.KEY_DURATION_MS, 0L),
                currentPositionMs    = extras.getLong(PlayerService.KEY_CURRENT_POS_MS, 0L),
                error                = extras.getString(PlayerService.KEY_ERROR),
                availableQualities   = qualities,
                selectedQualityIndex = extras.getInt(PlayerService.KEY_SELECTED_QUALITY, 0),
            )
        }
    }
}

/**
 * A single selectable video quality option derived from [Streams.videoOnlyStreams].
 *
 * @param label    Human-readable label shown in the quality picker (e.g. "1080p").
 * @param height   Pixel height used for sorting; higher is better.
 * @param videoUrl Direct URL for the video-only stream at this resolution.
 */
data class VideoQuality(
    val label   : String,
    val height  : Int,
    val videoUrl: String,
)