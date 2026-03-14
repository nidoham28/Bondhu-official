package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.player.state.VideoQuality

/**
 * Bottom gradient bar rendered inside the controls overlay.
 *
 * ## Layout — two rows inside a [Column]
 * ```
 * ┌────────────────────────────────────────────────┐
 * │  ████████░░░░░░░░░░░─────────────────────  ← seekbar row
 * │  0:42 / 10:00            [720p]  [⛶]       ← controls row
 * └────────────────────────────────────────────────┘
 * ```
 * The seekbar occupies its own full-width row so it is never squeezed by the
 * time label or action buttons. The controls row below it uses a [Spacer] with
 * [weight(1f)] to push the quality/fullscreen buttons to the trailing edge.
 *
 * The [modifier] passed by the caller should include
 * `Modifier.align(Alignment.BottomCenter)` so the bar sits at the bottom of
 * the [PlayerControlsOverlay] surface.
 *
 * ## Quality picker
 * When [availableQualities] is non-empty (merged video-only + audio source),
 * the quality button opens a [DropdownMenu] listing all tiers with a checkmark
 * on the active selection. When empty (HLS/DASH adaptive source), the button
 * shows "Auto" and is disabled.
 *
 * @param positionMs           Current playback position in milliseconds.
 * @param bufferedMs           Currently buffered position in milliseconds.
 * @param durationMs           Total stream duration in milliseconds.
 * @param isLandscape          When true, applies navigation bar inset padding.
 * @param availableQualities   Quality tiers available for selection.
 * @param selectedQualityIndex Index of the currently active quality.
 * @param onSeek               Forwarded to [PlayerSeekBar].
 * @param onSetQuality         Called with the chosen index on quality selection.
 * @param onToggleFullscreen   Called when the fullscreen icon is tapped.
 * @param modifier             Applied to the root [Box]. Pass
 *                             `Modifier.align(Alignment.BottomCenter)` from the
 *                             parent to pin this bar to the bottom of the overlay.
 */
@Composable
fun PlayerBottomBar(
    positionMs           : Long,
    bufferedMs           : Long,
    durationMs           : Long,
    isLandscape          : Boolean,
    availableQualities   : List<VideoQuality>,
    selectedQualityIndex : Int,
    onSeek               : (Long) -> Unit,
    onSetQuality         : (Int) -> Unit,
    onToggleFullscreen   : () -> Unit,
    modifier             : Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color(0xCC000000)),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .then(if (isLandscape) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 8.dp)
            .padding(bottom = 4.dp),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "${positionMs.formatTime()} / ${durationMs.formatTime()}",
                style    = MaterialTheme.typography.labelSmall,
                color    = Color.White,
                modifier = Modifier.padding(start = 4.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityButton(
                    availableQualities   = availableQualities,
                    selectedQualityIndex = selectedQualityIndex,
                    onSetQuality         = onSetQuality,
                )

                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        imageVector        = if (isLandscape) Icons.Rounded.FullscreenExit
                        else              Icons.Rounded.Fullscreen,
                        contentDescription = if (isLandscape) "Exit fullscreen"
                        else              "Fullscreen",
                        tint               = Color.White,
                    )
                }
            }
        }

        PlayerSeekBar(
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = durationMs,
            onSeek     = onSeek,
            modifier   = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        )
    }
}

// ── Quality picker button ─────────────────────────────────────────────────────

/**
 * Compact quality selector that shows the active resolution label and opens
 * a [DropdownMenu] listing all available tiers on tap.
 *
 * When [availableQualities] is empty (HLS/DASH adaptive source), the button
 * renders an "Auto" label and ignores tap events.
 *
 * @param availableQualities   Sorted descending by height; index 0 is best quality.
 * @param selectedQualityIndex Currently active index; shown with a checkmark.
 * @param onSetQuality         Called with the selected index when the user taps a tier.
 */
@Composable
private fun QualityButton(
    availableQualities   : List<VideoQuality>,
    selectedQualityIndex : Int,
    onSetQuality         : (Int) -> Unit,
) {
    val filteredQualities = remember(availableQualities) {
        availableQualities.filter { it.height < 1080 }
    }

    val isHdSupported = remember(filteredQualities) {
        filteredQualities.any { it.height >= 720 }
    }

    val isAdaptive = filteredQualities.isEmpty()
    val label      = if (isAdaptive) "Auto"
    else filteredQualities.getOrNull(selectedQualityIndex)?.label ?: "Auto"
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { if (!isAdaptive) menuOpen = true },
            enabled = !isAdaptive,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                if (isHdSupported) {
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Text(
                        text = "HD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }

        if (!isAdaptive) {
            DropdownMenu(
                expanded         = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                filteredQualities.forEachIndexed { index, quality ->
                    val isSelected = index == selectedQualityIndex
                    DropdownMenuItem(
                        text = {
                            Text(
                                text  = quality.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else            MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            // Map the filtered index back to the original availableQualities index
                            val originalIndex = availableQualities.indexOf(quality)
                            if (originalIndex != -1) {
                                onSetQuality(originalIndex)
                            }
                            menuOpen = false
                        },
                        leadingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector        = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                // Reserve space matching the checkmark icon width.
                                Box(modifier = Modifier.padding(start = 24.dp))
                            }
                        },
                    )
                }
            }
        }
    }
}