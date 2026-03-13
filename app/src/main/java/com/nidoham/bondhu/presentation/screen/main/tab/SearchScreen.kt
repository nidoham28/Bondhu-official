package com.nidoham.bondhu.presentation.screen.main.tab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.nidoham.bondhu.presentation.component.search.SearchResults
import com.nidoham.bondhu.presentation.screen.search.YoutubeSearchScreen
import com.nidoham.bondhu.presentation.viewmodel.SearchTab
import com.nidoham.bondhu.presentation.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    viewModel   : SearchViewModel = hiltViewModel(),
    onUserClick : (String) -> Unit
) {
    val searchQuery  by viewModel.searchQuery.collectAsState()
    val activeTab    by viewModel.searchTab.collectAsState()
    val youTubeState by viewModel.youTubeState.collectAsState()
    val people       = viewModel.peopleResults.collectAsLazyPagingItems()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchPillField(
                query         = searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch      = { people.refresh() },
                modifier      = Modifier.weight(1f)
            )
        }

        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchTabChip(
                label    = "People",
                selected = activeTab == SearchTab.PEOPLE,
                onClick  = { viewModel.onTabSelected(SearchTab.PEOPLE) }
            )
            SearchTabChip(
                label    = "YouTube",
                selected = activeTab == SearchTab.YOUTUBE,
                onClick  = { viewModel.onTabSelected(SearchTab.YOUTUBE) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        AnimatedContent(
            targetState    = activeTab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label          = "SearchTabContent",
            modifier       = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                SearchTab.PEOPLE  -> SearchResults(
                    users       = people,
                    searchQuery = searchQuery,
                    onUserClick = onUserClick,
                    modifier    = Modifier.fillMaxSize()
                )
                SearchTab.YOUTUBE -> YoutubeSearchScreen(
                    state      = youTubeState,
                    onLoadMore = viewModel::loadMoreYouTube,
                    onRetry    = viewModel::retryYouTubeSearch,
                    modifier   = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchTabChip(
    label    : String,
    selected : Boolean,
    onClick  : () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = {
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor  = MaterialTheme.colorScheme.primary,
            selectedLabelColor      = MaterialTheme.colorScheme.onPrimary,
            containerColor          = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            labelColor              = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled             = true,
            selected            = selected,
            borderColor         = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            selectedBorderColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(10.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Search pill field
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchPillField(
    query         : String,
    onQueryChange : (String) -> Unit,
    onSearch      : () -> Unit,
    modifier      : Modifier = Modifier
) {
    val cs        = MaterialTheme.colorScheme
    val pillBg    = cs.surfaceVariant.copy(alpha = 0.55f)
    val hintColor = cs.onSurfaceVariant.copy(alpha = 0.5f)
    val shape     = RoundedCornerShape(14.dp)

    BasicTextField(
        value           = query,
        onValueChange   = onQueryChange,
        singleLine      = true,
        textStyle       = TextStyle(
            color      = cs.onSurface,
            fontSize   = 15.sp,
            fontWeight = FontWeight.Normal
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        cursorBrush     = SolidColor(cs.primary),
        modifier        = modifier,
        decorationBox   = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(pillBg)
                    .padding(horizontal = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Search,
                    contentDescription = null,
                    tint               = if (query.isNotEmpty()) cs.primary else hintColor,
                    modifier           = Modifier.size(22.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text  = "Search",
                            color = hintColor,
                            style = TextStyle(fontSize = 15.sp)
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick  = { onQueryChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Clear,
                            contentDescription = "Clear",
                            tint               = cs.onSurfaceVariant,
                            modifier           = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared state composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun IdleState() {
    Box(modifier = Modifier.fillMaxSize())
}

@Composable
fun LoadingState() {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier    = Modifier.size(32.dp),
            strokeWidth = 2.5.dp,
            color       = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun EmptyState(query: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector        = Icons.Outlined.Person,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = cs.onBackground.copy(alpha = 0.15f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text       = "No results for \"$query\"",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = cs.onBackground,
            textAlign  = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text      = "Try a different username",
            style     = MaterialTheme.typography.bodyMedium,
            color     = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector        = Icons.Outlined.WifiOff,
            contentDescription = null,
            modifier           = Modifier.size(48.dp),
            tint               = cs.error.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text       = "Something went wrong",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = cs.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodySmall,
            color     = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onRetry) {
            Text(
                text       = "Try again",
                color      = cs.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}