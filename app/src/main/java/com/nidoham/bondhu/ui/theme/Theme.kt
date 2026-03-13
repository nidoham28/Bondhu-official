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
 * Font scale options. Additional values can be added here and the
 * [Typography.scale] extension will apply them automatically.
 */
enum class FontScale(val multiplier: Float) {
    NORMAL(1.0f)
}

/**
 * Aggregates all user-configurable theme preferences.
 *
 * @property isDarkMode    Explicit dark/light override; null defers to the system setting.
 * @property dynamicColor  Whether to use Material You dynamic color on API 31+.
 * @property fontScale     Global typography scale multiplier.
 */
data class AppSettings(
    val isDarkMode: Boolean? = null,
    val dynamicColor: Boolean = true,
    val fontScale: FontScale = FontScale.NORMAL
) {
    fun resolveIsDark(systemIsDark: Boolean): Boolean = isDarkMode ?: systemIsDark
}

/** Shape tokens for the Bondhu design system. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Provides [AppSettings] to the composition tree. */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

/** Provides [CustomColorPalette] — semantic colors beyond the Material 3 baseline. */
val LocalCustomColors = staticCompositionLocalOf { CustomColorPalette() }

/**
 * Semantic color extensions not covered by [MaterialTheme.colorScheme].
 * Any field left as [Color.Unspecified] will be overridden by the theme defaults
 * computed inside [AppTheme].
 */
data class CustomColorPalette(
    val success   : Color = Color.Unspecified,
    val onSuccess : Color = Color.Unspecified,
    val warning   : Color = Color.Unspecified,
    val onWarning : Color = Color.Unspecified,
    val info      : Color = Color.Unspecified,
    val onInfo    : Color = Color.Unspecified
)

/**
 * Root theme composable for the Bondhu app.
 *
 * Resolves the active [ColorScheme] in priority order:
 * 1. Material You dynamic color (API 31+), with dark-mode background/surface
 *    anchored to [AppColors] tokens so the palette stays on-brand.
 * 2. Static [DarkColorScheme] or [LightColorScheme].
 *
 * System bar appearance is synchronized with [isDark] via a [SideEffect] so
 * icon tints remain legible against the active background.
 *
 * @param settings      User theme preferences; defaults to system dark mode + dynamic color.
 * @param systemIsDark  System dark-mode state, injected for testability.
 * @param customColors  Caller-provided overrides for [CustomColorPalette] fields.
 * @param content       The composition subtree to theme.
 */
@Composable
fun AppTheme(
    settings     : AppSettings = AppSettings(),
    systemIsDark : Boolean = isSystemInDarkTheme(),
    customColors : CustomColorPalette = CustomColorPalette(),
    content      : @Composable () -> Unit
) {
    val context = LocalContext.current
    val view    = LocalView.current
    val isDark  = settings.resolveIsDark(systemIsDark)

    val colorScheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) {
                dynamicDarkColorScheme(context).copy(
                    background     = AppColors.DarkBg,
                    surface        = AppColors.DarkSurface,
                    surfaceVariant = AppColors.DarkSurfaceVariant
                )
            } else {
                dynamicLightColorScheme(context)
            }
        }
        isDark -> DarkColorScheme
        else   -> LightColorScheme
    }

    val typography = AppTypography.scale(settings.fontScale.multiplier)

    val resolvedCustomColors = CustomColorPalette(
        success   = if (isDark) Color(0xFF00F5A0) else Color(0xFF00D68F),
        onSuccess = if (isDark) AppColors.DarkBg  else Color.White,
        warning   = if (isDark) Color(0xFFFFD54F) else Color(0xFFFFBD00),
        onWarning = if (isDark) AppColors.DarkBg  else Color.White,
        info      = if (isDark) AppColors.Cyan300  else AppColors.Cyan500,
        onInfo    = if (isDark) AppColors.DarkBg  else Color.White
    ).merge(customColors)

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalAppSettings  provides settings,
        LocalCustomColors provides resolvedCustomColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = typography,
            shapes      = AppShapes,
            content     = content
        )
    }
}

/**
 * Returns a [Typography] with every text style scaled uniformly by [factor].
 * Returns the receiver unchanged when [factor] is exactly 1.0 to avoid
 * unnecessary allocations on every recomposition.
 */
private fun Typography.scale(factor: Float): Typography {
    if (factor == 1f) return this

    fun TextStyle.scaled() = copy(
        fontSize   = fontSize * factor,
        lineHeight = lineHeight * factor
    )

    return copy(
        displayLarge  = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall  = displaySmall.scaled(),
        headlineLarge  = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall  = headlineSmall.scaled(),
        titleLarge  = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall  = titleSmall.scaled(),
        bodyLarge   = bodyLarge.scaled(),
        bodyMedium  = bodyMedium.scaled(),
        bodySmall   = bodySmall.scaled(),
        labelLarge  = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall  = labelSmall.scaled()
    )
}

/**
 * Merges [other] into this palette, replacing any field that [other] has
 * explicitly set (i.e. not [Color.Unspecified]).
 */
private fun CustomColorPalette.merge(other: CustomColorPalette) = CustomColorPalette(
    success   = if (other.success   != Color.Unspecified) other.success   else success,
    onSuccess = if (other.onSuccess != Color.Unspecified) other.onSuccess else onSuccess,
    warning   = if (other.warning   != Color.Unspecified) other.warning   else warning,
    onWarning = if (other.onWarning != Color.Unspecified) other.onWarning else onWarning,
    info      = if (other.info      != Color.Unspecified) other.info      else info,
    onInfo    = if (other.onInfo    != Color.Unspecified) other.onInfo    else onInfo
)