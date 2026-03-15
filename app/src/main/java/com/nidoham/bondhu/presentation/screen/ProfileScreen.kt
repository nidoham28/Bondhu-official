package com.nidoham.bondhu.presentation.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ErrorOutline // FIX 3: correct namespace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel // FIX 1: correct import path
import coil.compose.AsyncImage
import com.nidoham.bondhu.R
import com.nidoham.bondhu.presentation.component.profile.PostItem
import com.nidoham.bondhu.presentation.component.profile.ProfileUiState
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.viewmodel.ProfileViewModel
import com.nidoham.server.domain.participant.User

// ─────────────────────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    profileUserId: String? = null,
    onNavigateBack: () -> Unit = {},
    onEditProfile: () -> Unit = {},
    onShareProfile: () -> Unit = {},
    onMessage: ((String) -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isOnline by viewModel.isTargetOnline.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val isDataStale = remember(uiState.user, uiState.isOwner, profileUserId) {
        val user = uiState.user
        when {
            user == null -> false
            !profileUserId.isNullOrBlank() -> user.uid != profileUserId
            else -> !uiState.isOwner
        }
    }

    LaunchedEffect(profileUserId) {
        viewModel.loadProfile(profileUserId)
        if (profileUserId.isNullOrBlank()) viewModel.updateLastActive()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val screenState = remember(uiState.isLoading, uiState.error, uiState.user, isDataStale) {
            when {
                isDataStale -> ScreenState.Loading
                uiState.isLoading -> ScreenState.Loading
                uiState.error != null -> ScreenState.Error(uiState.error!!)
                uiState.user != null -> ScreenState.Content(uiState.user!!)
                else -> ScreenState.Loading
            }
        }

        AnimatedContent(
            targetState = screenState,
            transitionSpec = { screenTransitionSpec() },
            label = "ProfileScreenTransition"
        ) { state ->
            when (state) {
                is ScreenState.Loading -> ProfileShimmerLoading()

                is ScreenState.Content -> ProfileContent(
                    user = state.user,
                    uiState = uiState,
                    isOnline = isOnline,
                    onPrimaryClick = {
                        if (uiState.isOwner) onEditProfile()
                        else viewModel.toggleFollow()
                    },
                    onSecondaryClick = {
                        if (uiState.isOwner) {
                            onShareProfile()
                        } else {
                            viewModel.startConversation(state.user.uid) { conversationId ->
                                NavigationHelper.navigateToChat(context, conversationId)
                                onMessage?.invoke(conversationId)
                            }
                        }
                    }
                )

                is ScreenState.Error -> ErrorScreen(
                    error = state.message,
                    onRetry = { viewModel.refreshProfile() },
                    onNavigateBack = onNavigateBack
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Screen State
// ─────────────────────────────────────────────────────────────

private sealed class ScreenState {
    data object Loading : ScreenState()
    data class Content(val user: User) : ScreenState()
    data class Error(val message: String) : ScreenState()
}

private fun AnimatedContentTransitionScope<ScreenState>.screenTransitionSpec(): ContentTransform =
    fadeIn(animationSpec = tween(300)) +
            scaleIn(initialScale = 0.95f, animationSpec = tween(300)) togetherWith
            fadeOut(animationSpec = tween(200)) +
            scaleOut(targetScale = 1.05f, animationSpec = tween(200))

// ─────────────────────────────────────────────────────────────
// Shimmer Loading
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileShimmerLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(100.dp)
                .clip(CircleShape)
                .shimmerEffect()
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .width(150.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmerEffect()
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(100.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmerEffect()
        )
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(3) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(50.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
        }
        Spacer(Modifier.height(32.dp))
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmerEffect()
            )
        }
    }
}

@Composable
private fun Modifier.shimmerEffect(): Modifier {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )
    return drawWithCache {
        val brush = Brush.linearGradient(
            colors = listOf(
                Color.LightGray.copy(alpha = 0.3f),
                Color.White.copy(alpha = 0.5f),
                Color.LightGray.copy(alpha = 0.3f)
            ),
            start = Offset(translateAnim, 0f),
            end = Offset(translateAnim + size.width, size.height)
        )
        onDrawBehind { drawRect(brush = brush) }
    }
}

// ─────────────────────────────────────────────────────────────
// Error screen
// ─────────────────────────────────────────────────────────────

@Composable
private fun ErrorScreen(error: String, onRetry: () -> Unit, onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.ErrorOutline, // FIX 3: was Icons.Default.ErrorOutline
            null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNavigateBack) { Text("Go Back") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Profile content
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileContent(
    user: User,
    uiState: ProfileUiState,
    isOnline: Boolean,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(24.dp))
            ProfileHeader(user = user, isOwner = uiState.isOwner, isOnline = isOnline)
            Spacer(Modifier.height(24.dp))
            StatsRow(
                postsCount = uiState.postsCount,
                followersCount = uiState.followersCount,
                followingCount = uiState.followingCount
            )
            Spacer(Modifier.height(16.dp))
            ActionButtons(
                isOwner = uiState.isOwner,
                isFollowing = uiState.isFollowing,
                isFollowLoading = uiState.isFollowLoading,
                isMessageLoading = uiState.isMessageLoading,
                onPrimaryClick = onPrimaryClick,
                onSecondaryClick = onSecondaryClick
            )
            Spacer(Modifier.height(20.dp))
            PostItem(userId = user.uid)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Top bar
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileTopBar(title: String, onNavigateBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = if (title == "Loading...") 0.dp else 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = {}) {
                Icon(
                    Icons.Default.MoreVert,
                    "More options",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Profile header
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileHeader(user: User, isOwner: Boolean, isOnline: Boolean) {
    val name = user.displayName.takeIf { it.isNotBlank() }
        ?: user.username.takeIf { it.isNotBlank() } ?: "?"
    val initials = name.take(2).uppercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            if (!user.photoUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = "Profile picture",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        initials,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (isOwner) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3B82F6))
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        "Change photo",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // FIX 2: added Arrangement.Center + verticalAlignment so name and badge
        // are centered together, and removed the broken fillMaxWidth + align combo.
        // Also added a Spacer between the text and the badge so they don't crowd.
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (user.verified) {
                Spacer(Modifier.width(4.dp))
                Image(
                    painter = painterResource(R.drawable.verified),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        user.username.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text("@$it", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOnline) Color(0xFF10B981)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isOnline) "Online" else "Offline",
                fontSize = 13.sp,
                color = if (isOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!user.phoneNumber.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Phone,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    user.phoneNumber!!,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Stats row
// ─────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(postsCount: Long, followersCount: Long, followingCount: Long) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(count = postsCount.toString(), label = "Posts")
            VerticalDivider(
                modifier = Modifier.height(40.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            StatItem(count = formatCount(followersCount), label = "Followers")
            VerticalDivider(
                modifier = Modifier.height(40.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            StatItem(count = formatCount(followingCount), label = "Following")
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StatItem(count: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────────────────────────────────────────
// Action buttons
// ─────────────────────────────────────────────────────────────

@Composable
private fun ActionButtons(
    isOwner: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    isMessageLoading: Boolean,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit
) {
    val primaryLabel = if (isOwner) "Edit Profile" else if (isFollowing) "Unfollow" else "Follow"
    val primaryContainerColor =
        if (isOwner || !isFollowing) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val primaryContentColor =
        if (isOwner || !isFollowing) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onPrimaryClick,
            enabled = !isFollowLoading,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryContainerColor)
        ) {
            if (isFollowLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    primaryLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryContentColor
                )
            }
        }

        OutlinedButton(
            onClick = onSecondaryClick,
            enabled = !isMessageLoading,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (isMessageLoading && !isOwner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    if (isOwner) "Share Profile" else "Message",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}