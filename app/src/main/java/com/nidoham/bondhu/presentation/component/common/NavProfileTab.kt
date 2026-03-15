package com.nidoham.bondhu.presentation.component.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nidoham.bondhu.presentation.component.util.BottomNavItem
import com.nidoham.bondhu.presentation.component.util.ICON_LABEL_GAP
import com.nidoham.bondhu.presentation.component.util.NAV_ICON_SIZE
import com.nidoham.bondhu.presentation.component.util.PROFILE_IMG_SIZE
import com.nidoham.bondhu.presentation.component.util.TAB_WIDTH

@Composable
fun NavProfileTab(
    item: BottomNavItem.ProfileTab,
    imageUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val ringWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "profileRing"
    )
    val ringColor = MaterialTheme.colorScheme.onBackground
    val iconAlpha = if (isSelected) 1f else 0.50f

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(TAB_WIDTH)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
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
                                drawCircle(
                                    color = ringColor,
                                    radius = size.minDimension / 2f + strokePx / 2f + 2.dp.toPx(),
                                    style = Stroke(width = strokePx)
                                )
                            }
                        }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                AnimatedContent(
                    targetState = isSelected,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "profileIconSwap"
                ) { sel ->
                    Icon(
                        painter = painterResource(if (sel) item.fallbackSelectedRes else item.fallbackUnselectedRes),
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size(NAV_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = iconAlpha)
                    )
                }
            }

            Text(
                text = item.contentDescription,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = iconAlpha),
                maxLines = 1
            )
        }
    }
}