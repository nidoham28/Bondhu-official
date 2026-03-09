package com.nidoham.bondhu.presentation.screen.main.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.nidoham.bondhu.R
import com.nidoham.bondhu.presentation.component.common.TopBar
// Renamed from ChatsScreen → MessageScreen
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.viewmodel.ConversationWithUser
import com.nidoham.bondhu.presentation.viewmodel.MessageViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MessageScreen(
    viewModel: MessageViewModel = hiltViewModel()
    // FIX: removed onNavigateToChat callback — navigation is handled internally
    //      via LocalContext so the caller (MainScreen) doesn't need a no-op lambda
) {
    val conversations: LazyPagingItems<ConversationWithUser> =
        viewModel.conversations.collectAsLazyPagingItems()

    val currentUserId = viewModel.currentUserId

    // FIX: grab context here so ConversationItem can call NavigationHelper
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopBar(
            title = stringResource(R.string.app_name),
            onNotificationClick = {},
            onAddClick = {},
        )

        when {
            conversations.loadState.refresh is LoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            conversations.loadState.refresh is LoadState.Error -> {
                val error = (conversations.loadState.refresh as LoadState.Error).error
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = "Failed to load chats: ${error.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            conversations.itemCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = "No conversations yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        count = conversations.itemCount,
                        key   = conversations.itemKey { it.conversation.conversationId }
                    ) { index ->
                        val item = conversations[index] ?: return@items
                        ConversationItem(
                            item          = item,
                            currentUserId = currentUserId,
                            onClick       = { conversationId ->
                                // FIX: open ChatActivity directly — only navigate if
                                //      a real conversationId exists (not blank)
                                if (conversationId.isNotBlank()) {
                                    NavigationHelper.navigateToChat(context, conversationId)
                                }
                            }
                        )
                    }

                    if (conversations.loadState.append is LoadState.Loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    item: ConversationWithUser,
    currentUserId: String,
    // FIX: callback now passes conversationId (not peerId) — that's what ChatActivity needs
    onClick: (conversationId: String) -> Unit
) {
    val conversation = item.conversation
    val peerUser     = item.peerUser

    val isGroup = conversation.isGroup

    val title = when {
        isGroup          -> conversation.title.ifEmpty { "Unnamed Group" }
        peerUser != null -> peerUser.displayName.ifEmpty {
            peerUser.username.ifEmpty { "Unknown" }
        }
        else             -> "Loading..."
    }

    val avatarUrl = peerUser?.photoUrl ?: ""

    val preview = conversation.lastMessage

    val previewText = when {
        preview == null                                         -> "No messages yet"
        preview.type == "text" && preview.content.isNotEmpty() -> preview.content
        preview.type == "image"                                 -> "📷 Photo"
        preview.type == "video"                                 -> "🎥 Video"
        preview.type == "audio"                                 -> "🎵 Voice Message"
        preview.type == "file"                                  -> "📎 File"
        else                                                    -> "New message"
    }

    val prefix          = if (preview?.senderId == currentUserId) "You: " else ""
    val displaySubtitle = "$prefix$previewText"

    val timeString = preview?.timestamp?.toDate()?.let { date ->
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // FIX: pass conversationId — ChatActivity reads EXTRA_CONVERSATION_ID
            .clickable { onClick(conversation.conversationId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model              = avatarUrl.takeIf { it.isNotEmpty() } ?: R.drawable.ic_launcher,
            contentDescription = "Avatar",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.LightGray)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onBackground,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text  = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text     = displaySubtitle,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}