package com.nidoham.bondhu.presentation.component.util

import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.R

// ─────────────────────────────────────────────────────────────────────────────
// Sizing constants
// ─────────────────────────────────────────────────────────────────────────────

val TOP_BAR_HEIGHT    = 56.dp
val BOTTOM_BAR_HEIGHT = 62.dp
val NAV_ICON_SIZE     = 26.dp
val PROFILE_IMG_SIZE  = 26.dp
val TOP_ICON_SIZE     = 24.dp
val TAB_WIDTH         = 56.dp
val ICON_LABEL_GAP    = 3.dp

// ─────────────────────────────────────────────────────────────────────────────
// Tab indices
// ─────────────────────────────────────────────────────────────────────────────

const val TAB_HOME = 0
const val TAB_CHATS = 1
const val TAB_COMMUNITY = 2
const val TAB_NOTIFICATION = 3
const val TAB_PROFILE = 4

val NAV_ITEMS = listOf(
    BottomNavItem.IconTab(R.drawable.ic_home_filled,      R.drawable.ic_home_outline,      "Home"),
    BottomNavItem.IconTab(R.drawable.ic_chat_filled,      R.drawable.ic_chat_outline,      "Chats"),
    BottomNavItem.IconTab(R.drawable.ic_community_filled, R.drawable.ic_community_outline, "Community"),
    BottomNavItem.IconTab(R.drawable.ic_notification_filled,     R.drawable.ic_notification_outline,     "Inbox"),
    BottomNavItem.ProfileTab(R.drawable.ic_profile_filled, R.drawable.ic_profile_outline)
)