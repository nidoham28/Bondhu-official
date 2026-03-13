package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Portrait-only metadata panel rendered below the 16:9 video surface.
 *
 * Displays the video title (up to two lines), uploader channel name, a
 * Subscribe button, and the [PlayerActionRow] (like / share / download / save).
 * A [HorizontalDivider] separates the action row from the content that would
 * follow in a full implementation (comments, recommended videos, etc.).
 *
 * All data flows in from [PlayerUiState] — no Firebase or repository calls are
 * made here.
 *
 * @param title        Video title; shown in bold, max 2 lines.
 * @param uploaderName Channel name; shown as a subdued subtitle.
 * @param modifier     Applied to the root [Column].
 */
@Composable
fun PlayerInfoSection(
    title        : String,
    uploaderName : String,
    modifier     : Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Title
        if (title.isNotBlank()) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Channel row + Subscribe
        if (uploaderName.isNotBlank()) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar placeholder
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(
                            Modifier.padding(0.dp) // replaced by Coil AsyncImage when available
                        )
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(36.dp)) {
                        drawCircle(color = Color(0xFF616161))
                    }
                    androidx.compose.material3.Text(
                        text     = uploaderName.take(1).uppercase(),
                        style    = MaterialTheme.typography.labelLarge,
                        color    = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = uploaderName,
                        style    = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color    = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text  = "Subscribe",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick  = { /* wire to subscribe action */ },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor   = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text       = "Subscribe",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        HorizontalDivider(
            modifier  = Modifier.padding(vertical = 8.dp),
            color     = MaterialTheme.colorScheme.outlineVariant,
        )

        // Action row
        PlayerActionRow(modifier = Modifier.padding(vertical = 4.dp))

        HorizontalDivider(
            modifier  = Modifier.padding(vertical = 8.dp),
            color     = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}