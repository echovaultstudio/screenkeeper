package app.screenkeeper

import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Long-term usage history. Android keeps detailed usage events for only about a week, so the
 * guard service saves each day's per-app time, open counts and hour-of-day profile here.
 * One small JSON file per month: files/history/2026-11.json.
 *
 * Months from before Screenkeeper started recording are filled once from Android's coarser
 * monthly buckets (per-app totals only) and marked as estimated.
 */
class UsageHistory(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "history").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("screenkeeper_history", Context.MODE_PRIVATE)
    private val tracker = UsageTracker(ctx)

    // ---------- Recording ----------

    /** Save yesterday and today (and up to a week back on first run). Safe to call often; takes a few hundred ms. */
    fun record(now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        val today = Time.startOfDay(now)
        val yesterday = Time.startOfDay(now, -1)
        val last = prefs.getLong(LAST_DAY, 0L)
        val firstRun = last == 0L
        var day = if (firstRun) Time.startOfDay(now, -BACKFILL_DAYS) else minOf(last, yesterday)
        val skip = excluded()
        val months = HashMap<String, MonthData>()
        while (day <= today) {
            val end = if (day == today) now else Time.startOfDay(day, 1)
            val d = tracker.computeDetailed(day, end)
            val apps = d.perApp.filterKeys { it !in skip && it.isNotEmpty() }.filterValues { it > 0 }
            val rec = DayRecord(apps, d.opens.filterKeys { it in apps }, d.hours)
            val key = monthKey(day)
            val m = months.getOrPut(key) { load(key) }
            val dom = dayOfMonth(day)
            val old = m.days[dom]
            // Old days whose raw events Android already dropped must not be overwritten with less.
            if (old == null || day >= yesterday || rec.total >= old.total) {
                if (!(firstRun && rec.total == 0L && old == null && day < yesterday)) m.days[dom] = rec
            }
            for (pkg in apps.keys) if (pkg !in m.labels) m.labels[pkg] = Apps.info(ctx, pkg)?.label ?: pkg
            day = Time.startOfDay(day, 1)
        }
        months.forEach { (k, m) -> save(k, m) }
        prefs.edit().putLong(LAST_DAY, today).apply()
        if (prefs.getLong(FIRST_DAY, 0L) == 0L) prefs.edit().putLong(FIRST_DAY, firstRecordedDay(now)).apply()
        if (!prefs.getBoolean(BACKFILLED, false)) {
            try { backfillMonths(now) } catch (_: Throwable) {}
            prefs.edit().putBoolean(BACKFILLED, true).apply()
        }
    }

    private fun excluded(): Set<String> = Apps.homePackages(ctx) + ctx.packageName + "com.android.systemui" + "android"

    private fun firstRecordedDay(now: Long): Long {
        val key = monthKeys().firstOrNull() ?: return Time.startOfDay(now)
        val m = load(key)
        val dom = m.days.keys.minOrNull() ?: return Time.startOfDay(now)
        val (y, mo) = parseMonth(key)!!
        return Calendar.getInstance().apply { clear(); set(y, mo - 1, dom) }.timeInMillis
    }

    /** One-time import of Android's monthly buckets for months before detailed recording began. */
    private fun backfillMonths(now: Long) {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val firstKey = monthKeys().firstOrNull() ?: monthKey(now)
        val from = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -12) }.timeInMillis
        val skip = excluded()
        val perMonth = HashMap<String, HashMap<String, Long>>()
        for (st in usm.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, from, now) ?: emptyList()) {
            val key = monthKey(st.firstTimeStamp)
            if (key >= firstKey) continue
            val pkg = st.packageName ?: continue
            if (pkg in skip || st.totalTimeInForeground <= 0) continue
            val m = perMonth.getOrPut(key) { HashMap() }
            m[pkg] = maxOf(m[pkg] ?: 0L, st.totalTimeInForeground)
        }
        for ((key, apps) in perMonth) {
            val m = load(key)
            if (m.days.isNotEmpty()) continue
            m.estimate.clear(); m.estimate.putAll(apps)
            for (pkg in apps.keys) if (pkg !in m.labels) m.labels[pkg] = Apps.info(ctx, pkg)?.label ?: pkg
            save(key, m)
        }
    }

    // ---------- Storage ----------

    fun monthKeys(): List<String> =
        (dir.list() ?: emptyArray()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
            .filter { parseMonth(it) != null }.sorted()

    fun load(key: String): MonthData = synchronized(LOCK) {
        val f = File(dir, "$key.json")
        if (!f.exists()) return MonthData()
        return try { MonthData.fromJson(JSONObject(f.readText())) } catch (_: Exception) { MonthData() }
    }

    private fun save(key: String, m: MonthData) {
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(m.toJson().toString())
        tmp.renameTo(File(dir, "$key.json"))
    }

    /** Wipe all saved history (used by "Clear statistics"). */
    fun clear() = synchronized(LOCK) {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    // ---------- Summaries for the dashboard ----------

    /**
     * Summary JSON for [period] ("month" with key "2026-11", or "year" with key "2026"), as shown on
     * the statistics screen locally and sent to a paired phone. Brings today up to date first.
     */
    fun summary(period: String, key: String?, withIcons: Boolean, now: Long = System.currentTimeMillis()): JSONObject {
        try { record(now) } catch (_: Throwable) {}
        val keys = monthKeys()
        val years = (keys.map { it.substring(0, 4) } + yearKey(now)).distinct().sorted()
        val result = when (period) {
            PERIOD_YEAR -> {
                val y = key?.toIntOrNull() ?: Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
                val months = (1..12).associateWith { load(String.format(Locale.US, "%04d-%02d", y, it)) }
                val prev = (1..12).sumOf { load(String.format(Locale.US, "%04d-%02d", y - 1, it)).total }
                Summaries.year(y, months, prev, now)
            }
            else -> {
                val (y, m) = key?.let { parseMonth(it) } ?: parseMonth(monthKey(now))!!
                val mk = String.format(Locale.US, "%04d-%02d", y, m)
                val prevKey = shiftMonth(mk, -1)
                Summaries.month(y, m, load(mk), load(prevKey).total, now)
            }
        }
        val json = toJson(result, withIcons)
        val earliestMonth = keys.firstOrNull() ?: monthKey(now)
        json.put("prevKey", if (period == PERIOD_YEAR) (result.key.toInt() - 1).toString().takeIf { it >= years.first() }
                            else shiftMonth(result.key, -1).takeIf { it >= earliestMonth })
        json.put("nextKey", if (period == PERIOD_YEAR) (result.key.toInt() + 1).toString().takeIf { it <= yearKey(now) }
                            else shiftMonth(result.key, 1).takeIf { it <= monthKey(now) })
        json.put("months", JSONArray((keys + monthKey(now)).distinct().sorted().reversed()))
        json.put("years", JSONArray(years.reversed()))
        json.put("trackingSince", prefs.getLong(FIRST_DAY, 0L))
        json.put("device", Remote.deviceName(ctx))
        return json
    }

    private fun toJson(s: Summaries.Result, withIcons: Boolean): JSONObject {
        val iconPx = (40 * ctx.resources.displayMetrics.density).toInt().coerceIn(40, 96)
        val apps = JSONArray()
        for (a in s.apps) {
            val o = JSONObject().put("pkg", a.pkg).put("label", a.label).put("ms", a.ms).put("opens", a.opens)
            if (withIcons) o.put("icon", Remote.iconToBase64(Apps.info(ctx, a.pkg)?.icon, iconPx) ?: JSONObject.NULL)
            apps.put(o)
        }
        val bars = JSONArray()
        s.bars.forEach { (l, ms) -> bars.put(JSONObject().put("label", l).put("ms", ms)) }
        return JSONObject()
            .put("period", s.period)
            .put("key", s.key)
            .put("title", s.title)
            .put("total", s.total)
            .put("avgPerDay", s.avgPerDay)
            .put("trackedDays", s.trackedDays)
            .put("activeDays", s.activeDays)
            .put("opens", s.opens)
            .put("change", s.change ?: JSONObject.NULL)
            .put("estimated", s.estimated)
            .put("bars", bars)
            .put("hours", JSONArray(s.hours.toList()))
            .put("apps", apps)
            .put("summary", JSONArray(summaryLines(s)))
    }

    /** The plain-language "Summary" card. */
    private fun summaryLines(s: Summaries.Result): List<String> {
        if (s.total <= 0) return listOf(ctx.getString(R.string.stats_nothing))
        val out = ArrayList<String>()
        out += ctx.getString(R.string.stats_sum_total, formatDuration(s.total), s.title, formatDuration(s.avgPerDay))
        s.apps.firstOrNull()?.let { a ->
            out += ctx.getString(R.string.stats_sum_top, a.label, formatDuration(a.ms), (a.ms * 100 / s.total).toInt())
        }
        if (s.apps.size >= 3) out += ctx.getString(R.string.stats_sum_top3, s.apps[1].label, s.apps[2].label)
        s.busiest?.let { (label, ms) ->
            out += ctx.getString(if (s.period == PERIOD_YEAR) R.string.stats_sum_busiest_month else R.string.stats_sum_busiest_day, label, formatDuration(ms))
        }
        s.busiestDay?.takeIf { s.period == PERIOD_YEAR }?.let { (label, ms) ->
            out += ctx.getString(R.string.stats_sum_busiest_day, label, formatDuration(ms))
        }
        s.peakHour?.let { h -> out += ctx.getString(R.string.stats_sum_peak, formatClock(h * 60), formatClock(((h + 1) % 24) * 60)) }
        if (s.opens > 0) out += ctx.getString(R.string.stats_sum_opens, s.opens)
        s.change?.let { c ->
            val pct = Math.round(c * 100).toInt()
            out += ctx.getString(
                if (s.period == PERIOD_YEAR) R.string.stats_sum_change_year else R.string.stats_sum_change_month,
                (if (pct > 0) "+" else "") + pct + "%",
            )
        }
        if (s.estimated) out += ctx.getString(R.string.stats_sum_estimated)
        return out
    }

    companion object {
        const val PERIOD_MONTH = "month"
        const val PERIOD_YEAR = "year"
        private const val BACKFILL_DAYS = 7
        private const val LAST_DAY = "last_day"
        private const val FIRST_DAY = "first_day"
        private const val BACKFILLED = "backfilled"
        private val LOCK = Any()

        fun monthKey(t: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(t)
        fun yearKey(t: Long): String = SimpleDateFormat("yyyy", Locale.US).format(t)

        fun dayOfMonth(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH)

        fun parseMonth(key: String): Pair<Int, Int>? {
            if (key.length != 7 || key[4] != '-') return null
            val y = key.substring(0, 4).toIntOrNull() ?: return null
            val m = key.substring(5).toIntOrNull() ?: return null
            return if (m in 1..12) y to m else null
        }

        fun shiftMonth(key: String, by: Int): String {
            val (y, m) = parseMonth(key) ?: return key
            val idx = y * 12 + (m - 1) + by
            return String.format(Locale.US, "%04d-%02d", idx / 12, idx % 12 + 1)
        }
    }
}

/** One day: per-app time, per-app opens, and total time per local hour (0–23). */
class DayRecord(val apps: Map<String, Long>, val opens: Map<String, Int>, val hours: LongArray) {
    val total: Long get() = apps.values.sum()

    fun toJson(): JSONObject = JSONObject()
        .put("a", JSONObject(apps as Map<*, *>))
        .put("o", JSONObject(opens as Map<*, *>))
        .put("h", JSONArray(hours.toList()))

    companion object {
        fun fromJson(j: JSONObject): DayRecord {
            val a = HashMap<String, Long>()
            j.optJSONObject("a")?.let { o -> o.keys().forEach { a[it] = o.optLong(it) } }
            val op = HashMap<String, Int>()
            j.optJSONObject("o")?.let { o -> o.keys().forEach { op[it] = o.optInt(it) } }
            val h = LongArray(24)
            j.optJSONArray("h")?.let { arr -> for (i in 0 until minOf(24, arr.length())) h[i] = arr.optLong(i) }
            return DayRecord(a, op, h)
        }
    }
}

/** One month file: recorded days (by day of month), an optional pre-recording estimate, and app labels. */
class MonthData(
    val days: MutableMap<Int, DayRecord> = HashMap(),
    val estimate: MutableMap<String, Long> = HashMap(),
    val labels: MutableMap<String, String> = HashMap(),
) {
    /** Recorded time if any day was recorded, else the estimate. */
    val total: Long get() = if (days.isNotEmpty()) days.values.sumOf { it.total } else estimate.values.sum()
    val isEstimate: Boolean get() = days.isEmpty() && estimate.isNotEmpty()

    fun perApp(): Map<String, Long> {
        if (days.isEmpty()) return estimate
        val m = HashMap<String, Long>()
        days.values.forEach { d -> d.apps.forEach { (k, v) -> m[k] = (m[k] ?: 0L) + v } }
        return m
    }

    fun opens(): Map<String, Int> {
        val m = HashMap<String, Int>()
        days.values.forEach { d -> d.opens.forEach { (k, v) -> m[k] = (m[k] ?: 0) + v } }
        return m
    }

    fun toJson(): JSONObject {
        val d = JSONObject()
        days.forEach { (k, v) -> d.put(k.toString(), v.toJson()) }
        return JSONObject().put("v", 1).put("days", d)
            .put("est", JSONObject(estimate as Map<*, *>))
            .put("labels", JSONObject(labels as Map<*, *>))
    }

    companion object {
        fun fromJson(j: JSONObject): MonthData {
            val m = MonthData()
            j.optJSONObject("days")?.let { o ->
                o.keys().forEach { k -> k.toIntOrNull()?.let { dom -> o.optJSONObject(k)?.let { m.days[dom] = DayRecord.fromJson(it) } } }
            }
            j.optJSONObject("est")?.let { o -> o.keys().forEach { m.estimate[it] = o.optLong(it) } }
            j.optJSONObject("labels")?.let { o -> o.keys().forEach { m.labels[it] = o.optString(it) } }
            return m
        }
    }
}

/** Pure month/year statistics (unit tested). */
object Summaries {
    class AppRow(val pkg: String, val label: String, val ms: Long, val opens: Int)

    class Result(
        val period: String,
        val key: String,
        val title: String,
        val total: Long,
        val avgPerDay: Long,
        val trackedDays: Int,
        val activeDays: Int,
        val opens: Int,
        /** Fraction vs the previous period (0.12 = +12%), null if there's nothing to compare with. */
        val change: Double?,
        val estimated: Boolean,
        val bars: List<Pair<String, Long>>,
        val hours: LongArray,
        val apps: List<AppRow>,
        val busiest: Pair<String, Long>?,
        val busiestDay: Pair<String, Long>?,
        val peakHour: Int?,
    )

    private const val TOP_APPS = 20

    fun month(year: Int, month: Int, data: MonthData, prevTotal: Long, now: Long, locale: Locale = Locale.getDefault()): Result {
        val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val title = SimpleDateFormat("LLLL yyyy", locale).format(cal.timeInMillis).replaceFirstChar { it.titlecase(locale) }
        val bars = (1..daysInMonth).map { d -> d.toString() to (data.days[d]?.total ?: 0L) }
        val hours = LongArray(24)
        data.days.values.forEach { d -> for (i in 0 until 24) hours[i] += d.hours[i] }
        val total = data.total
        val tracked = if (data.isEstimate) elapsedDays(year, month, now) else data.days.size
        val busiest = data.days.maxByOrNull { it.value.total }?.takeIf { it.value.total > 0 }?.let { (dom, rec) ->
            dayLabel(year, month, dom, locale) to rec.total
        }
        val opens = data.opens()
        return Result(
            period = UsageHistory.PERIOD_MONTH,
            key = String.format(Locale.US, "%04d-%02d", year, month),
            title = title,
            total = total,
            avgPerDay = if (tracked > 0) total / tracked else 0L,
            trackedDays = tracked,
            activeDays = if (data.isEstimate) 0 else data.days.count { it.value.total > 0 },
            opens = opens.values.sum(),
            change = change(total, prevTotal),
            estimated = data.isEstimate,
            bars = bars,
            hours = hours,
            apps = topApps(data.perApp(), opens, data.labels),
            busiest = busiest,
            busiestDay = busiest,
            peakHour = peak(hours),
        )
    }

    fun year(year: Int, months: Map<Int, MonthData>, prevTotal: Long, now: Long, locale: Locale = Locale.getDefault()): Result {
        val names = DateFormatSymbols(locale).shortMonths
        val bars = (1..12).map { m -> names[m - 1] to (months[m]?.total ?: 0L) }
        val hours = LongArray(24)
        val perApp = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val labels = HashMap<String, String>()
        var tracked = 0
        var active = 0
        var estimated = false
        var bestDay: Pair<String, Long>? = null
        for ((m, data) in months) {
            data.days.values.forEach { d -> for (i in 0 until 24) hours[i] += d.hours[i] }
            data.perApp().forEach { (k, v) -> perApp[k] = (perApp[k] ?: 0L) + v }
            data.opens().forEach { (k, v) -> opens[k] = (opens[k] ?: 0) + v }
            labels.putAll(data.labels)
            if (data.isEstimate) { estimated = true; tracked += elapsedDays(year, m, now) }
            else tracked += data.days.size
            active += data.days.count { it.value.total > 0 }
            for ((dom, rec) in data.days) {
                if (rec.total > (bestDay?.second ?: 0L)) bestDay = dayLabel(year, m, dom, locale) to rec.total
            }
        }
        val total = bars.sumOf { it.second }
        val fullNames = DateFormatSymbols(locale).months
        val busiestMonth = (1..12).maxByOrNull { months[it]?.total ?: 0L }?.let { m ->
            val t = months[m]?.total ?: 0L
            if (t > 0) fullNames[m - 1].replaceFirstChar { it.titlecase(locale) } to t else null
        }
        return Result(
            period = UsageHistory.PERIOD_YEAR,
            key = year.toString(),
            title = year.toString(),
            total = total,
            avgPerDay = if (tracked > 0) total / tracked else 0L,
            trackedDays = tracked,
            activeDays = active,
            opens = opens.values.sum(),
            change = change(total, prevTotal),
            estimated = estimated,
            bars = bars,
            hours = hours,
            apps = topApps(perApp, opens, labels),
            busiest = busiestMonth,
            busiestDay = bestDay,
            peakHour = peak(hours),
        )
    }

    private fun topApps(perApp: Map<String, Long>, opens: Map<String, Int>, labels: Map<String, String>) =
        perApp.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(TOP_APPS)
            .map { AppRow(it.key, labels[it.key] ?: it.key, it.value, opens[it.key] ?: 0) }

    private fun change(total: Long, prev: Long): Double? = if (prev > 0 && total > 0) (total - prev).toDouble() / prev else null

    private fun peak(hours: LongArray): Int? {
        val i = hours.indices.maxByOrNull { hours[it] } ?: return null
        return if (hours[i] > 0) i else null
    }

    /** Days of that month up to today (all of it for past months), for averaging estimates. */
    private fun elapsedDays(year: Int, month: Int, now: Long): Int {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val n = Calendar.getInstance().apply { timeInMillis = now }
        return if (n.get(Calendar.YEAR) == year && n.get(Calendar.MONTH) == month - 1) n.get(Calendar.DAY_OF_MONTH) else max
    }

    private fun dayLabel(year: Int, month: Int, dom: Int, locale: Locale): String {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, dom) }
        return SimpleDateFormat("EEE d MMM", locale).format(c.timeInMillis)
    }
}
