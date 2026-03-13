package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Horizontal action row displayed below the video in portrait mode.
 *
 * Mirrors YouTube's icon tray: thumbs-up / thumbs-down (with a divider between
 * them, matching the unified like-bar style), Share, Download, and Save. Each
 * item is a [Column] of icon + label. The like and dislike buttons toggle local
 * state; the others are stubs ready for ViewModel wiring.
 *
 * @param modifier Applied to the root [Row].
 */
@Composable
fun PlayerActionRow(modifier: Modifier = Modifier) {
    var liked    by remember { mutableStateOf(false) }
    var disliked by remember { mutableStateOf(false) }

    val activeColor   = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier              = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        // Like + dislike share one pill (YouTube style)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionItem(
                icon  = Icons.Rounded.ThumbUp,
                label = "Like",
                tint  = if (liked) activeColor else inactiveColor,
                onClick = {
                    liked    = !liked
                    if (liked) disliked = false
                },
            )
            VerticalDivider(
                modifier  = Modifier
                    .size(height = 20.dp, width = 1.dp)
                    .padding(horizontal = 2.dp),
                color     = MaterialTheme.colorScheme.outlineVariant,
            )
            ActionItem(
                icon  = Icons.Rounded.ThumbDown,
                label = "Dislike",
                tint  = if (disliked) activeColor else inactiveColor,
                onClick = {
                    disliked = !disliked
                    if (disliked) liked = false
                },
            )
        }

        ActionItem(
            icon    = Icons.Rounded.Reply,
            label   = "Share",
            onClick = { /* wire to share intent */ },
        )
        ActionItem(
            icon    = Icons.Rounded.Download,
            label   = "Download",
            onClick = { /* wire to download */ },
        )
        ActionItem(
            icon    = Icons.Rounded.SaveAlt,
            label   = "Save",
            onClick = { /* wire to playlist */ },
        )
    }
}

@Composable
private fun ActionItem(
    icon    : ImageVector,
    label   : String,
    tint    : androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick : () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = tint,
                modifier           = Modifier.size(24.dp),
            )
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}