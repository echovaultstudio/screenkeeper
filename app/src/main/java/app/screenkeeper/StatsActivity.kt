package app.screenkeeper

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Usage statistics dashboard: one month or one year at a time, with total and daily average,
 * a per-day (or per-month) chart, time-of-day profile, a plain-language summary and the most
 * used apps. Shows this device's history, or a paired TV's when opened with [EXTRA_REMOTE].
 */
class StatsActivity : Activity() {
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private var remote: RemoteClient? = null
    private var period = UsageHistory.PERIOD_MONTH
    private var key: String? = null // null = current month/year
    private var last: JSONObject? = null

    private lateinit var monthBtn: Button
    private lateinit var yearBtn: Button
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var titleBtn: Button
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceName: String
        if (intent.getBooleanExtra(EXTRA_REMOTE, false)) {
            val saved = RuleStore(this).savedRemote() ?: run { finish(); return }
            remote = RemoteClient(saved.host, saved.token)
            deviceName = saved.name
        } else {
            deviceName = Remote.deviceName(this)
        }
        savedInstanceState?.let {
            period = it.getString(STATE_PERIOD, period)
            key = it.getString(STATE_KEY)
        }

        val (_, page) = newPage()
        page.addView(text(getString(R.string.stats_title), 26f, bold = true))
        page.addView(text(deviceName, 14f, Palette.MUTED), lp(top = dp(2), bottom = dp(12)))

        monthBtn = button(getString(R.string.stats_month)) { switchPeriod(UsageHistory.PERIOD_MONTH) }
        yearBtn = button(getString(R.string.stats_year)) { switchPeriod(UsageHistory.PERIOD_YEAR) }
        page.addView(buttonRow(monthBtn, yearBtn))

        prevBtn = button("‹") { last?.optString("prevKey")?.takeIf { it.isNotEmpty() && it != "null" }?.let { go(it) } }.apply { gravity = Gravity.CENTER; textSize = 22f }
        nextBtn = button("›") { last?.optString("nextKey")?.takeIf { it.isNotEmpty() && it != "null" }?.let { go(it) } }.apply { gravity = Gravity.CENTER; textSize = 22f }
        titleBtn = button("…") { pickPeriod() }.apply { gravity = Gravity.CENTER; textSize = 18f }
        val nav = hbox()
        nav.addView(prevBtn, LinearLayout.LayoutParams(dp(56), WRAP))
        nav.addView(titleBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        nav.addView(nextBtn, LinearLayout.LayoutParams(dp(56), WRAP))
        page.addView(nav, lp(top = dp(10)))

        content = vbox()
        page.addView(content, lp(top = dp(6)))
        titleBtn.requestFocus()
        load()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_PERIOD, period)
        out.putString(STATE_KEY, key)
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    /** Left/right on a remote, or volume-less keyboards: step to the previous/next period from anywhere. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_PAGE_UP) { prevBtn.performClick(); return true }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { nextBtn.performClick(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchPeriod(p: String) {
        if (p == period) return
        // Keep the context: from a month go to its year, from a year go to its latest month.
        val cur = last?.optString("key")
        key = when {
            cur == null -> null
            p == UsageHistory.PERIOD_YEAR -> cur.take(4)
            else -> {
                val now = UsageHistory.monthKey(System.currentTimeMillis())
                if (now.startsWith(cur)) null else "$cur-12"
            }
        }
        period = p
        load()
    }

    private fun go(k: String) { key = k; load() }

    private fun pickPeriod() {
        val j = last ?: return
        val arr = j.optJSONArray(if (period == UsageHistory.PERIOD_YEAR) "years" else "months") ?: return
        val keys = (0 until arr.length()).map { arr.getString(it) }
        if (keys.isEmpty()) return
        val labels = keys.map { if (period == UsageHistory.PERIOD_YEAR) it else monthLabel(it) }
        pickFrom(getString(if (period == UsageHistory.PERIOD_YEAR) R.string.stats_pick_year else R.string.stats_pick_month), labels) { go(keys[it]) }
    }

    private fun monthLabel(k: String): String {
        val (y, m) = UsageHistory.parseMonth(k) ?: return k
        val c = Calendar.getInstance().apply { clear(); set(y, m - 1, 1) }
        return java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).format(c.timeInMillis)
            .replaceFirstChar { it.titlecase() }
    }

    // ---------- Loading ----------

    private fun load() {
        styleSegment()
        content.removeAllViews()
        content.addView(text(getString(R.string.loading), 15f, Palette.MUTED), lp(top = dp(16)))
        val p = period
        val k = key
        val r = remote
        io(bg, {
            if (r != null) r.get("/history/$p" + (k?.let { "/$it" } ?: ""))
            else UsageHistory(this).summary(p, k, withIcons = false)
        }, { e ->
            content.removeAllViews()
            val msg = when (e.status) {
                404 -> getString(R.string.stats_old_tv)
                401 -> getString(R.string.remote_unpaired)
                0 -> getString(R.string.remote_unreachable, "TV")
                else -> e.message ?: "Error ${e.status}"
            }
            content.addView(text(msg, 15f, Palette.WARN), lp(top = dp(16)))
        }) { j ->
            last = j
            key = j.optString("key")
            render(j)
        }
    }

    private fun styleSegment() {
        for ((b, on) in listOf(monthBtn to (period == UsageHistory.PERIOD_MONTH), yearBtn to (period == UsageHistory.PERIOD_YEAR))) {
            b.setTextColor(if (on) Palette.ACCENT else Palette.MUTED)
            b.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    // ---------- Rendering ----------

    private fun render(j: JSONObject) {
        content.removeAllViews()
        val isYear = j.optString("period") == UsageHistory.PERIOD_YEAR
        titleBtn.text = j.optString("title")
        val hasPrev = j.optString("prevKey").let { it.isNotEmpty() && it != "null" }
        val hasNext = j.optString("nextKey").let { it.isNotEmpty() && it != "null" }
        prevBtn.isEnabled = hasPrev; prevBtn.alpha = if (hasPrev) 1f else 0.35f
        nextBtn.isEnabled = hasNext; nextBtn.alpha = if (hasNext) 1f else 0.35f

        val total = j.optLong("total")

        // Headline numbers
        val head = card()
        head.addView(text(getString(R.string.stats_total), 13f, Palette.MUTED))
        head.addView(text(formatDuration(total), 34f, Palette.ACCENT, bold = true))
        val facts = ArrayList<String>()
        facts += getString(R.string.stats_avg, formatDuration(j.optLong("avgPerDay")))
        if (j.optInt("activeDays") > 0) facts += resources.getQuantityString(R.plurals.stats_active_days, j.optInt("activeDays"), j.optInt("activeDays"))
        if (j.optInt("opens") > 0) facts += resources.getQuantityString(R.plurals.stats_opens, j.optInt("opens"), j.optInt("opens"))
        head.addView(text(facts.joinToString("  ·  "), 14f, Palette.TEXT), lp(top = dp(4)))
        if (!j.isNull("change")) {
            val pct = Math.round(j.optDouble("change") * 100).toInt()
            val up = pct > 0
            head.addView(
                text(getString(if (isYear) R.string.stats_change_year else R.string.stats_change_month, (if (up) "▲ +" else if (pct < 0) "▼ " else "") + pct + "%"),
                    14f, if (up) Palette.WARN else Palette.ACCENT, bold = true),
                lp(top = dp(6)),
            )
        }
        if (j.optBoolean("estimated")) head.addView(text(getString(R.string.stats_estimated_note), 13f, Palette.MUTED), lp(top = dp(6)))
        content.addView(head, lp(top = dp(12)))

        if (total <= 0) {
            content.addView(text(getString(R.string.stats_empty), 15f, Palette.MUTED), lp(top = dp(16)))
            return
        }

        // Per day / per month chart
        val barsArr = j.optJSONArray("bars")
        if (barsArr != null) {
            val bars = (0 until barsArr.length()).map { barsArr.getJSONObject(it).let { o -> o.optString("label") to o.optLong("ms") } }
            val c = card()
            c.addView(text(getString(if (isYear) R.string.stats_per_month else R.string.stats_per_day), 16f, bold = true))
            c.addView(BarChartView(this).apply {
                data = bars
                labelEvery = if (isYear) 1 else 5
                showValues = isYear && !isNarrow()
                highlight = currentIndex(j, isYear)
            }, lp(top = dp(8)))
            if (isYear) {
                // Jump straight into a month.
                val chips = bars.mapIndexedNotNull { i, (label, ms) ->
                    if (ms <= 0) null else button("$label  ${formatDuration(ms)}") {
                        period = UsageHistory.PERIOD_MONTH
                        go(String.format(java.util.Locale.US, "%s-%02d", j.optString("key"), i + 1))
                    }
                }
                if (chips.isNotEmpty()) c.addView(buttonRow(*chips.toTypedArray()), lp(top = dp(10)))
            }
            content.addView(c, lp(top = dp(12)))
        }

        // Time of day
        val hoursArr = j.optJSONArray("hours")
        if (hoursArr != null && (0 until hoursArr.length()).any { hoursArr.optLong(it) > 0 }) {
            val hours = (0 until 24).map { h -> h.toString() to hoursArr.optLong(h) }
            val peak = hours.indices.maxByOrNull { hours[it].second } ?: -2
            val c = card()
            c.addView(text(getString(R.string.stats_time_of_day), 16f, bold = true))
            c.addView(text(getString(R.string.stats_time_of_day_hint), 13f, Palette.MUTED), lp(top = dp(2)))
            c.addView(BarChartView(this).apply {
                data = hours
                labelEvery = 3
                showValues = false
                highlight = peak
            }, lp(top = dp(8)))
            content.addView(c, lp(top = dp(12)))
        }

        // Summary
        val sum = j.optJSONArray("summary")
        if (sum != null && sum.length() > 0) {
            val c = card()
            c.addView(text(getString(if (isYear) R.string.stats_year_summary else R.string.stats_summary), 16f, bold = true))
            for (i in 0 until sum.length()) c.addView(text("•  " + sum.optString(i), 14f), lp(top = dp(6)))
            content.addView(c, lp(top = dp(12)))
        }

        // Most used apps
        val apps = j.optJSONArray("apps")
        if (apps != null && apps.length() > 0) {
            content.addView(text(getString(R.string.stats_top_apps), 20f, bold = true), lp(top = dp(22), bottom = dp(8)))
            val maxMs = apps.getJSONObject(0).optLong("ms").coerceAtLeast(1)
            for (i in 0 until apps.length()) {
                val a = apps.getJSONObject(i)
                content.addView(appRow(i + 1, a, total, maxMs), lp(bottom = dp(8)))
            }
        }
    }

    /** Which bar is "now": today's day in the current month, this month in the current year. */
    private fun currentIndex(j: JSONObject, isYear: Boolean): Int {
        val now = Calendar.getInstance()
        val k = j.optString("key")
        return if (isYear) {
            if (k == now.get(Calendar.YEAR).toString()) now.get(Calendar.MONTH) else -2
        } else {
            if (k == UsageHistory.monthKey(now.timeInMillis)) now.get(Calendar.DAY_OF_MONTH) - 1 else -2
        }
    }

    private fun appRow(rank: Int, a: JSONObject, total: Long, maxMs: Long): View {
        val ms = a.optLong("ms")
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(text("$rank", 14f, Palette.MUTED).apply { minWidth = dp(22) })
        row.addView(icon(appIcon(a), 36))
        val texts = vbox()
        val top = hbox()
        top.addView(text(a.optString("label"), 16f, bold = true).apply { maxLines = 1 }, lp(0, WRAP, 1f))
        top.addView(text(formatDuration(ms), 15f, Palette.ACCENT, bold = true))
        texts.addView(top)
        // Bar relative to the most used app.
        val track = LinearLayout(this).apply {
            background = GradientDrawable().apply { setColor(Palette.BG); cornerRadius = dpf(3f) }
        }
        val fill = View(this).apply { background = GradientDrawable().apply { setColor(Palette.ACCENT_DIM); cornerRadius = dpf(3f) } }
        val frac = (ms.toFloat() / maxMs).coerceIn(0.02f, 1f)
        track.addView(fill, LinearLayout.LayoutParams(0, dp(6), frac))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(6), 1f - frac))
        texts.addView(track, lp(top = dp(6)))
        val share = (ms * 100 / total.coerceAtLeast(1)).toInt()
        val opens = a.optInt("opens")
        val line = getString(R.string.stats_app_share, share) +
            if (opens > 0) "  ·  " + resources.getQuantityString(R.plurals.stats_opens, opens, opens) else ""
        texts.addView(text(line, 12f, Palette.MUTED), lp(top = dp(4)))
        row.addView(texts, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
        return row
    }

    private fun appIcon(a: JSONObject): Drawable? =
        if (remote != null) decodeIcon(this, a.optString("icon").takeIf { !a.isNull("icon") })
        else Apps.info(this, a.optString("pkg"))?.icon

    private fun card() = vbox().apply {
        background = GradientDrawable().apply { setColor(Palette.CARD); cornerRadius = dpf(14f) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun isNarrow() = resources.displayMetrics.widthPixels / resources.displayMetrics.density < 420

    companion object {
        const val EXTRA_REMOTE = "remote"
        private const val STATE_PERIOD = "period"
        private const val STATE_KEY = "key"
    }
}
