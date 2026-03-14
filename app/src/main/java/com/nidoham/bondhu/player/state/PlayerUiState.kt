package com.nidoham.bondhu.player.state

/**
 * A single selectable video quality option resolved from the extractor's
 * [com.nidoham.extractor.stream.Streams.videoStreams] list.
 *
 * Only populated when the active [PlaybackSource] is `Merged` (separate
 * video-only + audio-only URLs). For HLS and DASH manifests the player
 * handles adaptive bitrate internally; in that case [PlayerUiState.availableQualities]
 * is empty and the UI shows "Auto".
 *
 * @property label    Human-readable label shown in the quality picker (e.g. "1080p", "720p").
 * @property height   Vertical resolution in pixels; used for sorting and deduplication.
 * @property videoUrl Direct video-only stream URL for this resolution tier.
 */
data class VideoQuality(
    val label    : String,
    val height   : Int,
    val videoUrl : String,
)

/**
 * Immutable UI state for the player screen.
 *
 * The [phase] discriminator drives top-level rendering; all other fields are
 * populated only when [phase] is [Phase.Ready].
 *
 * @property phase               Current lifecycle phase of the player.
 * @property title               Video title resolved from stream metadata or the intent extra.
 * @property uploaderName        Channel / uploader name returned by the extractor.
 * @property uploaderUrl         Channel avatar URL; null until stream info is loaded.
 * @property thumbnailUrl        Highest-resolution thumbnail URL; null until stream info is loaded.
 * @property durationMs          Total stream duration in milliseconds; 0 while loading.
 * @property currentPositionMs   Current playback position in milliseconds; updated every 500 ms
 *                               while [isPlaying] is true. Resets to 0 when playback ends.
 * @property isPlaying           True when ExoPlayer is actively playing (not paused or buffering).
 * @property isBuffering         True when ExoPlayer has entered the buffering state mid-playback.
 * @property error               Human-readable error message; non-null only when [phase] is [Phase.Error].
 * @property availableQualities  Quality tiers available for manual selection. Empty for HLS/DASH
 *                               streams where ABR is handled automatically.
 * @property selectedQualityIndex Index into [availableQualities] for the currently active tier.
 *                               0 is always the highest resolution option (list is sorted descending).
 */
data class PlayerUiState(
    val phase                : Phase             = Phase.Idle,
    val title                : String            = "",
    val uploaderName         : String            = "",
    val uploaderUrl          : String?           = null,
    val thumbnailUrl         : String?           = null,
    val durationMs           : Long              = 0L,
    val currentPositionMs    : Long              = 0L,
    val isPlaying            : Boolean           = false,
    val isBuffering          : Boolean           = false,
    val error                : String?           = null,
    val availableQualities   : List<VideoQuality> = emptyList(),
    val selectedQualityIndex : Int               = 0,
) {
    enum class Phase {
        /** No URL has been submitted yet. */
        Idle,
        /** Fetching stream metadata via StreamExtractor. */
        Loading,
        /** ExoPlayer is prepared and the media is ready to play. */
        Ready,
        /** Extraction or playback failed — [error] is non-null. */
        Error,
    }
}