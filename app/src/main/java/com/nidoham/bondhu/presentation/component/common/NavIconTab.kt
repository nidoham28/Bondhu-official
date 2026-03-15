package com.nidoham.bondhu.presentation.component.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.nidoham.bondhu.presentation.component.util.BottomNavItem
import com.nidoham.bondhu.presentation.component.util.ICON_LABEL_GAP
import com.nidoham.bondhu.presentation.component.util.NAV_ICON_SIZE
import com.nidoham.bondhu.presentation.component.util.TAB_WIDTH

@Composable
fun NavIconTab(
    item: BottomNavItem.IconTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabScale_${item.contentDescription}"
    )
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
            AnimatedContent(
                targetState = isSelected,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "iconSwap_${item.contentDescription}"
            ) { sel ->
                Icon(
                    painter = painterResource(if (sel) item.selectedRes else item.unselectedRes),
                    contentDescription = item.contentDescription,
                    modifier = Modifier
                        .size(NAV_ICON_SIZE)
                        .scale(scale),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = iconAlpha)
                )
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