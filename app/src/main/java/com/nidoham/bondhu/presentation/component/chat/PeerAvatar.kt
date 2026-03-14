package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nidoham.bondhu.ui.theme.LocalCustomColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Circular peer avatar used throughout the chat screen.
 *
 * Renders a remote / local image via Coil when [avatarUrl] is non-blank, or
 * falls back to a colored circle containing the first letter of [name] in
 * upper-case. An optional green presence dot is overlaid at the bottom-end
 * corner when [isOnline] is `true`.
 *
 * The dot scales proportionally with [size] (28 % of the avatar diameter) and
 * is bordered by the surface color so it remains legible over both the avatar
 * image and the background behind it.
 *
 * @param avatarUrl Remote URL or local asset path for the peer's profile image.
 *                  Pass an empty string to display the initial fallback.
 * @param name      Display name of the peer. The first character is used as the
 *                  fallback initial when no image is available.
 * @param size      Diameter of the circular avatar in [Dp].
 * @param isOnline  When `true`, renders a green online-presence indicator dot
 *                  at the bottom-end corner of the avatar.
 */
@Composable
fun PeerAvatar(
    avatarUrl: String,
    name: String,
    size: Dp,
    isOnline: Boolean,
) {
    val customColors = LocalCustomColors.current

    Box(contentAlignment = Alignment.BottomEnd) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model              = avatarUrl,
                contentDescription = name,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Box(
                modifier         = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        color      = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize   = (size.value * 0.38f).sp,
                    ),
                )
            }
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(customColors.success)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

fun formatLastSeen(timestamp: Long): String {
    if (timestamp <= 0) return "offline"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
