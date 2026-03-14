package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * Contains (left to right): current position / total duration label, a flexible
 * [PlayerSeekBar], a quality picker button, and a fullscreen toggle button.
 *
 * ## Quality picker
 * When [availableQualities] is non-empty (i.e. the active source is a merged
 * video-only + audio pair), the quality button shows the currently selected
 * resolution label (e.g. "720p") and opens a [DropdownMenu] listing all
 * available tiers. A checkmark indicates the active selection. When the list is
 * empty (HLS/DASH adaptive source), the button shows "Auto" and is non-interactive.
 *
 * @param positionMs           Current playback position in milliseconds.
 * @param bufferedMs           Currently buffered position in milliseconds.
 * @param durationMs           Total stream duration in milliseconds.
 * @param isLandscape          When true, applies navigation bar inset padding.
 * @param availableQualities   Quality tiers available for selection; empty for adaptive sources.
 * @param selectedQualityIndex Index of the currently active quality in [availableQualities].
 * @param onSeek               Forwarded to [PlayerSeekBar] for seek-by-position.
 * @param onSetQuality         Called with the chosen index when the user selects a quality tier.
 * @param onToggleFullscreen   Called when the fullscreen icon is tapped.
 * @param modifier             Applied to the root [Box].
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient)
            .then(if (isLandscape) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            PlayerSeekBar(
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                durationMs = durationMs,
                onSeek     = onSeek,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text     = "${positionMs.formatTime()} / ${durationMs.formatTime()}",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )

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
                            contentDescription = if (isLandscape) "Exit fullscreen" else "Fullscreen",
                            tint               = Color.White,
                        )
                    }
                }
            }
        }
    }
}

// ── Quality picker button ─────────────────────────────────────────────────────

/**
 * Compact quality selector that shows the active resolution label and opens
 * a [DropdownMenu] listing all available tiers on tap.
 *
 * When [availableQualities] is empty (HLS/DASH adaptive source), the button
 * renders an "Auto" label and ignores tap events — there is nothing to select
 * manually since the player handles bitrate adaptation internally.
 *
 * The menu positions itself above the button automatically (Compose default
 * behaviour) because the button sits near the bottom of the screen.
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
    val isAdaptive = availableQualities.isEmpty()
    val label      = if (isAdaptive) "Auto"
    else availableQualities.getOrNull(selectedQualityIndex)?.label ?: "Auto"

    var menuOpen by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick  = { if (!isAdaptive) menuOpen = true },
            enabled  = !isAdaptive,
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }

        if (!isAdaptive) {
            DropdownMenu(
                expanded         = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                availableQualities.forEachIndexed { index, quality ->
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
                            onSetQuality(index)
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
                                // Reserve the same space as the checkmark so labels stay aligned.
                                Box(modifier = Modifier.padding(start = 24.dp))
                            }
                        },
                    )
                }
            }
        }
    }
}