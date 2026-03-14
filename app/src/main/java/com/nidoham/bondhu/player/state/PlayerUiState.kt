package com.nidoham.bondhu.player.state

/**
 * Immutable UI state for the player screen.
 *
 * The [phase] discriminator drives top-level rendering; all other fields are
 * populated only when [phase] is [Phase.Ready].
 *
 * @property phase             Current lifecycle phase of the player.
 * @property title             Video title resolved from stream metadata or the intent extra.
 * @property uploaderName      Channel / uploader name returned by the extractor.
 * @property uploaderUrl       Channel avatar URL; null until stream info is loaded.
 * @property thumbnailUrl      Highest-resolution thumbnail URL; null until stream info is loaded.
 * @property durationMs        Total stream duration in milliseconds; 0 while loading.
 * @property currentPositionMs Current playback position in milliseconds; updated every 500 ms
 *                             while [isPlaying] is true. Resets to 0 when [Phase.Error] or
 *                             playback ends.
 * @property isPlaying         True when ExoPlayer is actively playing (not paused or buffering).
 * @property isBuffering       True when ExoPlayer has entered the buffering state mid-playback.
 * @property error             Human-readable error message; non-null only when [phase] is [Phase.Error].
 */
data class PlayerUiState(
    val phase             : Phase   = Phase.Idle,
    val title             : String  = "",
    val uploaderName      : String  = "",
    val uploaderUrl       : String? = null,
    val thumbnailUrl      : String? = null,
    val durationMs        : Long    = 0L,
    val currentPositionMs : Long    = 0L,
    val isPlaying         : Boolean = false,
    val isBuffering       : Boolean = false,
    val error             : String? = null,
) {
    /**
     * Returns the playback progress as a fraction between 0.0 and 1.0.
     */
    val progress: Float
        get() = if (durationMs > 0) {
            currentPositionMs.coerceIn(0, durationMs).toFloat() / durationMs
        } else 0f

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