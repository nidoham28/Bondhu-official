package com.nidoham.bondhu.presentation.component.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nidoham.bondhu.presentation.component.util.BOTTOM_BAR_HEIGHT
import com.nidoham.bondhu.presentation.component.util.BottomNavItem
import com.nidoham.bondhu.presentation.component.util.NAV_ITEMS

@Composable
fun BondhuBottomBar(
    selectedTab: Int,
    profileImageUrl: String?,
    onTabSelected: (Int) -> Unit
) {
    Column {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BOTTOM_BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_ITEMS.forEachIndexed { index, item ->
                val isSelected = selectedTab == index
                when (item) {
                    is BottomNavItem.IconTab -> NavIconTab(
                        item = item,
                        isSelected = isSelected,
                        onClick = { onTabSelected(index) }
                    )

                    is BottomNavItem.ProfileTab -> NavProfileTab(
                        item = item,
                        imageUrl = profileImageUrl,
                        isSelected = isSelected,
                        onClick = { onTabSelected(index) }
                    )
                }
            }
        }
    }
}