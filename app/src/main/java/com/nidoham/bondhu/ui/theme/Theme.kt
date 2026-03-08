package com.nidoham.bondhu.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * ফন্ট স্কেল অপশন।
 */
enum class FontScale(val multiplier: Float) {
    NORMAL(1.0f)
}

/**
 * অ্যাপ সেটিংস ডেটা ক্লাস।
 */
data class AppSettings(
    val isDarkMode: Boolean? = null,
    val dynamicColor: Boolean = true,
    val fontScale: FontScale = FontScale.NORMAL
) {
    fun resolveIsDark(systemIsDark: Boolean): Boolean {
        return isDarkMode ?: systemIsDark
    }
}

/**
 * অ্যাপের শেপ সিস্টেম।
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * সেটিংস এক্সেসের জন্য CompositionLocal।
 */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

/**
 * কাস্টম কালার এক্সটেনশনের জন্য CompositionLocal।
 */
val LocalCustomColors = staticCompositionLocalOf { CustomColorPalette() }

/**
 * কাস্টম কালার প্যালেট।
 */
data class CustomColorPalette(
    val success: Color = Color.Unspecified,
    val onSuccess: Color = Color.Unspecified,
    val warning: Color = Color.Unspecified,
    val onWarning: Color = Color.Unspecified,
    val info: Color = Color.Unspecified,
    val onInfo: Color = Color.Unspecified
)

/**
 * রুট থিম কম্পোজেবল।
 */
@Composable
fun AppTheme(
    settings: AppSettings = AppSettings(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
    customColors: CustomColorPalette = CustomColorPalette(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isDark = settings.resolveIsDark(systemIsDark)

    val colorScheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (isDark) dynamicScheme.copy(
                background = Color(0xFF1D2535),
                surface = Color(0xFF252D3D),
                surfaceVariant = Color(0xFF2D3545)
            ) else dynamicScheme
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val typography = if (settings.fontScale == FontScale.NORMAL) {
        AppTypography
    } else {
        AppTypography.scale(settings.fontScale.multiplier)
    }

    val customPalette = CustomColorPalette(
        success = if (isDark) Color(0xFF00F5A0) else Color(0xFF00D68F),
        onSuccess = if (isDark) Color(0xFF1D2535) else Color.White,
        warning = if (isDark) Color(0xFFFFD54F) else Color(0xFFFFBD00),
        onWarning = if (isDark) Color(0xFF1D2535) else Color.White,
        info = if (isDark) Color(0xFF48CAE4) else Color(0xFF00B4D8),
        onInfo = if (isDark) Color(0xFF1D2535) else Color.White
    ).merge(customColors)

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalAppSettings provides settings,
        LocalCustomColors provides customPalette
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}

/**
 * টাইপোগ্রাফি স্কেলিং এক্সটেনশন।
 */
private fun Typography.scale(factor: Float): Typography {
    if (factor == 1f) return this

    fun TextStyle.scaled() = copy(
        fontSize = fontSize * factor,
        lineHeight = lineHeight * factor
    )

    return copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled()
    )
}

/**
 * CustomColorPalette merge extension।
 */
private fun CustomColorPalette.merge(other: CustomColorPalette): CustomColorPalette {
    return CustomColorPalette(
        success = if (other.success != Color.Unspecified) other.success else success,
        onSuccess = if (other.onSuccess != Color.Unspecified) other.onSuccess else onSuccess,
        warning = if (other.warning != Color.Unspecified) other.warning else warning,
        onWarning = if (other.onWarning != Color.Unspecified) other.onWarning else onWarning,
        info = if (other.info != Color.Unspecified) other.info else info,
        onInfo = if (other.onInfo != Color.Unspecified) other.onInfo else onInfo
    )
}