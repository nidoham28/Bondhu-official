package com.nidoham.bondhu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Primary font family for the Bondhu design system.
 *
 * [FontFamily.SansSerif] resolves to Roboto on Android, providing a native
 * look-and-feel without bundling additional font assets.
 */
val AppFontFamily: FontFamily = FontFamily.SansSerif

/**
 * Vertically centers text within its line height.
 * Applied uniformly across the type scale to ensure consistent alignment
 * in buttons, chips, and other fixed-height containers.
 */
private val CenteredLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim      = LineHeightStyle.Trim.None
)

/**
 * Material 3 type scale for the Bondhu app.
 *
 * Usage guidance:
 * - **Display** — hero sections, large numerics, welcome screens.
 * - **Headline** — page titles, section headers, dialog titles.
 * - **Title** — card headers, list item titles, app bars.
 * - **Body** — paragraphs, descriptions, message content.
 * - **Label** — buttons, chips, captions, tabs, text fields.
 */
val AppTypography = Typography(
    // ── Display ──────────────────────────────────────────────────────────────
    displayLarge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 57.sp,
        lineHeight    = 64.sp,
        letterSpacing = (-0.25).sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    displayMedium = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 45.sp,
        lineHeight    = 52.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    displaySmall = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 36.sp,
        lineHeight    = 44.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // ── Headline ─────────────────────────────────────────────────────────────
    headlineLarge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 32.sp,
        lineHeight    = 40.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    headlineMedium = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 28.sp,
        lineHeight    = 36.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    headlineSmall = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 24.sp,
        lineHeight    = 32.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // ── Title ─────────────────────────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 22.sp,
        lineHeight    = 28.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    titleMedium = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 16.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.15.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    titleSmall = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // ── Body ──────────────────────────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.25.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    bodySmall = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 12.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // ── Label ─────────────────────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 12.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    labelSmall = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )
)

/**
 * Custom type tokens for use cases outside the Material 3 scale.
 * Access these directly where [MaterialTheme.typography] does not have
 * an appropriate slot.
 */
object CustomTypography {

    /** Large dashboard statistics, e.g. "85%". Tight tracking, heavy weight. */
    val heroStat = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.ExtraBold,
        fontSize      = 64.sp,
        lineHeight    = 64.sp,
        letterSpacing = (-2).sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Secondary statistics, e.g. chart axis values. */
    val mediumStat = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 32.sp,
        lineHeight    = 40.sp,
        letterSpacing = (-1).sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Monospace — order IDs, transaction hashes; distinguishes '0' from 'O'. */
    val monoCode = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontWeight    = FontWeight.Medium,
        fontSize      = 13.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Section dividers and background labels, e.g. "TODAY", "RECENT". */
    val overline = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 11.sp,
        lineHeight    = 16.sp,
        letterSpacing = 1.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Notification badges and count indicators in compact circular elements. */
    val badge = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 10.sp,
        lineHeight    = 12.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Heavy variant for primary CTA button labels. */
    val buttonHeavy = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Tags and chips. */
    val tag = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 12.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /** Secondary metadata and list subtitles where reduced visual weight is appropriate. */
    val meta = TextStyle(
        fontFamily    = AppFontFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 11.sp,
        lineHeight    = 14.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )
}