package org.nidoham.server.util

import com.google.firebase.Timestamp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── FormatHelper ────────────────────────────────────────────────────────────
//
// A collection of pure, stateless extension functions for formatting raw data
// values into human-readable strings.
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
 * Formats a raw count into a compact social-media string (e.g., 1.5K, 2M).
 * Uses [Locale.US] to ensure consistent decimal separators.
 */
@Suppress("unused")
fun Long.toFormattedCount(): String {
    if (this < 1_000) return this.toString()

    val symbols = DecimalFormatSymbols.getInstance(Locale.US)
    val formatter = DecimalFormat("#.#", symbols)

    return when {
        this < 1_000_000 -> "${formatter.format(this / 1_000.0)}K"
        this < 1_000_000_000 -> "${formatter.format(this / 1_000_000.0)}M"
        else -> "${formatter.format(this / 1_000_000_000.0)}B"
    }
}

@Suppress("unused")
fun Int.toFormattedCount(): String = this.toLong().toFormattedCount()

/**
 * Formats a number with grouping separators (e.g., 1,500,000).
 */
@Suppress("unused")
fun Long.toFormattedNumber(): String {
    return DecimalFormat("#,##,###", DecimalFormatSymbols.getInstance(Locale.US)).format(this)
}

@Suppress("unused")
fun Int.toFormattedNumber(): String = this.toLong().toFormattedNumber()

// ─── File Size Formatting ─────────────────────────────────────────────────────

/**
 * Formats a size in bytes into a human-readable string (e.g., 12.5 MB).
 */
@Suppress("unused")
fun Long.toFormattedFileSize(): String {
    if (this < 1024) return "$this B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val value = this.toDouble()
    val exp = (Math.log(value) / Math.log(1024.0)).toInt()
    val index = exp.coerceIn(0, units.size - 1)

    val symbols = DecimalFormatSymbols.getInstance(Locale.US)
    val formatter = DecimalFormat("#.##", symbols)

    return "${formatter.format(value / Math.pow(1024.0, index.toDouble()))} ${units[index]}"
}

@Suppress("unused")
fun Int.toFormattedFileSize(): String = this.toLong().toFormattedFileSize()

// ─── Relative Time (Time Ago) ─────────────────────────────────────────────────

/**
 * Converts a Firebase [Timestamp] into a concise relative-time string.
 * Handles past ("5m ago") and future timestamps (clock skew).
 */
@Suppress("unused")
fun Timestamp.toTimeAgo(): String {
    return this.toMillis().toTimeAgo()
}

/**
 * Converts epoch milliseconds into a concise relative-time string.
 */
@Suppress("unused")
fun Long.toTimeAgo(): String {
    val now = System.currentTimeMillis()
    val elapsed = now - this

    // Handle future dates (e.g., clock skew) by clamping to "Just now"
    if (elapsed < 0) return "Just now"

    val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "Active $minutes minutes ago"
        hours < 24 -> "Active $hours hours ago"
        days < 7 -> "Active $days days ago"
        else -> ""
    }
}

// ─── Date & Duration Formatting ───────────────────────────────────────────────

/**
 * Formats a duration in milliseconds into a playback time string (MM:SS or H:MM:SS).
 */
@Suppress("unused")
fun Long.toFormattedDuration(): String {
    val hours = TimeUnit.MILLISECONDS.toHours(this)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Formats a Firebase [Timestamp] into a date string using the provided pattern.
 *
 * @param pattern The date format pattern (e.g., "yyyy-MM-dd").
 * @param locale  The locale to use for formatting. Defaults to device locale.
 */
@Suppress("unused")
fun Timestamp.toFormattedDate(pattern: String, locale: Locale = Locale.getDefault()): String {
    return this.toMillis().toFormattedDate(pattern, locale)
}

/**
 * Formats epoch milliseconds into a date string using the provided pattern.
 */
@Suppress("unused")
fun Long.toFormattedDate(pattern: String, locale: Locale = Locale.getDefault()): String {
    return try {
        val sdf = SimpleDateFormat(pattern, locale)
        sdf.format(Date(this))
    } catch (e: Exception) {
        "Invalid Date"
    }
}

/**
 * Formats a [Timestamp] or [Long] into an ISO 8601 standard string (UTC).
 * Useful for server-side sorting or API responses.
 */
@Suppress("unused")
fun Timestamp.toISODateString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(this.toDate())
}

@Suppress("unused")
fun Long.toISODateString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(Date(this))
}