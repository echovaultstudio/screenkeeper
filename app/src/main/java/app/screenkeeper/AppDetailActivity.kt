package app.screenkeeper

import android.app.Activity
import android.os.Bundle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Stats and rules for one app on this device. */
class AppDetailActivity : Activity() {
    private lateinit var store: RuleStore
    private lateinit var tracker: UsageTracker
    private lateinit var editor: RuleEditor
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var pkg: String
    private var app: AppInfo? = null
    private var days: List<UsageTracker.Snapshot>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        tracker = UsageTracker(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        app = Apps.info(this, pkg)
        editor = RuleEditor(
            this,
            hasPin = { store.hasPin() },
            createPin = { done -> PinPad.create(this, store) { Admin.unlock(); done() } },
            onChange = { store.save(it); render() },
        )
    }

    override fun onResume() {
        super.onResume()
        if (store.hasPin() && !Admin.isUnlocked()) { finish(); return } // back to the PIN gate
        Admin.touch()
        render()
        bg.execute {
            val d = tracker.dailyTotals(7)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                days = d
                render()
            }
        }
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val (_, page) = newPage()
        val rule = store.ruleOrDefault(pkg)

        val header = hbox()
        header.addView(icon(app?.icon, 64))
        val names = vbox()
        names.addView(text(app?.label ?: pkg, 24f, bold = true))
        names.addView(text(pkg, 13f, Palette.MUTED))
        header.addView(names, lp(0, WRAP, 1f).apply { marginStart = dp(16) })
        page.addView(header)

        val d = days
        if (d == null) {
            page.addView(text(getString(R.string.loading), 15f, Palette.MUTED), lp(top = dp(14)))
        } else {
            val todayMs = d.last().perApp[pkg] ?: 0L
            val weekMs = d.sumOf { it.perApp[pkg] ?: 0L }
            addUsageSummary(page, rule, todayMs, weekMs, d.map { Time.dayLabel(it.start) to (it.perApp[pkg] ?: 0L) })
        }

        editor.addTo(page, rule)

        page.addView(text(getString(R.string.actions_heading), 20f, bold = true), lp(top = dp(20), bottom = dp(8)))
        page.addView(editor.setting("open", getString(R.string.open_app), "") { Apps.launch(this, pkg) })
        page.addView(editor.setting("info", getString(R.string.app_info), "") { Apps.openAppInfo(this, pkg) })
        if (!rule.isDefault) page.addView(editor.setting("reset", getString(R.string.reset_rules), "") { store.save(AppRule(pkg)); render() })

        editor.restoreFocus(page)
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}

/** Today / 7-day lines, remaining time, and the bar chart shared by the local and remote app pages. */
fun Activity.addUsageSummary(page: android.widget.LinearLayout, rule: AppRule, todayMs: Long, weekMs: Long, days: List<Pair<String, Long>>) {
    page.addView(text(getString(R.string.detail_today, formatDuration(todayMs)), 22f, Palette.ACCENT, bold = true), lp(top = dp(16)))
    page.addView(text(getString(R.string.week_total, formatDuration(weekMs), formatDuration(weekMs / 7)), 15f, Palette.MUTED))
    if (!rule.unlimited && rule.dailyLimitMin > 0) {
        val left = rule.dailyLimitMin * Time.MINUTE - todayMs
        page.addView(
            text(
                if (left > 0) getString(R.string.remaining, formatDuration(left)) else getString(R.string.limit_used_up),
                15f, if (left > 0) Palette.TEXT else Palette.DANGER,
            ),
            lp(top = dp(4)),
        )
    }
    page.addView(BarChartView(this).apply { data = days }, lp(top = dp(12)))
}
