package com.nidoham.bondhu.presentation.component.util

import androidx.annotation.DrawableRes

sealed class BottomNavItem {
    data class IconTab(
        @param:DrawableRes val selectedRes   : Int,
        @param:DrawableRes val unselectedRes : Int,
        val contentDescription : String
    ) : BottomNavItem()

    data class ProfileTab(
        @param:DrawableRes val fallbackSelectedRes   : Int,
        @param:DrawableRes val fallbackUnselectedRes : Int,
        val contentDescription : String = "Profile"
    ) : BottomNavItem()
}