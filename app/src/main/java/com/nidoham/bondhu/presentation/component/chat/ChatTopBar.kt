package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Conversation top app bar displaying peer identity, presence status, and
 * action buttons.
 *
 * Layout (left to right):
 * - **Back arrow** + **[PeerAvatar]** grouped in the navigation slot so the
 *   avatar sits immediately to the right of the back button, matching the
 *   Instagram DM chrome.
 * - **Title column** containing the peer's display name and a presence sub-label
 *   ("active" when online, or a human-readable last-seen string otherwise).
 * - **Call** and **More** action icons.
 *
 * The avatar size scales with [windowSizeClass] (40 dp on compact, 48 dp on larger
 * screens). The bar background uses `MaterialTheme.colorScheme.background` rather
 * than a surface-level color so it blends with the wallpaper on transparent
 * Scaffold configurations.
 *
 * @param peerName Peer display name shown as the title.
 * @param peerAvatarUrl Remote URL for the peer's profile photo.
 * @param isOnline When `true`, the sub-label shows "active"; otherwise
 *                 [formatLastSeen] is used to derive a human-readable label.
 * @param lastSeenTimestamp Epoch-millisecond string from the remote user document;
 *                          passed directly to [formatLastSeen].
 * @param onNavigateBack Navigates back to the conversation list.
 * @param windowSizeClass Controls the avatar size dimension.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    peerName: String,
    peerAvatarUrl: String,
    isOnline: Boolean,
    lastSeenTimestamp: String,
    onNavigateBack: () -> Unit,
    windowSizeClass: WindowWidthClass
) {
    val avatarSize = if (windowSizeClass == WindowWidthClass.Compact) 40.dp else 48.dp

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        ),
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
                PeerAvatar(
                    avatarUrl = peerAvatarUrl,
                    name = peerName,
                    size = avatarSize,
                    isOnline = isOnline
                )
                Spacer(Modifier.width(8.dp))
            }
        },
        title = {
            Column {
                Text(
                    text = peerName.ifBlank { "Loading…" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val statusText = if (isOnline) "active" else formatLastSeen(lastSeenTimestamp)

                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
                        )
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { /* TODO: Initiate voice call */ }) {
                Icon(Icons.Default.Call, contentDescription = "Voice call")
            }
            IconButton(onClick = { /* TODO: Show overflow menu */ }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
        }
    )
}