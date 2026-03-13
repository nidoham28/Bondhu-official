package com.nidoham.bondhu.presentation.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.presentation.component.content.ChannelRow
import com.nidoham.bondhu.presentation.component.content.PlaylistCard
import com.nidoham.bondhu.presentation.component.content.VideoCard
import com.nidoham.bondhu.presentation.viewmodel.YouTubeSearchState
import com.nidoham.extractor.stream.StreamItem

/**
 * YouTube search results screen.
 *
 * Renders one of four states based on [state]:
 * - **Idle** — when no query has been entered yet.
 * - **Loading** — spinner while the first page is being fetched.
 * - **Error** — error message with a retry action.
 * - **Results** — paginated [LazyColumn] of [VideoCard], [ChannelRow], or [PlaylistCard]
 *   depending on each item's [StreamItem.ItemType], with automatic load-more triggering
 *   when the user scrolls near the bottom.
 *
 * @param state Current [YouTubeSearchState] owned by the ViewModel.
 * @param onLoadMore Called when the list is near the bottom and more results are available.
 * @param onRetry Called when the user taps "Try again" on the error state.
 * @param onItemClick Called when the user taps any result item; receives the tapped [StreamItem].
 * @param modifier Modifier applied to the root container.
 */
@Composable
fun YoutubeSearchScreen(
    state       : YouTubeSearchState,
    onLoadMore  : () -> Unit,
    onRetry     : () -> Unit,
    onItemClick : (StreamItem) -> Unit,
    modifier    : Modifier = Modifier,
) {
    Box(
        modifier         = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // ── Initial idle: no query has been entered ────────────────────────
            !state.isLoading && state.items.isEmpty() && state.error == null
                    && state.searchedQuery.isBlank() -> {
                YouTubeIdleState()
            }

            // ── First-page loading spinner ─────────────────────────────────────
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier    = Modifier.size(36.dp),
                    strokeWidth = 2.5.dp,
                    color       = MaterialTheme.colorScheme.primary,
                )
            }

            // ── Error with retry ───────────────────────────────────────────────
            state.error != null && state.items.isEmpty() -> {
                YouTubeErrorState(
                    message = state.error,
                    onRetry = onRetry,
                )
            }

            // ── Query returned zero items ──────────────────────────────────────
            state.items.isEmpty() && state.searchedQuery.isNotBlank() -> {
                YouTubeEmptyState(query = state.searchedQuery)
            }

            // ── Results list ───────────────────────────────────────────────────
            else -> {
                YouTubeResultsList(
                    state       = state,
                    onLoadMore  = onLoadMore,
                    onRetry     = onRetry,
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Results list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouTubeResultsList(
    state       : YouTubeSearchState,
    onLoadMore  : () -> Unit,
    onRetry     : () -> Unit,
    onItemClick : (StreamItem) -> Unit,
) {
    val listState = rememberLazyListState()

    // Trigger load-more when 3 items from the end become visible.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyColumn(
        state    = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        // ── Result items ───────────────────────────────────────────────────────
        items(
            items = state.items,
            key   = { it.url ?: it.name },
        ) { item ->
            when (item.type) {
                StreamItem.ItemType.VIDEO    -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                    ) {
                        VideoCard(item = item)
                    }
                }
                StreamItem.ItemType.CHANNEL  -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                    ) {
                        ChannelRow(item = item)
                    }
                    HorizontalDivider(
                        modifier  = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                StreamItem.ItemType.PLAYLIST -> {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                    ) {
                        PlaylistCard(item = item)
                    }
                }
                else                         -> Unit // gracefully skip unsupported types
            }
        }

        // ── Footer: load-more indicator or inline error ────────────────────────
        item(key = "footer") {
            when {
                state.isLoadingMore -> {
                    Box(
                        modifier         = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                state.error != null -> {
                    // Pagination error — show inline retry rather than replacing the list.
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text      = state.error,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = onRetry) {
                            Text(
                                text       = "Try again",
                                color      = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                !state.hasNextPage && state.items.isNotEmpty() -> {
                    // End-of-results hint.
                    Box(
                        modifier         = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "No more results",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Idle state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouTubeIdleState() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector        = Icons.Outlined.VideoLibrary,
            contentDescription = null,
            modifier           = Modifier.size(52.dp),
            tint               = cs.primary.copy(alpha = 0.35f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text      = "Search YouTube",
            style     = MaterialTheme.typography.titleMedium,
            color     = cs.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text      = "Videos, channels, and playlists — all in one place.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state (full-screen, for first-page failures)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouTubeErrorState(
    message : String,
    onRetry : () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.WifiOff,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = cs.error.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text       = "Something went wrong",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = cs.onBackground,
        )
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodySmall,
            color     = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(
                text       = "Try again",
                color      = cs.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty results state (query returned zero items)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouTubeEmptyState(query: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector        = Icons.Outlined.SearchOff,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = cs.onBackground.copy(alpha = 0.15f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text       = "No results for \"$query\"",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = cs.onBackground,
            textAlign  = TextAlign.Center,
        )
        Text(
            text      = "Try different keywords or remove filters.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}