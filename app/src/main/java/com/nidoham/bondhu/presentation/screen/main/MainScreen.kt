package com.nidoham.bondhu.presentation.screen.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nidoham.bondhu.presentation.screen.main.tab.HomeScreen
import com.nidoham.bondhu.presentation.screen.main.tab.MessageScreen
import com.nidoham.bondhu.presentation.screen.main.tab.ProfileScreen
import com.nidoham.bondhu.presentation.screen.main.tab.ReelsScreen
import com.nidoham.bondhu.presentation.screen.main.tab.SearchScreen

// ─────────────────────────────────────────────────────────────────────────────
// Sizing constants — single source of truth
// ─────────────────────────────────────────────────────────────────────────────

private val BAR_HEIGHT       = 68.dp
private val TAB_WIDTH        = 56.dp
private val ICON_SIZE        = 26.dp
private val PROFILE_IMG_SIZE = 26.dp
private val ICON_LABEL_GAP   = 3.dp

// ─────────────────────────────────────────────────────────────────────────────
// Tab indices — single source of truth
// ─────────────────────────────────────────────────────────────────────────────

private const val TAB_HOME    = 0
private const val TAB_SEARCH  = 1
private const val TAB_REELS   = 2
private const val TAB_MESSAGES = 3
private const val TAB_PROFILE = 4

// ─────────────────────────────────────────────────────────────────────────────
// Nav Item Model
// ─────────────────────────────────────────────────────────────────────────────

sealed class BottomNavItem {
    data class IconTab(
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector,
        val contentDescription: String
    ) : BottomNavItem()

    data class ProfileTab(
        val fallbackSelectedIcon: ImageVector   = Icons.Filled.AccountCircle,
        val fallbackUnselectedIcon: ImageVector = Icons.Outlined.AccountCircle,
        val contentDescription: String          = "Profile"
    ) : BottomNavItem()
}

private val NAV_ITEMS = listOf(
    BottomNavItem.IconTab(
        selectedIcon       = Icons.Filled.Home,
        unselectedIcon     = Icons.Outlined.Home,
        contentDescription = "Home"
    ),
    BottomNavItem.IconTab(
        selectedIcon       = Icons.Filled.Search,
        unselectedIcon     = Icons.Outlined.Search,
        contentDescription = "Search"
    ),
    BottomNavItem.IconTab(
        selectedIcon       = Icons.Filled.Movie,
        unselectedIcon     = Icons.Outlined.Movie,
        contentDescription = "Reels"
    ),
    BottomNavItem.IconTab(
        selectedIcon       = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon     = Icons.AutoMirrored.Outlined.Chat,
        contentDescription = "Messages"
    ),
    BottomNavItem.ProfileTab()
)

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    profileImageUrl: String? = null
) {
    var selectedTab       by remember { mutableIntStateOf(TAB_HOME) }

    // Holds the UID of a user opened from SearchScreen.
    // null  → own profile (Profile tab tapped directly)
    // nonNull → another user's profile (navigated from search result)
    var targetProfileUid  by remember { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            InstagramBottomBar(
                selectedTab     = selectedTab,
                profileImageUrl = profileImageUrl,
                onTabSelected   = { index ->
                    // Tapping the Profile tab directly always shows own profile
                    if (index == TAB_PROFILE) targetProfileUid = null
                    selectedTab = index
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { _ ->
        AnimatedContent(
            targetState  = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
            },
            label    = "MainScreenTransition",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                TAB_HOME     -> HomeScreen()

                TAB_SEARCH   -> SearchScreen(
                    onUserClick = { uid ->
                        targetProfileUid = uid
                        selectedTab      = TAB_PROFILE
                    }
                )

                TAB_REELS    -> ReelsScreen()

                TAB_MESSAGES -> MessageScreen()

                TAB_PROFILE  -> ProfileScreen(
                    profileUserId  = targetProfileUid,
                    onNavigateBack = {
                        if (targetProfileUid != null) {
                            targetProfileUid = null
                            selectedTab      = TAB_SEARCH
                        }
                    }
                )

                else -> HomeScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramBottomBar(
    selectedTab: Int,
    profileImageUrl: String?,
    onTabSelected: (Int) -> Unit
) {
    Box {
        HorizontalDivider(
            modifier  = Modifier.align(Alignment.TopCenter),
            thickness = 0.5.dp,
            color     = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            NAV_ITEMS.forEachIndexed { index, item ->
                val selected = selectedTab == index
                when (item) {
                    is BottomNavItem.IconTab    -> NavIconTab(
                        item       = item,
                        isSelected = selected,
                        onClick    = { onTabSelected(index) }
                    )
                    is BottomNavItem.ProfileTab -> NavProfileTab(
                        item       = item,
                        imageUrl   = profileImageUrl,
                        isSelected = selected,
                        onClick    = { onTabSelected(index) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Icon Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NavIconTab(
    item: BottomNavItem.IconTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMediumLow
        ),
        label = "iconScale"
    )

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(TAB_WIDTH)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ICON_LABEL_GAP)
        ) {
            Box(
                modifier         = Modifier.scale(scale),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState  = isSelected,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label        = "iconSwap"
                ) { sel ->
                    Icon(
                        imageVector        = if (sel) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.contentDescription,
                        modifier           = Modifier.size(ICON_SIZE),
                        tint               = if (sel)
                            MaterialTheme.colorScheme.onBackground
                        else
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            Text(
                text   = item.contentDescription,
                style  = MaterialTheme.typography.labelSmall,
                color  = if (isSelected)
                    MaterialTheme.colorScheme.onBackground
                else
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NavProfileTab(
    item: BottomNavItem.ProfileTab,
    imageUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val ringWidth by animateDpAsState(
        targetValue   = if (isSelected) 2.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "profileRingWidth"
    )

    val ringColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(TAB_WIDTH)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ICON_LABEL_GAP)
        ) {
            if (!imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(PROFILE_IMG_SIZE)
                        .drawBehind {
                            val strokePx = ringWidth.toPx()
                            if (strokePx > 0f) {
                                val ringRadius = size.minDimension / 2f + strokePx / 2f + 2.dp.toPx()
                                drawCircle(
                                    color  = ringColor,
                                    radius = ringRadius,
                                    style  = Stroke(width = strokePx)
                                )
                            }
                        }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model              = imageUrl,
                        contentDescription = item.contentDescription,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                }
            } else {
                AnimatedContent(
                    targetState  = isSelected,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label        = "profileIconSwap"
                ) { sel ->
                    Icon(
                        imageVector        = if (sel) item.fallbackSelectedIcon else item.fallbackUnselectedIcon,
                        contentDescription = item.contentDescription,
                        modifier           = Modifier.size(ICON_SIZE),
                        tint               = if (sel)
                            MaterialTheme.colorScheme.onBackground
                        else
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            Text(
                text   = item.contentDescription,
                style  = MaterialTheme.typography.labelSmall,
                color  = if (isSelected)
                    MaterialTheme.colorScheme.onBackground
                else
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}