package com.nidoham.bondhu.presentation.screen.player

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nidoham.bondhu.R

/**
 * Portrait-only metadata panel rendered below the 16:9 video surface.
 *
 * Displays the video title (up to two lines), uploader channel name with a
 * circular avatar loaded via Coil (falls back to [R.drawable.ic_default_avatar]
 * on load failure or when [uploaderUrl] is null), a Subscribe button, and
 * the [PlayerActionRow].
 *
 * All data flows in from [PlayerUiState] — no Firebase or repository calls are
 * made here.
 *
 * @param title        Video title; shown in bold, max 2 lines.
 * @param uploaderName Channel name; shown alongside the avatar.
 * @param uploaderUrl  Remote image URL for the channel avatar. When null or
 *                     the load fails, [R.drawable.ic_default_avatar] is shown.
 * @param modifier     Applied to the root [Column].
 */
@Composable
fun PlayerInfoSection(
    title: String,
    uploaderName: String,
    uploaderUrl: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ── Title ────────────────────────────────────────────────────────────
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

        // ── Channel row + Subscribe ──────────────────────────────────────────
        if (uploaderName.isNotBlank()) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(
                    avatarUrl    = uploaderUrl,
                    uploaderName = uploaderName,
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = uploaderName,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color      = MaterialTheme.colorScheme.onSurface,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = { /* wire to subscribe action */ },
                    colors  = ButtonDefaults.buttonColors(
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
            modifier = Modifier.padding(vertical = 8.dp),
            color    = MaterialTheme.colorScheme.outlineVariant,
        )

        // ── Action row ───────────────────────────────────────────────────────
        PlayerActionRow(modifier = Modifier.padding(vertical = 4.dp))

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color    = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Circular channel avatar backed by Coil's [AsyncImage].
 *
 * Displays [R.drawable.ic_default_avatar] both while the remote image is
 * loading and when the load fails entirely, ensuring the UI is never blank
 * regardless of network state. Cross-fade is enabled for a smooth transition
 * once the real image arrives.
 *
 * @param avatarUrl    Remote URL to load. May be null.
 * @param uploaderName Used for the accessibility content description.
 */
@Composable
private fun ChannelAvatar(
    avatarUrl    : String?,
    uploaderName : String,
) {
    val defaultAvatar = painterResource(R.drawable.ic_launcher)

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(avatarUrl)
            .crossfade(true)
            .build(),
        contentDescription = "$uploaderName channel avatar",
        contentScale       = ContentScale.Crop,
        placeholder        = defaultAvatar,
        error              = defaultAvatar,
        modifier           = Modifier
            .size(36.dp)
            .clip(CircleShape),
    )
}