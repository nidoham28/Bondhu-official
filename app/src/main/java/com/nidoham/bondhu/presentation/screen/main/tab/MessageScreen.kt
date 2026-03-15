package com.nidoham.bondhu.presentation.screen.main.tab

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nidoham.bondhu.R
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.util.ConversationUiState
import com.nidoham.bondhu.presentation.viewmodel.ConversationWithUser
import com.nidoham.bondhu.presentation.viewmodel.MessageViewModel
import com.nidoham.server.util.ParticipantType
import java.text.SimpleDateFormat
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Screen Entry Point
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    viewModel: MessageViewModel = hiltViewModel()
) {
    // collectAsStateWithLifecycle is preferred over collectAsState — it
    // automatically pauses collection when the app goes to the background,
    // saving battery and avoiding unnecessary recompositions.
    val uiState: ConversationUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing: Boolean by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val currentUserId = viewModel.currentUserId
    val context = LocalContext.current
    val pullToRefreshState = rememberPullToRefreshState()

    // A shared click handler passed down to every conversation row so we don't
    // create a new lambda instance per recomposition in the lazy list.
    val onConversationClick: (String) -> Unit = { conversationId ->
        NavigationHelper.navigateToChat(context, conversationId)
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh    = { viewModel.refresh() },
        state        = pullToRefreshState,
        modifier     = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = uiState) {

            // ── Full-screen skeleton shown only on the very first load ────────
            is ConversationUiState.Loading -> {
                ConversationShimmerList()
            }

            // ── Refreshing: list stays on screen, PTR indicator handles the UI
            is ConversationUiState.Refreshing -> {
                ConversationList(
                    items         = state.items,
                    currentUserId = currentUserId,
                    onClick       = onConversationClick,
                    showFooter    = false,
                )
            }

            // ── LoadingMore: list visible + footer spinner at the bottom ──────
            is ConversationUiState.LoadingMore -> {
                ConversationList(
                    items         = state.items,
                    currentUserId = currentUserId,
                    onClick       = onConversationClick,
                    showFooter    = true // bottom spinner for pagination
                )
            }

            // ── Empty: user has no conversations yet ──────────────────────────
            is ConversationUiState.Empty -> {
                Box(
                    modifier        = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = "No conversations yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Success: normal, fully loaded state ───────────────────────────
            is ConversationUiState.Success -> {
                ConversationList(
                    items         = state.items,
                    currentUserId = currentUserId,
                    onClick       = onConversationClick,
                    showFooter    = false
                )
            }

            // ── Error: two sub-cases based on whether we have cached data ─────
            is ConversationUiState.Error -> {
                if (state.cachedItems != null) {
                    // We already have data — keep it visible and show a
                    // non-blocking banner at the top so the user can retry.
                    Box(modifier = Modifier.fillMaxSize()) {
                        ConversationList(
                            items         = state.cachedItems,
                            currentUserId = currentUserId,
                            onClick       = onConversationClick,
                            showFooter    = false
                        )
                        ErrorBanner(
                            message   = state.cause.localizedMessage ?: "Sync failed",
                            onRetry   = { viewModel.refresh() },
                            modifier  = Modifier.align(Alignment.TopCenter)
                        )
                    }
                } else {
                    // Initial load failed — full error page is appropriate here.
                    FullErrorScreen(
                        cause   = state.cause,
                        onRetry = { viewModel.refresh() }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Conversation List
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders the scrollable list of conversation rows.
 *
 * Extracted from the `when` branches so each state that needs a list (Success,
 * Refreshing, LoadingMore, Error with cache) can reuse the same implementation
 * without duplication.
 *
 * @param items         Conversations to display.
 * @param currentUserId Used to prefix "You:" on outgoing message previews.
 * @param onClick       Called with the conversation ID when a row is tapped.
 * @param showFooter    When true, appends a [FooterLoadingSpinner] at the bottom
 *                      to signal an in-progress paginated fetch.
 */
@Composable
private fun ConversationList(
    items: List<ConversationWithUser>,
    currentUserId: String,
    onClick: (conversationId: String) -> Unit,
    showFooter: Boolean
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            count = items.size,
            key   = { index -> items[index].conversation.parentId }
        ) { index ->
            val item = items[index]
            ConversationItem(
                item          = item,
                currentUserId = currentUserId,
                onClick       = onClick
            )
            if (index < items.lastIndex) {
                HorizontalDivider(
                    modifier  = Modifier.padding(start = 84.dp),
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }

        // Footer spinner only rendered when a next-page fetch is in progress.
        if (showFooter) {
            item(key = "footer_spinner") {
                FooterLoadingSpinner()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Conversation Item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationItem(
    item: ConversationWithUser,
    currentUserId: String,
    onClick: (conversationId: String) -> Unit
) {
    val conversation = item.conversation
    val peerUser     = item.peerUser
    val isGroup      = conversation.type == ParticipantType.GROUP.value

    val title = when {
        isGroup          -> conversation.title.orEmpty().ifEmpty { "Unnamed Group" }
        peerUser != null -> peerUser.displayName.ifEmpty {
            peerUser.username.ifEmpty { "Unknown" }
        }
        else             -> "Loading…"
    }

    val previewText = conversation.lastMessage?.let { msg ->
        if (msg.senderId == currentUserId) "You: ${msg.content}" else msg.content
    } ?: "No messages yet"

    // Firebase Timestamp.toDate() returns java.util.Date — no Long conversion needed.
    val timeString = conversation.lastMessage?.timestamp
        ?.toDate()
        ?.let { date -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date) }
        .orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(conversation.parentId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        AsyncImage(
            model              = peerUser?.photoUrl?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_launcher,
            contentDescription = "Avatar of $title",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        Spacer(modifier = Modifier.width(12.dp))

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
                text     = previewText,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting UI Components
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Non-blocking error banner shown at the top of the screen when cached data
 * is still available. Keeps the conversation list visible rather than
 * replacing it with a full error page.
 */
@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text     = message,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(
                    text  = "Retry",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Full-screen error state used only when the initial load fails and there is
 * no cached data to fall back on.
 */
@Composable
private fun FullErrorScreen(
    cause: Throwable,
    onRetry: () -> Unit
) {
    Box(
        modifier        = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = "Something went wrong",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text  = cause.localizedMessage ?: "Unknown error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text(text = "Retry")
            }
        }
    }
}

/**
 * Spinner shown as the last item in the lazy list during a paginated fetch.
 * Keeps the list content stable while signalling that more is coming.
 */
@Composable
private fun FooterLoadingSpinner() {
    Box(
        modifier        = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier  = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer / Skeleton Loading
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationShimmerList() {
    val shimmerBrush = rememberShimmerBrush()
    LazyColumn(
        modifier          = Modifier.fillMaxSize(),
        contentPadding    = PaddingValues(vertical = 8.dp),
        userScrollEnabled = false
    ) {
        items(6) {
            ShimmerConversationRow(brush = shimmerBrush)
            HorizontalDivider(
                modifier  = Modifier.padding(start = 84.dp),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun ShimmerConversationRow(brush: Brush) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(brush)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1200f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslateX"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(translateX, 0f),
        end    = Offset(translateX + 400f, 0f)
    )
}