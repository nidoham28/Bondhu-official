package com.nidoham.bondhu.presentation.component.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nidoham.server.domain.participant.User
import org.nidoham.server.util.toFormattedCount // FIX: Use the utility we created earlier

private val InstagramBlue = Color(0xFF0095F6)

@Composable
fun SearchRow(
    user: User,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val primaryText = user.username
    val secondaryText = user.displayName.takeIf { it.isNotBlank() } ?: ""
    val followersCount = user.followerCount

    // FIX: Handle empty username safely to avoid crash/crash
    val initials = remember(primaryText) {
        primaryText.trim().takeIf { it.isNotEmpty() }?.first()?.uppercaseChar()?.toString() ?: "?"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left: Profile Pic ────────
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!user.photoUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = "$primaryText's avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = initials,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant,
                    fontSize = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // ── Middle: Texts ───────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // 1st Text: Username + Verified Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        letterSpacing = 0.sp
                    ),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (user.verified) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Verified",
                        tint = InstagramBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // FIX: Construct subtitle cleanly
            val bottomText = buildString {
                if (secondaryText.isNotEmpty()) {
                    append(secondaryText)
                }
                if (followersCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    // FIX: Use the extension function
                    append("${followersCount.toFormattedCount()} followers")
                }
            }

            if (bottomText.isNotEmpty()) {
                Text(
                    text = bottomText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}