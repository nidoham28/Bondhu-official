package org.nidoham.server.util

import com.google.firebase.Timestamp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── FormatHelper ────────────────────────────────────────────────────────────
//
// A collection of pure, stateless extension functions for formatting raw data
// values into human-readable strings across the presentation layer.
//
// All formatters are locale-aware where the output is user-visible (date labels,
// relative time strings), and use Locale.US for numeric formatting to ensure
// decimal separators are consistent regardless of device language.
//
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts this [Timestamp] to its equivalent epoch milliseconds as a [Long].
 *
 * @param defaultValue Returned when the receiver is `null`. Defaults to `0L`.
 */
fun Timestamp?.toMillis(defaultValue: Long = 0L): Long =
    this?.toDate()?.time ?: defaultValue

// ─── Count Formatting ─────────────────────────────────────────────────────────

/**
 * Formats a raw count into a compact social-media string, using K / M / B
 * suffixes for large values.
 *
 * Decimal places are included only when significant (i.e. 1 500 → "1.5K",
 * but 2 000 → "2K"). Numeric output always uses `Locale.US` so the decimal
 * separator is always `.`, regardless of the device locale.
 *
 * Examples:
 * | Input        | Output  |
 * |-------------|---------|
 * | 500         | "500"   |
 * | 1 500       | "1.5K"  |
 * | 2 000 000   | "2M"    |
 * | 1 200 000 000 | "1.2B"|
 */
@Suppress("unused")
fun Long.toFormattedCount(): String {
    if (this < 1_000) return this.toString()

    val symbols   = DecimalFormatSymbols.getInstance(Locale.US)
    val formatter = DecimalFormat("#.#", symbols)
    val value     = this.toDouble()

    return when {
        this < 1_000_000 -> {
            val thousands = value / 1_000
            if (thousands == thousands.toLong().toDouble()) "${thousands.toLong()}K"
            else "${formatter.format(thousands)}K"
        }
        this < 1_000_000_000 -> {
            val millions = value / 1_000_000
            if (millions == millions.toLong().toDouble()) "${millions.toLong()}M"
            else "${formatter.format(millions)}M"
        }
        else -> {
            val billions = value / 1_000_000_000
            if (billions == billions.toLong().toDouble()) "${billions.toLong()}B"
            else "${formatter.format(billions)}B"
        }
    }
}

/**
 * Convenience overload of [Long.toFormattedCount] for [Int] values.
 */
@Suppress("unused")
fun Int.toFormattedCount(): String = this.toLong().toFormattedCount()

// ─── Relative Time (Time Ago) ─────────────────────────────────────────────────

/**
 * Converts a Firebase [Timestamp] into a concise relative-time string using
 * the device locale for date formatting.
 *
 * The output progresses through increasing granularity as elapsed time grows:
 *
 * | Elapsed time   | Example output      |
 * |----------------|---------------------|
 * | < 60 seconds   | "Just now"          |
 * | < 60 minutes   | "5m ago"            |
 * | < 24 hours     | "2h ago"            |
 * | < 7 days       | "3d ago"            |
 * | < 30 days      | "2w ago"            |
 * | ≥ 30 days      | "Jan 5, 2024"       |
 */
@Suppress("unused")
fun Timestamp.toTimeAgo(): String {
    val now     = System.currentTimeMillis()
    val elapsed = now - toMillis()

    val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours   = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days    = TimeUnit.MILLISECONDS.toDays(elapsed)

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours   < 24 -> "${hours}h ago"
        days    <  7 -> "${days}d ago"
        days    < 30 -> "${days / 7}w ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(toDate())
    }
}

// ─── Media Duration ───────────────────────────────────────────────────────────

/**
 * Formats a duration in milliseconds into a standard playback time string.
 *
 * Uses `%02d` zero-padding and `Locale.US` to guarantee consistent output
 * regardless of the device locale.
 *
 * | Input ms    | Output    |
 * |-------------|-----------|
 * | 5 000       | "00:05"   |
 * | 65 000      | "01:05"   |
 * | 3 600 000   | "1:00:00" |
 */
@Suppress("unused")
fun Long.toFormattedDuration(): String {
    val hours   = TimeUnit.MILLISECONDS.toHours(this)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60

    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else           String.format(Locale.US, "%02d:%02d", minutes, seconds)
}