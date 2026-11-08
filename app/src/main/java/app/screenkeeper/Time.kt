package app.screenkeeper

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Time {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE

    /** Local midnight of the day containing [t], shifted by [offsetDays]. */
    fun startOfDay(t: Long, offsetDays: Int = 0): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (offsetDays != 0) c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }

    /** java.util.Calendar.DAY_OF_WEEK of [t] in local time (1 = Sunday … 7 = Saturday). */
    fun dayOfWeek(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK)

    fun minuteOfDay(t: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun dayLabel(dayStart: Long): String = SimpleDateFormat("EEE", Locale.getDefault()).format(dayStart)

    /** Add the stretch [a, b) to [hours] (index = local hour of day), split at each full hour. */
    fun addByHour(a: Long, b: Long, hours: LongArray, cal: Calendar = Calendar.getInstance()) {
        var t = a
        while (t < b) {
            cal.timeInMillis = t
            val h = cal.get(Calendar.HOUR_OF_DAY)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            val next = minOf(b, maxOf(cal.timeInMillis, t + 1))
            hours[h] += next - t
            t = next
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

fun formatMinutes(min: Int): String = formatDuration(min * 60_000L)

fun formatClock(minOfDay: Int): String = String.format(Locale.US, "%02d:%02d", minOfDay / 60, minOfDay % 60)

fun formatCountdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
