package com.nidoham.bondhu.presentation.component.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nidoham.extractor.stream.StreamItem
import com.nidoham.extractor.util.TimeUtil
import com.nidoham.extractor.util.TimeUtil.formatCount
import com.nidoham.extractor.util.TimeUtil.formatDuration
import java.util.Date

@Composable
fun VideoCard(item: StreamItem) {
    VideoBox(item)
}

@Composable
fun VideoBox(item: StreamItem) {
    val thumbnail = item.thumbnails.maxByOrNull { it.height }?.url
    val aspectRatio = if (item.isShort) 9f / 16f else 16f / 9f

    // FIX 1: formatRelativeTime() must be called inside the with(TimeUtil) scope,
    //         and Date conversion is kept in the same lambda to avoid scope leakage.
    val statsParts: List<String> = with(TimeUtil) {
        buildList {
            if (item.viewCount > 0L) add("${item.viewCount.formatCount()} views")
            item.uploadDate?.let { uploadDate ->
                val date: Date? = uploadDate.offsetDateTime()
                    .toInstant()
                    ?.let { Date.from(it) }
                // formatRelativeTime() is now correctly inside the with(TimeUtil) scope
                add(date?.formatRelativeTime() ?: "Unknown")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {

        // ── Thumbnail ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
        ) {
            AsyncImage(
                model = thumbnail,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (item.isAgeRestricted) Modifier.blur(20.dp) else Modifier),
            )

            // Age-restricted overlay
            if (item.isAgeRestricted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Age restricted",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "Age-Restricted Content",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
            }

            // LIVE badge
            if (item.isLive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xFFFF0000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "● LIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                }
            }

            // FIX 2: Added !item.isShort so the duration badge never overlaps the SHORT badge
            if (!item.isAgeRestricted && !item.isLive && !item.isShort && item.duration > 0L) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = item.duration.formatDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }

            // SHORT badge
            if (item.isShort) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "SHORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // ── Metadata row ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Uploader avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                // FIX 3: isNullOrEmpty() guards against a null uploaderAvatarUrl
                if (item.uploaderAvatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = item.uploaderAvatarUrl,
                        contentDescription = item.uploaderName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        // FIX 4: orEmpty() guards against a null uploaderName before calling firstOrNull()
                        text = item.uploaderName.orEmpty()
                            .firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // Video title
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // FIX 5: orEmpty() prevents a crash when uploaderName is null
                val displayName = item.uploaderName.orEmpty()
                Text(
                    text = if (item.verified) "$displayName ✓" else displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Views • relative publish time
                if (statsParts.isNotEmpty()) {
                    Text(
                        text = statsParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Like count
                if (item.likeCount > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ThumbUp,
                            contentDescription = "Likes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = item.likeCount.formatCount(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}