package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// ── Shared color tokens ────────────────────────────────────────────────────────
internal val YouTubeRed      = Color(0xFFFF0000)
internal val TrackBackground = Color(0x40FFFFFF)
internal val TrackBuffered   = Color(0x80FFFFFF)

/**
 * YouTube-style horizontal seekbar.
 *
 * Renders three layers on a [Canvas]: background track, buffered progress, and
 * played progress (red). A red circular thumb grows slightly while the user is
 * dragging, matching the YouTube interaction model.
 *
 * Both tap-to-seek and horizontal drag are supported via two independent
 * [pointerInput] modifiers keyed on [durationMs].
 *
 * @param positionMs  Current playback position in milliseconds.
 * @param bufferedMs  Currently buffered position in milliseconds.
 * @param durationMs  Total stream duration in milliseconds; renders nothing if ≤ 0.
 * @param onSeek      Invoked with the resolved seek position in milliseconds.
 * @param modifier    Applied to the outer [Box].
 */
@Composable
fun PlayerSeekBar(
    positionMs : Long,
    bufferedMs : Long,
    durationMs : Long,
    onSeek     : (Long) -> Unit,
    modifier   : Modifier = Modifier,
) {
    if (durationMs <= 0L) return

    var isDragging   by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val playedFraction   = if (isDragging) dragFraction
    else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    Box(
        modifier         = modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((fraction * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart  = { isDragging = true },
                        onDragEnd    = {
                            isDragging = false
                            onSeek((dragFraction * durationMs).toLong())
                        },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { _, delta ->
                            dragFraction = (dragFraction + delta / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val cy      = size.height / 2f
            val trackH  = 3.dp.toPx()
            val thumbR  = if (isDragging) 8.dp.toPx() else 5.dp.toPx()
            val top     = cy - trackH / 2f

            // Background
            drawRoundRect(
                color        = TrackBackground,
                topLeft      = Offset(0f, top),
                size         = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            // Buffered
            drawRoundRect(
                color        = TrackBuffered,
                topLeft      = Offset(0f, top),
                size         = Size(size.width * bufferedFraction, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            // Played
            drawRoundRect(
                color        = YouTubeRed,
                topLeft      = Offset(0f, top),
                size         = Size(size.width * playedFraction, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            // Thumb
            drawCircle(
                color  = YouTubeRed,
                radius = thumbR,
                center = Offset(size.width * playedFraction, cy),
            )
        }
    }
}

// ── Time formatting helper ─────────────────────────────────────────────────────

/**
 * Formats a millisecond duration as `M:SS` or `H:MM:SS`.
 */
internal fun Long.formatTime(): String {
    val total   = this / 1000L
    val hours   = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else                   "%d:%02d".format(minutes, seconds)
}