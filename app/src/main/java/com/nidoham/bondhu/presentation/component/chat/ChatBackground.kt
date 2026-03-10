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

private const val LIGHT_BACKGROUND_URL = "file:///android_asset/light_background.jpg"
private const val DARK_BACKGROUND_URL  = "file:///android_asset/dark_background.jpg"

/**
 * Full-screen chat wallpaper that switches between [lightUrl] and [darkUrl]
 * according to the current system theme.
 *
 * This composable must be placed *below* the [androidx.compose.material3.Scaffold]
 * in the z-order (i.e. composed before it inside a
 * [androidx.compose.foundation.layout.Box] or
 * [androidx.compose.foundation.layout.BoxWithConstraints]), and the Scaffold's
 * `containerColor` must be set to [androidx.compose.ui.graphics.Color.Transparent]
 * so the wallpaper shows through.
 *
 * Both URL parameters accept any scheme recognised by [Coil](https://coil-kt.github.io/coil/):
 * `https://`, `file://`, `content://`, etc.
 *
 * @param lightUrl URL of the wallpaper to display in light mode.
 *                 Defaults to the bundled `light_background.jpg` asset.
 * @param darkUrl  URL of the wallpaper to display in dark mode.
 *                 Defaults to the bundled `dark_background.jpg` asset.
 */
@Composable
fun ChatBackground(
    url: Boolean = false,
    lightUrl: String = LIGHT_BACKGROUND_URL,
    darkUrl:  String = DARK_BACKGROUND_URL,
) {
    val imageUrl = if (isSystemInDarkTheme()) darkUrl else lightUrl
    val image = if (isSystemInDarkTheme()) R.drawable.dark_background else R.drawable.light_background

    if (url){
        AsyncImage(
            model              = imageUrl,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
    } else {
        AsyncImage(
            model              = image,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
    }
}