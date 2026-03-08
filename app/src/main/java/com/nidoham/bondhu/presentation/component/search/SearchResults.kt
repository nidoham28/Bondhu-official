package com.nidoham.bondhu.presentation.component.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.nidoham.bondhu.presentation.screen.main.tab.EmptyState
import com.nidoham.bondhu.presentation.screen.main.tab.ErrorState
import com.nidoham.bondhu.presentation.screen.main.tab.IdleState
import com.nidoham.bondhu.presentation.screen.main.tab.LoadingState
import org.nidoham.server.domain.model.User

@Composable
fun SearchResults(
    users: LazyPagingItems<User>,
    searchQuery: String,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when {
            // 1. If query is blank, show Idle State (centered)
            searchQuery.isBlank() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    IdleState()
                }
            }

            // 2. Initial Load Loading (centered)
            users.loadState.refresh is LoadState.Loading && users.itemCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingState()
                }
            }

            // 3. Initial Load Error (centered)
            users.loadState.refresh is LoadState.Error -> {
                val err = (users.loadState.refresh as LoadState.Error).error
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorState(
                        message = err.localizedMessage ?: "Something went wrong",
                        onRetry = { users.retry() }
                    )
                }
            }

            // 4. Empty Result (centered)
            users.itemCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(query = searchQuery)
                }
            }

            // 5. Success - Show List
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {

                    // Header
                    item {
                        Text(
                            text = "People",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    // List Items (Modern Paging Syntax)
                    items(
                        count = users.itemCount,
                        key = users.itemKey { it.uid }
                    ) { index ->
                        users[index]?.let { user ->
                            SearchRow(
                                user = user,
                                onClick = { onUserClick(user.uid) }
                            )
                        }
                    }

                    // Append State (Loading more / Footer Error)
                    item {
                        when (users.loadState.append) {
                            is LoadState.Loading -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            is LoadState.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    TextButton(onClick = { users.retry() }) {
                                        Text(
                                            text = "Retry",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}