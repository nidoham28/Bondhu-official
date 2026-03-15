package com.nidoham.bondhu.presentation.screen.main.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nidoham.bondhu.R
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.viewmodel.ConversationWithUser
import com.nidoham.bondhu.presentation.viewmodel.MessageViewModel
import com.nidoham.server.util.ParticipantType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageScreen(
    viewModel: MessageViewModel = hiltViewModel()
) {

    val conversations: List<ConversationWithUser> by viewModel.conversations
        .collectAsState(initial = emptyList())

    val currentUserId = viewModel.currentUserId
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {

            conversations.isEmpty() -> {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No conversations yet",
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
                        items = conversations,
                        key = { it.conversation.parentId }
                    ) { item ->

                        ConversationItem(
                            item = item,
                            currentUserId = currentUserId,
                            onClick = { conversationId ->
                                if (conversationId.isNotBlank()) {
                                    NavigationHelper.navigateToChat(context, conversationId)
                                }
                            }
                        )

                    }

                }

            }

        }

    }

}

fun Long.toDate(): Date = Date(this)

@Composable
private fun ConversationItem(
    item: ConversationWithUser,
    currentUserId: String,
    onClick: (conversationId: String) -> Unit
) {

    val conversation = item.conversation
    val peerUser = item.peerUser

    val isGroup = conversation.type == ParticipantType.GROUP.value

    val title = when {
        isGroup -> conversation.title.orEmpty().ifEmpty { "Unnamed Group" }
        peerUser != null -> peerUser.displayName.ifEmpty {
            peerUser.username.ifEmpty { "Unknown" }
        }
        else -> "Loading..."
    }

    val avatarUrl = peerUser?.photoUrl ?: ""

    val previewText = conversation.lastMessage?.let { msg ->
        if (msg.senderId == currentUserId) {
            "You: ${msg.content}"
        } else {
            msg.content
        }
    } ?: "No messages yet"

    val timeString = conversation.lastMessage?.timestamp?.toDate()?.let { date ->
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(conversation.parentId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        AsyncImage(
            model = avatarUrl.takeIf { it.isNotEmpty() } ?: R.drawable.ic_launcher,
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.LightGray)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = previewText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

    }

}