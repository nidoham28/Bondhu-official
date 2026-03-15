package com.nidoham.bondhu.presentation.screen.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nidoham.bondhu.presentation.component.common.BondhuBottomBar
import com.nidoham.bondhu.presentation.component.common.BondhuTopBar
import com.nidoham.bondhu.presentation.component.util.TAB_CHATS
import com.nidoham.bondhu.presentation.component.util.TAB_COMMUNITY
import com.nidoham.bondhu.presentation.component.util.TAB_HOME
import com.nidoham.bondhu.presentation.component.util.TAB_NOTIFICATION
import com.nidoham.bondhu.presentation.component.util.TAB_PROFILE
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.ProfileScreen
import com.nidoham.bondhu.presentation.screen.main.tab.CommunityScreen
import com.nidoham.bondhu.presentation.screen.main.tab.HomeScreen
import com.nidoham.bondhu.presentation.screen.main.tab.InboxScreen
import com.nidoham.bondhu.presentation.screen.main.tab.MessageScreen
import kotlinx.coroutines.launch

/**
 * Root screen owning the [Scaffold], top bar, bottom nav, and animated tab content.
 *
 * @param profileImageUrl Optional remote URL for the current user's avatar.
 */
@Composable
fun MainScreen(profileImageUrl: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedTab by remember { mutableIntStateOf(TAB_HOME) }
    var targetProfileUid by remember { mutableStateOf<String?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // TODO: Implement Drawer Content (e.g., AppDrawer)
        }
    ) {
        Scaffold(
            topBar = {
                BondhuTopBar(
                    onMenuClick = {
                        scope.launch { drawerState.open() }
                    },
                    onSearchClick = {
                        NavigationHelper.navigateToSearch(context)
                    }
                )
            },
            bottomBar = {
                BondhuBottomBar(
                    selectedTab = selectedTab,
                    profileImageUrl = profileImageUrl,
                    onTabSelected = { index ->
                        if (index == TAB_PROFILE) targetProfileUid = null
                        selectedTab = index
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(180, easing = FastOutSlowInEasing))
                },
                label = "MainTabTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { tab ->
                when (tab) {
                    TAB_HOME -> HomeScreen()
                    TAB_CHATS -> MessageScreen()
                    TAB_COMMUNITY -> CommunityScreen()
                    TAB_NOTIFICATION -> InboxScreen()
                    TAB_PROFILE -> ProfileScreen(
                        profileUserId = targetProfileUid,
                        onNavigateBack = {
                            if (targetProfileUid != null) {
                                targetProfileUid = null
                                selectedTab = TAB_CHATS
                            }
                        }
                    )
                }
            }
        }
    }
}