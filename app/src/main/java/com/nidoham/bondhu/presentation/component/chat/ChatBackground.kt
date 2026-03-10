package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.nidoham.bondhu.R

// ─── ChatBackground ───────────────────────────────────────────────────────────
//
// Design contract:
//  • The parent Scaffold and the Box wrapping the message list must both have
//    containerColor / background = Color.Transparent for the image to show through.
//  • Light mode → loads light_background.jpg  (local asset URL or remote URL).
//  • Dark  mode → loads dark_background.jpg   (local asset URL or remote URL).
//  • Images are cropped to fill the screen without distortion (ContentScale.Crop).
//  • If the image fails to load, the composable renders nothing — callers
//    should set a solid fallback color on their parent container if desired.
//
// ─────────────────────────────────────────────────────────────────────────────

private const val DEFAULT_LIGHT_BACKGROUND_URL = "file:///android_asset/light_background.jpg"
private const val DEFAULT_DARK_BACKGROUND_URL = "file:///android_asset/dark_background.jpg"

/**
 * A full-screen composable that renders a chat wallpaper.
 *
 * It automatically switches between light and dark variants based on the system theme.
 * It supports loading images either from remote URLs/Assets or local Drawable resources.
 *
 * This composable must be placed *below* the [androidx.compose.material3.Scaffold]
 * in the z-order (i.e., composed before it inside a Box). The Scaffold's
 * `containerColor` must be set to [androidx.compose.ui.graphics.Color.Transparent]
 * for the wallpaper to be visible.
 *
 * @param useRemoteSource If true, loads the image from the provided [lightModeUrl] or [darkModeUrl].
 *                         If false (default), loads from local drawable resources.
 * @param lightModeUrl The URL or asset path for the wallpaper in light mode.
 * @param darkModeUrl The URL or asset path for the wallpaper in dark mode.
 */
@Composable
fun ChatBackground(
    useRemoteSource: Boolean = false,
    lightModeUrl: String = DEFAULT_LIGHT_BACKGROUND_URL,
    darkModeUrl: String = DEFAULT_DARK_BACKGROUND_URL,
) {
    val isDarkTheme = isSystemInDarkTheme()

    // Determine the appropriate image source based on theme and source type.
    // Coil's 'model' accepts Any (both Int and String), allowing us to unify the logic.
    val imageModel: Any = if (useRemoteSource) {
        if (isDarkTheme) darkModeUrl else lightModeUrl
    } else {
        if (isDarkTheme) R.drawable.dark_background else R.drawable.light_background
    }

    AsyncImage(
        model = imageModel,
        contentDescription = null, // Decorative background element
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}