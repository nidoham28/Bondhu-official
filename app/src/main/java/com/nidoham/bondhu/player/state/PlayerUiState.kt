package com.nidoham.bondhu.player.state

/**
 * Immutable UI state for the player screen.
 *
 * The [phase] discriminator drives top-level rendering; all other fields are
 * populated only when [phase] is [Phase.Ready].
 *
 * @property phase          Current lifecycle phase of the player.
 * @property title          Video title resolved from stream metadata or the intent extra.
 * @property uploaderName   Channel / uploader name returned by the extractor.
 * @property thumbnailUrl   Highest-resolution thumbnail URL; null until stream info is loaded.
 * @property duration       Total stream duration in seconds; 0 while loading.
 * @property isPlaying      True when ExoPlayer is actively playing (not paused or buffering).
 * @property isBuffering    True when ExoPlayer has entered the buffering state mid-playback.
 * @property error          Human-readable error message; non-null only when [phase] is [Phase.Error].
 */
data class PlayerUiState(
    val phase        : Phase   = Phase.Idle,
    val title        : String  = "",
    val uploaderName : String  = "",
    val thumbnailUrl : String? = null,
    val duration     : Long    = 0L,
    val isPlaying    : Boolean = false,
    val isBuffering  : Boolean = false,
    val error        : String? = null,
) {
    enum class Phase {
        /** No URL has been submitted yet. */
        Idle,
        /** Fetching stream metadata via [StreamExtractor]. */
        Loading,
        /** ExoPlayer is prepared and the media is ready to play. */
        Ready,
        /** Extraction or playback failed — [error] is non-null. */
        Error,
    }
}