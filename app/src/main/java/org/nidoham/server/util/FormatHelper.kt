package org.nidoham.server.util

import com.google.firebase.Timestamp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Firebase Timestamp কে milliseconds (Long) এ রূপান্তর করে।
 * null হলে 0L রিটার্ন করে।
 */
fun Timestamp?.toMillis(defaultValue: Long = 0L): Long =
    this?.toDate()?.time ?: defaultValue

// ============================================================
// 1. Count Formatting (Likes, Followers, Following, Comments)
// ============================================================

/**
 * Formats a number into a social-media friendly string.
 * Examples:
 * - 500 -> "500"
 * - 1500 -> "1.5K"
 * - 2000000 -> "2M"
 * - 1200000000 -> "1.2B"
 */
@Suppress("unused")
fun Long.toFormattedCount(): String {
    if (this < 1000) {
        return this.toString()
    }

    // Use Locale.US to ensure decimal point is '.' regardless of device language
    val symbols = DecimalFormatSymbols.getInstance(Locale.US)
    val formatter = DecimalFormat("#.#", symbols)
    val value = this.toDouble()

    return when {
        this < 1_000_000 -> {
            val thousands = value / 1_000
            if (thousands == thousands.toLong().toDouble()) {
                "${thousands.toLong()}K"
            } else {
                "${formatter.format(thousands)}K"
            }
        }
        this < 1_000_000_000 -> {
            val millions = value / 1_000_000
            if (millions == millions.toLong().toDouble()) {
                "${millions.toLong()}M"
            } else {
                "${formatter.format(millions)}M"
            }
        }
        else -> {
            val billions = value / 1_000_000_000
            if (billions == billions.toLong().toDouble()) {
                "${billions.toLong()}B"
            } else {
                "${formatter.format(billions)}B"
            }
        }
    }
}

@Suppress("unused")
fun Int.toFormattedCount(): String = this.toLong().toFormattedCount()

// ============================================================
// 2. Time Duration (Time Ago)
// ============================================================

/**
 * Converts a Firebase Timestamp into a "Time Ago" string.
 * Examples: "Just now", "5m ago", "2h ago", "3d ago", "Jan 5" (for older dates).
 */
@Suppress("unused")
fun Timestamp.toTimeAgo(): String {
    val now = System.currentTimeMillis()
    val time = this.toMillis()
    val diff = now - time

    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        days < 30 -> "${days / 7}w ago" // Weeks
        else -> {
            // Use Locale.getDefault() for displaying dates to the user
            val date = this.toDate()
            val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            format.format(date)
        }
    }
}

// ============================================================
// 3. Media Duration (Video/Audio Length)
// ============================================================

/**
 * Formats milliseconds into a playback duration string.
 * Examples:
 * - 5000 (5s) -> "00:05"
 * - 65000 (1m 5s) -> "01:05"
 * - 3600000 (1h) -> "1:00:00"
 */
@Suppress("unused")
fun Long.toFormattedDuration(): String {
    val hours = TimeUnit.MILLISECONDS.toHours(this)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60

    return if (hours > 0) {
        // Use Locale.US for consistent formatting
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}