package app.screenkeeper

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Computes per-app foreground time from raw usage events (more accurate than the
 * aggregated UsageStats buckets, which are coarse and can double count).
 */
class UsageTracker(ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)

    class Snapshot(
        val start: Long,
        val end: Long,
        val perApp: Map<String, Long>,
        /** App in the foreground at [end], if any. */
        val foreground: String?,
    ) {
        fun total(pkgs: Set<String>): Long = perApp.entries.sumOf { if (it.key in pkgs) it.value else 0L }
    }

    /** Per-app time plus how often each app was opened and how the time spreads over the hours of the day. */
    class Detail(val perApp: Map<String, Long>, val opens: Map<String, Int>, val hours: LongArray)

    fun computeDetailed(start: Long, end: Long): Detail {
        val hours = LongArray(24)
        val opens = HashMap<String, Int>()
        val cal = java.util.Calendar.getInstance()
        val snap = compute(start, end) { pkg, a, b, startedInside ->
            if (startedInside) opens[pkg] = (opens[pkg] ?: 0) + 1
            Time.addByHour(a, b, hours, cal)
        }
        return Detail(snap.perApp, opens, hours)
    }

    /**
     * [onSession] gets every counted foreground stretch, clipped to [start, end]; `startedInside`
     * is false for a session already running at [start] (so it isn't counted as an extra open).
     */
    fun compute(
        start: Long,
        end: Long,
        onSession: ((pkg: String, a: Long, b: Long, startedInside: Boolean) -> Unit)? = null,
    ): Snapshot {
        val totals = HashMap<String, Long>()
        val resumed = HashMap<String, MutableSet<String>>() // pkg -> resumed activity classes
        val since = HashMap<String, Long>() // pkg -> session start

        fun close(pkg: String, t: Long) {
            resumed.remove(pkg)
            val s = since.remove(pkg) ?: return
            val a = maxOf(s, start)
            val b = minOf(t, end)
            if (b > a) {
                totals[pkg] = (totals[pkg] ?: 0L) + (b - a)
                onSession?.invoke(pkg, a, b, s >= start)
            }
        }

        // Look back so sessions that began before `start` are counted from `start`.
        val events = usm?.queryEvents(start - LOOKBACK, end) ?: return Snapshot(start, end, emptyMap(), null)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Only one app is treated as foreground at a time; a new app closes the others.
                    for (other in since.keys.toList()) if (other != pkg) close(other, t)
                    val set = resumed.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty() && pkg !in since) since[pkg] = t
                    set.add(e.className ?: "")
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val set = resumed[pkg] ?: continue
                    set.remove(e.className ?: "")
                    if (set.isEmpty()) close(pkg, t)
                }
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> {
                    for (p in since.keys.toList()) close(p, t)
                }
            }
        }
        val fg = since.keys.firstOrNull()
        for (p in since.keys.toList()) close(p, end)
        return Snapshot(start, end, totals, fg)
    }

    /** One snapshot per day, oldest first; the last one is today up to now. */
    fun dailyTotals(days: Int, now: Long = System.currentTimeMillis()): List<Snapshot> =
        (days - 1 downTo 0).map { i ->
            val s = Time.startOfDay(now, -i)
            val e = if (i == 0) now else Time.startOfDay(now, -i + 1)
            compute(s, e)
        }

    companion object {
        private const val LOOKBACK = 3 * Time.HOUR
        // Constants added in API 28/29; use raw values so this compiles cleanly everywhere.
        const val SCREEN_NON_INTERACTIVE = 16
        const val DEVICE_SHUTDOWN = 26
    }
}

/** Incrementally follows usage events to know which app is in front right now. Cheap to poll every second. */
class ForegroundWatcher(ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private var lastEventTs = 0L
    private var initialized = false
    private var currentClass: String? = null

    var current: String? = null
        private set

    /** Set when a screen-off or shutdown event is seen; [consumeScreenOff] reads and clears it. */
    private var screenOff = false

    fun consumeScreenOff(): Boolean = screenOff.also { screenOff = false }

    fun poll(now: Long): String? {
        val usm = usm ?: return null
        val begin = if (initialized) maxOf(lastEventTs, now - Time.MINUTE) - 2000 else now - 3 * Time.HOUR
        initialized = true
        val events = usm.queryEvents(begin, now + 1) ?: return current
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp <= lastEventTs) continue
            lastEventTs = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    current = e.packageName
                    currentClass = e.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (e.packageName == current && e.className == currentClass) {
                        current = null
                        currentClass = null
                    }
                }
                UsageTracker.SCREEN_NON_INTERACTIVE, UsageTracker.DEVICE_SHUTDOWN -> {
                    current = null
                    currentClass = null
                    screenOff = true
                }
            }
        }
        return current
    }
}
