/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.photonmessenger.core.designsystem.component

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Messenger-style timestamp formatting: recent times stay short (clock time today, weekday within
 * the last week), older ones become dates. Used by the conversation list, chat day headers, and
 * bubble times so the whole app tells time the same way.
 */

/** Conversation-row time: "14:32" today, "Tue" within 7 days, "Jun 21" this year, else a date. */
fun formatListTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    return when {
        then.sameDayAs(today) -> timeFormat().format(Date(epochMillis))
        now - epochMillis < 7L * 24 * 60 * 60 * 1000 ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis))
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMillis))
    }
}

/** Bubble time: always the short clock time (the day is carried by the day header). */
fun formatBubbleTime(epochMillis: Long): String =
    if (epochMillis <= 0) "" else timeFormat().format(Date(epochMillis))

/** Chat day-header label: "Today", "Yesterday", "June 21", or "June 21, 2025". */
fun formatDayHeader(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        then.sameDayAs(today) -> "Today"
        then.sameDayAs(yesterday) -> "Yesterday"
        then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(epochMillis))
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
    }
}

/** True when two timestamps fall on the same calendar day (drives chat day headers). */
fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.sameDayAs(cb)
}

private fun Calendar.sameDayAs(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

private fun timeFormat(): DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
