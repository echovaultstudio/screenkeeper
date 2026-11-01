package app.screenkeeper

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Stats and rules for one app. */
class AppDetailActivity : Activity() {
    private lateinit var store: RuleStore
    private lateinit var tracker: UsageTracker
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var pkg: String
    private var app: AppInfo? = null
    private var days: List<UsageTracker.Snapshot>? = null
    private var focusTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        tracker = UsageTracker(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        app = Apps.info(this, pkg)
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
            page.addView(BarChartView(this).apply { data = d.map { Time.dayLabel(it.start) to (it.perApp[pkg] ?: 0L) } }, lp(top = dp(12)))
        }

        page.addView(text(getString(R.string.rules_heading), 20f, bold = true), lp(top = dp(20), bottom = dp(8)))
        page.addView(setting("limit", getString(R.string.rule_limit), if (rule.dailyLimitMin > 0) formatMinutes(rule.dailyLimitMin) else off()) { pickLimit() })
        page.addView(setting("hours", getString(R.string.rule_hours),
            if (rule.hasWindow) formatClock(rule.allowedFromMin) + " – " + formatClock(rule.allowedToMin) else getString(R.string.any_time)) { pickHours() })
        page.addView(setting("break", getString(R.string.rule_break),
            if (rule.hasBreaks) getString(R.string.break_value, formatMinutes(rule.breakEveryMin), formatMinutes(rule.breakForMin)) else off()) { pickBreak() })
        page.addView(setting("block", getString(R.string.rule_block), onOff(rule.blocked)) {
            update(rule.copy(blocked = !rule.blocked))
        })
        page.addView(setting("unlimited", getString(R.string.rule_unlimited), onOff(rule.unlimited)) {
            update(if (rule.unlimited) rule.copy(unlimited = false)
                   else rule.copy(unlimited = true, dailyLimitMin = 0, allowedFromMin = -1, allowedToMin = -1, breakEveryMin = 0, breakForMin = 0))
        })
        page.addView(setting("pin", getString(R.string.rule_pin), onOff(rule.pinLocked)) {
            if (!rule.pinLocked && !store.hasPin()) {
                PinPad.create(this, store) { Admin.unlock(); update(store.ruleOrDefault(pkg).copy(pinLocked = true)) }
            } else {
                update(rule.copy(pinLocked = !rule.pinLocked))
            }
        })

        page.addView(text(getString(R.string.actions_heading), 20f, bold = true), lp(top = dp(20), bottom = dp(8)))
        page.addView(setting("open", getString(R.string.open_app), "") { Apps.launch(this, pkg) })
        page.addView(setting("info", getString(R.string.app_info), "") { Apps.openAppInfo(this, pkg) })
        if (!rule.isDefault) page.addView(setting("reset", getString(R.string.reset_rules), "") { update(AppRule(pkg)) })

        val target = focusTag?.let { page.findViewWithTag<View>(it) } ?: page.findViewWithTag("limit")
        target?.requestFocus()
    }

    private fun setting(tag: String, title: String, value: String, onClick: () -> Unit): View {
        val b: Button = button(if (value.isEmpty()) title else "$title:  $value") {
            focusTag = tag
            onClick()
        }
        b.tag = tag
        b.layoutParams = lp(bottom = dp(8))
        return b
    }

    private fun off() = getString(R.string.off)
    private fun onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    private fun update(rule: AppRule) {
        // Any time rule cancels "unlimited", since the two contradict each other.
        val fixed = if (rule.unlimited && (rule.dailyLimitMin > 0 || rule.hasWindow || rule.hasBreaks)) rule.copy(unlimited = false) else rule
        store.save(fixed)
        render()
    }

    private fun pickLimit() {
        val mins = listOf(0, 15, 30, 45, 60, 90, 120, 180, 240, 360)
        pickFrom(getString(R.string.rule_limit), mins.map { if (it == 0) off() else formatMinutes(it) }) { i ->
            update(store.ruleOrDefault(pkg).copy(dailyLimitMin = mins[i]))
        }
    }

    private fun pickHours() {
        val slots = (0 until 48).map { it * 30 }
        val labels = slots.map { formatClock(it) }
        pickFrom(getString(R.string.rule_hours), listOf(getString(R.string.any_time), getString(R.string.set_window))) { choice ->
            if (choice == 0) {
                update(store.ruleOrDefault(pkg).copy(allowedFromMin = -1, allowedToMin = -1))
            } else {
                pickFrom(getString(R.string.allowed_from), labels) { f ->
                    pickFrom(getString(R.string.allowed_until), labels) { t ->
                        update(store.ruleOrDefault(pkg).copy(allowedFromMin = slots[f], allowedToMin = slots[t]))
                    }
                }
            }
        }
    }

    private fun pickBreak() {
        val every = listOf(0, 30, 45, 60, 90, 120)
        val length = listOf(5, 10, 15, 20, 30)
        pickFrom(getString(R.string.break_every), every.map { if (it == 0) off() else formatMinutes(it) }) { i ->
            if (every[i] == 0) {
                update(store.ruleOrDefault(pkg).copy(breakEveryMin = 0, breakForMin = 0))
            } else {
                pickFrom(getString(R.string.break_length), length.map { formatMinutes(it) }) { j ->
                    update(store.ruleOrDefault(pkg).copy(breakEveryMin = every[i], breakForMin = length[j]))
                }
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
