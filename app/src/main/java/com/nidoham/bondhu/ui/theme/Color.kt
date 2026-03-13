package com.nidoham.bondhu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw color tokens for the Bondhu design system.
 * Reference these constants in [DarkColorScheme] and [LightColorScheme] only;
 * Composables should consume colors via [MaterialTheme.colorScheme].
 */
object AppColors {
    val Blue500  = Color(0xFF2979FF)
    val Blue400  = Color(0xFF5393FF)
    val Blue700  = Color(0xFF0057E0)
    val Blue50   = Color(0xFFE8F1FF)

    val Violet500 = Color(0xFF7C3AED)
    val Violet300 = Color(0xFFA78BFF)
    val Violet50  = Color(0xFFF5F3FF)

    val Cyan300  = Color(0xFF48CAE4)
    val Cyan500  = Color(0xFF00B4D8)
    val Cyan50   = Color(0xFFE0F7FA)

    val DarkBg          = Color(0xFF1D2535)
    val DarkSurface     = Color(0xFF252D3D)
    val DarkSurfaceVariant = Color(0xFF2D3545)

    val White = Color(0xFFFFFFFF)
    val Ice50 = Color(0xFFF0F6FF)

    val LightGreen = Color(0xFF19AB60)
    val DarkGreen  = Color(0xFF008169)
}

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary             = AppColors.Blue400,
    onPrimary           = AppColors.DarkBg,
    primaryContainer    = AppColors.Blue700,
    onPrimaryContainer  = AppColors.Blue50,

    secondary            = AppColors.Violet300,
    onSecondary          = AppColors.DarkBg,
    secondaryContainer   = AppColors.Violet500,
    onSecondaryContainer = AppColors.Violet50,

    tertiary            = AppColors.Cyan300,
    onTertiary          = AppColors.DarkBg,
    tertiaryContainer   = AppColors.Cyan500,
    onTertiaryContainer = AppColors.Cyan50,

    background   = AppColors.DarkBg,
    onBackground = AppColors.White,

    surface            = AppColors.DarkSurface,
    onSurface          = AppColors.White,
    surfaceVariant     = AppColors.DarkSurfaceVariant,
    onSurfaceVariant   = Color(0xFFE0E0E0),

    outline        = Color(0xFF4A5568),
    outlineVariant = Color(0xFF3A4558),

    error   = Color(0xFFFF6B6B),
    onError = AppColors.DarkBg,

    scrim = AppColors.DarkBg.copy(alpha = 0.80f)
)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary             = AppColors.Blue500,
    onPrimary           = AppColors.White,
    primaryContainer    = AppColors.Blue50,
    onPrimaryContainer  = Color(0xFF003BB5),

    secondary            = AppColors.Violet500,
    onSecondary          = AppColors.White,
    secondaryContainer   = Color(0xFFEDE9FF),
    onSecondaryContainer = Color(0xFF5B21B6),

    tertiary            = AppColors.Cyan500,
    onTertiary          = AppColors.White,
    tertiaryContainer   = AppColors.Cyan50,
    onTertiaryContainer = Color(0xFF0077B6),

    background   = AppColors.Ice50,
    onBackground = AppColors.DarkBg,

    surface          = AppColors.White,
    onSurface        = AppColors.DarkBg,
    surfaceVariant   = Color(0xFFDEEBFF),
    onSurfaceVariant = AppColors.Violet500,

    outline        = Color(0xFFC2D9FF),
    outlineVariant = Color(0xFFE8F1FF),

    error   = Color(0xFFFF4C4C),
    onError = AppColors.White,

    scrim = AppColors.DarkBg.copy(alpha = 0.32f)
)