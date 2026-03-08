package com.nidoham.bondhu.presentation.screen.main.tab

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.nidoham.bondhu.R
import com.nidoham.bondhu.presentation.component.common.TopBar
import com.nidoham.bondhu.presentation.component.search.SearchResults
import com.nidoham.bondhu.presentation.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val users = viewModel.searchResults.collectAsLazyPagingItems()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(
            title = stringResource(R.string.app_name),
            onNotificationClick = {},
            onAddClick = {},
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchPillField(
                query         = searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch      = { users.refresh() },
                modifier      = Modifier.weight(1f)
            )
        }

        SearchResults(
            users       = users,
            searchQuery = searchQuery,
            onUserClick = onUserClick,
            modifier    = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SearchPillField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs        = MaterialTheme.colorScheme
    val pillBg    = cs.surfaceVariant.copy(alpha = 0.55f)
    val hintColor = cs.onSurfaceVariant.copy(alpha = 0.5f)

    BasicTextField(
        value         = query,
        onValueChange = onQueryChange,
        singleLine    = true,
        textStyle     = TextStyle(
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
                    .height(45.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .padding(horizontal = 12.dp),
                verticalAlignment   = Alignment.CenterVertically,
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

@Composable
fun IdleState() {
    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        // Intentionally empty
    }
}

@Composable
fun LoadingState() {
    Box(
        modifier        = Modifier.fillMaxSize(),
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
            text      = "No results for \"$query\"",
            style     = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color     = cs.onBackground,
            textAlign = TextAlign.Center
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
            text  = "Something went wrong",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onBackground
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