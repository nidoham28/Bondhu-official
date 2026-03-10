package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nidoham.server.data.util.EMOJI_CATEGORIES
import org.nidoham.server.data.util.EmojiCategory

/**
 * Full-screen emoji picker presented as a [ModalBottomSheet].
 *
 * Features:
 * - Category tabs (Recent + all [EMOJI_CATEGORIES]) with an animated active
 *   underline indicator.
 * - A search field that filters across all categories when non-empty, hiding
 *   the tab row in favour of a flat search-results grid.
 * - A 6-column adaptive grid ([GridCells.Adaptive] at 46 dp) so the layout
 *   responds gracefully to different screen widths.
 * - Empty states for both the "Recent" category (first launch) and search
 *   with no results.
 *
 * The [recentEmojis] list is owned by the calling composable ([ConversationScreen])
 * and passed here as a read-only [List]. This avoids the anti-pattern of sharing
 * a global mutable list across files.
 *
 * @param sheetState      Controls the expand/collapse animation of the sheet.
 * @param recentEmojis    Ordered list of recently used emojis; displayed when
 *                        the "Recent" tab is active and no search query is typed.
 * @param onDismiss       Called when the user swipes the sheet down or taps the
 *                        scrim; callers should hide the sheet and update visibility state.
 * @param onEmojiSelected Called with the selected emoji string; callers should
 *                        append it to the current draft and update [recentEmojis].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiBottomSheet(
    sheetState: SheetState,
    recentEmojis: List<String>,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val isDark       = isSystemInDarkTheme()
    val sheetBg      = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val searchBg     = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val activeColor  = if (isDark) Color.White       else Color(0xFF007AFF)
    val mutedColor   = Color(0xFF8E8E93)

    var searchQuery    by remember { mutableStateOf("") }
    var selectedTabIdx by remember { mutableIntStateOf(0) }

    // Prepend a synthetic "Recent" category so index 0 always maps to the
    // recent-emoji list regardless of what EMOJI_CATEGORIES contains.
    val allTabs = remember<List<EmojiCategory>> {
        listOf(EmojiCategory("Recent", "🕐", emptyList())) + EMOJI_CATEGORIES
    }

    val displayedEmojis: List<String> = when {
        searchQuery.isNotBlank() -> EMOJI_CATEGORIES
            .flatMap { it.emojis }
            .filter { it.contains(searchQuery, ignoreCase = true) }
            .distinct()
        selectedTabIdx == 0      -> recentEmojis
        else                     -> allTabs.getOrNull(selectedTabIdx)?.emojis ?: emptyList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = sheetBg,
        dragHandle = {
            // Custom drag handle: a rounded pill, matching the iOS sheet style.
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF3A3A3C) else Color(0xFFC7C7CC))
                )
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {

            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(searchBg)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector        = Icons.Default.Search,
                    contentDescription = null,
                    tint               = mutedColor,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                TextField(
                    value         = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier      = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp),
                    placeholder = {
                        Text(
                            "Search emoji",
                            style = MaterialTheme.typography.bodySmall.copy(color = mutedColor)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor             = activeColor,
                        focusedTextColor        = if (isDark) Color.White else Color.Black,
                        unfocusedTextColor      = if (isDark) Color.White else Color.Black
                    ),
                    textStyle       = MaterialTheme.typography.bodySmall,
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }

            // ── Category tabs — hidden while the user is searching ────────────
            AnimatedVisibility(visible = searchQuery.isBlank()) {
                Column {
                    LazyRow(
                        modifier              = Modifier.fillMaxWidth(),
                        contentPadding        = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(allTabs) { idx, category ->
                            val isSelected = idx == selectedTabIdx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedTabIdx = idx }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(category.tabIcon, fontSize = 22.sp)
                                Spacer(Modifier.height(3.dp))
                                // Active indicator underline.
                                Box(
                                    Modifier
                                        .height(2.5.dp)
                                        .width(26.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) activeColor else Color.Transparent
                                        )
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color     = if (isDark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
                        thickness = 0.5.dp
                    )
                }
            }

            // ── Section label ─────────────────────────────────────────────────
            val sectionLabel = if (searchQuery.isNotBlank()) "Search results"
            else allTabs.getOrNull(selectedTabIdx)?.label ?: ""
            Text(
                text     = sectionLabel.uppercase(),
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
                style    = MaterialTheme.typography.labelSmall.copy(
                    color         = mutedColor,
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                )
            )

            // ── Emoji grid or empty state ─────────────────────────────────────
            if (displayedEmojis.isEmpty()) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = if (searchQuery.isNotBlank()) "No results for \"$searchQuery\""
                        else "No recent emojis yet",
                        style = MaterialTheme.typography.bodySmall.copy(color = mutedColor)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Adaptive(46.dp),
                    modifier              = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentPadding        = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement   = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Fixed: was incorrectly calling non-existent `gridItems`;
                    // the correct LazyVerticalGrid API is `items`.
                    items(displayedEmojis) { emoji ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier         = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEmojiSelected(emoji) }
                        ) {
                            Text(emoji, fontSize = 26.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}