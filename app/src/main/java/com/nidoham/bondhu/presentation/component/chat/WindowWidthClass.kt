package com.nidoham.bondhu.presentation.component.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Classifies available horizontal screen width into three adaptive-layout
 * buckets, following the Material Design window-size class specification.
 *
 * | Bucket    | Width range | Typical device                       |
 * |-----------|-------------|--------------------------------------|
 * | [Compact] | < 600 dp    | Portrait phone                       |
 * | [Medium]  | 600–839 dp  | Large-phone landscape / small tablet |
 * | [Expanded]| ≥ 840 dp    | Tablet or foldable in table-top mode |
 *
 * Every chat component (input bar, top bar, message list) accepts a
 * [WindowWidthClass] to adjust paddings, font sizes, and avatar sizes without
 * embedding breakpoint logic inside each composable.
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/**
 * Maps this [Dp] measurement to the appropriate [WindowWidthClass] bucket.
 *
 * Intended to be called with `maxWidth` from a [BoxWithConstraints] scope:
 * ```kotlin
 * BoxWithConstraints {
 *     val windowClass = maxWidth.toWindowWidthClass()
 * }
 * ```
 */
internal fun Dp.toWindowWidthClass(): WindowWidthClass = when {
    this < 600.dp -> WindowWidthClass.Compact
    this < 840.dp -> WindowWidthClass.Medium
    else          -> WindowWidthClass.Expanded
}