package com.nidoham.bondhu.presentation.component.chat

import com.nidoham.server.domain.message.Message
import org.nidoham.server.data.util.DEFAULT_RECENT_EMOJIS
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── Recent-emoji state ───────────────────────────────────────────────────────
//
// Module-level list that survives recomposition but is reset when the process
// is killed.  In a production build this should be persisted via a ViewModel /
// DataStore; kept here to avoid changing the existing architecture.

/** Mutable in-memory list of the user's most recently used emojis (max 24). */
internal val recentEmojis: MutableList<String> = DEFAULT_RECENT_EMOJIS.toMutableList()

// ─── Date / Time formatting ───────────────────────────────────────────────────

/**
 * Formats a [Date] into a short wall-clock string, e.g. `"3:45 PM"`.
 *
 * Uses the device's default locale so that AM/PM vs 24-hour display matches
 * the user's system preference.
 */
internal fun Date.toTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(this)

/**
 * Converts a raw last-seen value (milliseconds-since-epoch stored as a [String])
 * into a human-readable label suitable for display in the chat top bar.
 *
 * Return values:
 *  - `""` — if [lastSeenMillisStr] is blank or cannot be parsed (caller should
 *            hide the label row entirely in this case).
 *  - `"last seen recently"` — seen within the last minute.
 *  - `"last seen X minutes ago"` — seen within the last hour.
 *  - `"last seen today at HH:mm"` — seen earlier today.
 *  - `"last seen yesterday at HH:mm"` — seen yesterday.
 *  - `"last seen MMM d"` — seen more than two days ago.
 *
 * @param lastSeenMillisStr Epoch-millisecond timestamp encoded as a [String].
 */
internal fun formatLastSeen(lastSeenMillisStr: String): String {
    val millis = lastSeenMillisStr.toLongOrNull() ?: return ""
    val diff   = System.currentTimeMillis() - millis
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFmt = SimpleDateFormat("MMM d",  Locale.getDefault())

    return when {
        diff < TimeUnit.MINUTES.toMillis(1)  -> "last seen recently"
        diff < TimeUnit.HOURS.toMillis(1)    -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "last seen $mins minute${if (mins == 1L) "" else "s"} ago"
        }
        diff < TimeUnit.DAYS.toMillis(1)     -> "last seen today at ${timeFmt.format(Date(millis))}"
        diff < TimeUnit.DAYS.toMillis(2)     -> "last seen yesterday at ${timeFmt.format(Date(millis))}"
        else                                  -> "last seen ${dateFmt.format(Date(millis))}"
    }
}

// ─── Message grouping ─────────────────────────────────────────────────────────

/**
 * Groups a flat list of [Message] objects into an ordered map keyed by a
 * human-readable date label (`"TODAY"`, `"YESTERDAY"`, or `"MMM D"`).
 *
 * The map preserves insertion order so that iterating it yields groups from
 * oldest to newest, matching the top-to-bottom order of the message list.
 */
internal fun List<Message>.groupByDate(): Map<String, List<Message>> {
    val today     = Calendar.getInstance()
    val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
    val fmt       = SimpleDateFormat("MMM d", Locale.getDefault())
    return groupBy { msg ->
        val date = msg.timestamp?.toDate()
        val cal  = Calendar.getInstance().also { if (date != null) it.time = date }
        when {
            cal.isSameDay(today)     -> "TODAY"
            cal.isSameDay(yesterday) -> "YESTERDAY"
            else -> date?.let { fmt.format(it) }?.uppercase() ?: "UNKNOWN"
        }
    }
}

/**
 * Returns `true` when `this` [Calendar] falls on the same calendar day as
 * [other], comparing both year and day-of-year fields.
 */
internal fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR)        == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)