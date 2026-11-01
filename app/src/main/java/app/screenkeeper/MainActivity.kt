package app.screenkeeper

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: RuleStore
    private lateinit var tracker: UsageTracker
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private var autoPinShown = false
    private var focusPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        tracker = UsageTracker(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        if (Perms.allRequired(this)) GuardService.start(this)
        when {
            !Perms.allRequired(this) -> showSetup()
            store.hasPin() && !Admin.isUnlocked() -> showLocked()
            else -> { Admin.touch(); showDashboard() }
        }
    }

    // ---------- Setup ----------

    private fun showSetup() {
        val (_, page) = newPage()
        page.addView(text(getString(R.string.setup_title), 28f, bold = true))
        page.addView(text(getString(R.string.setup_intro), 16f, Palette.MUTED), lp(top = dp(8), bottom = dp(16)))

        page.addView(permRow(getString(R.string.perm_usage), getString(R.string.perm_usage_desc), Perms.hasUsageAccess(this)) {
            if (!Perms.openUsageAccess(this)) showAdbHelp()
        })
        page.addView(permRow(getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc), Perms.canOverlay(this)) {
            if (!Perms.openOverlay(this)) showAdbHelp()
        })
        if (Build.VERSION.SDK_INT >= 33) {
            page.addView(permRow(getString(R.string.perm_notif), getString(R.string.perm_notif_desc), Perms.hasNotifications(this)) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            })
        }
        page.addView(permRow(getString(R.string.perm_battery), getString(R.string.perm_battery_desc), Perms.ignoresBatteryOptimizations(this)) {
            if (!Perms.openBattery(this)) showAdbHelp()
        })

        page.addView(button(getString(R.string.adb_button)) { showAdbHelp() }, lp(WRAP, WRAP, top = dp(16)))
        page.post { page.findFocus() ?: page.getChildAt(2)?.requestFocus() }
    }

    private fun permRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit): View {
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { if (!granted) onGrant() }
        }
        val texts = vbox()
        texts.addView(text(title, 17f, bold = true))
        texts.addView(text(desc, 14f, Palette.MUTED), lp(top = dp(2)))
        row.addView(texts, lp(0, WRAP, 1f, end = dp(12)))
        row.addView(
            text(if (granted) "✓ Allowed" else "Tap to allow", 14f, if (granted) Palette.ACCENT else Palette.WARN, bold = true)
        )
        return row.also { it.layoutParams = lp(bottom = dp(10)) }
    }

    private fun showAdbHelp() {
        info(getString(R.string.adb_title), getString(R.string.adb_body) + "\n\n" + Perms.adbCommands(packageName))
    }

    // ---------- PIN gate ----------

    private fun showLocked() {
        val (_, page) = newPage()
        page.gravity = Gravity.CENTER_HORIZONTAL
        page.addView(text(getString(R.string.app_name), 28f, bold = true), lp(WRAP, WRAP, top = dp(40)))
        page.addView(text(getString(R.string.locked_text), 16f, Palette.MUTED), lp(WRAP, WRAP, top = dp(8)))
        val b = button(getString(R.string.enter_pin)) { askAdminPin() }.apply { gravity = Gravity.CENTER }
        page.addView(b, lp(WRAP, WRAP, top = dp(24)))
        b.requestFocus()
        if (!autoPinShown) {
            autoPinShown = true
            page.post { askAdminPin() }
        }
    }

    private fun askAdminPin() {
        PinPad.verify(this, store, getString(R.string.enter_pin)) {
            Admin.unlock()
            render()
        }
    }

    // ---------- Dashboard ----------

    private fun showDashboard() {
        val (_, page) = newPage()
        page.addView(text(getString(R.string.app_name), 26f, bold = true))
        val loading = text(getString(R.string.loading), 15f, Palette.MUTED)
        page.addView(loading, lp(top = dp(12)))

        bg.execute {
            val days = tracker.dailyTotals(7)
            val apps = Apps.launchable(this)
            val rules = store.allRules()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                page.removeView(loading)
                buildDashboard(page, days, apps, rules)
            }
        }
    }

    private fun buildDashboard(
        page: LinearLayout,
        days: List<UsageTracker.Snapshot>,
        apps: List<AppInfo>,
        rules: Map<String, AppRule>,
    ) {
        val pkgs = apps.map { it.pkg }.toSet()
        val today = days.last()
        val todayTotal = today.total(pkgs)
        val weekTotal = days.sumOf { it.total(pkgs) }
        val weekPerApp = HashMap<String, Long>()
        days.forEach { d -> d.perApp.forEach { (k, v) -> weekPerApp[k] = (weekPerApp[k] ?: 0L) + v } }

        page.addView(text(getString(R.string.today_total, formatDuration(todayTotal)), 30f, Palette.ACCENT, bold = true), lp(top = dp(10)))
        page.addView(
            text(getString(R.string.week_total, formatDuration(weekTotal), formatDuration(weekTotal / 7)), 15f, Palette.MUTED),
            lp(top = dp(2)),
        )
        page.addView(BarChartView(this).apply {
            data = days.map { Time.dayLabel(it.start) to it.total(pkgs) }
        }, lp(top = dp(14)))

        val pinButton = button(getString(if (store.hasPin()) R.string.change_pin else R.string.set_pin)) {
            if (store.hasPin()) {
                PinPad.verify(this, store, getString(R.string.enter_current_pin)) {
                    PinPad.create(this, store) { render() }
                }
            } else {
                PinPad.create(this, store) { Admin.unlock(); render() }
            }
        }
        val actions = mutableListOf<View>(pinButton)
        if (store.hasPin()) {
            actions += button(getString(R.string.remove_pin)) {
                PinPad.verify(this, store, getString(R.string.enter_current_pin)) { store.clearPin(); render() }
            }
            actions += button(getString(R.string.lock_now)) { Admin.lock(); render() }
        }
        actions += button(getString(R.string.permissions)) { showSetup() }
        actions += button(getString(R.string.refresh)) { render() }
        page.addView(buttonRow(*actions.toTypedArray()), lp(top = dp(14)))

        page.addView(text(getString(R.string.apps_heading), 20f, bold = true), lp(top = dp(22)))
        page.addView(text(getString(R.string.apps_hint), 13f, Palette.MUTED), lp(top = dp(2), bottom = dp(10)))

        val sorted = apps.sortedWith(
            compareByDescending<AppInfo> { today.perApp[it.pkg] ?: 0L }
                .thenByDescending { weekPerApp[it.pkg] ?: 0L }
                .thenBy { it.label.lowercase() }
        )
        var toFocus: View? = null
        var firstRow: View? = null
        for (app in sorted) {
            val row = appRow(app, today.perApp[app.pkg] ?: 0L, weekPerApp[app.pkg] ?: 0L, rules[app.pkg])
            page.addView(row)
            if (firstRow == null) firstRow = row
            if (app.pkg == focusPkg) toFocus = row
        }
        // On TV, start with the most-used app selected; return to the last opened app when coming back.
        (toFocus ?: if (isTv()) firstRow else null)?.requestFocus()
    }

    private fun appRow(app: AppInfo, todayMs: Long, weekMs: Long, rule: AppRule?): View {
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                focusPkg = app.pkg
                startActivity(Intent(this@MainActivity, AppDetailActivity::class.java).putExtra(AppDetailActivity.EXTRA_PKG, app.pkg))
            }
            setOnLongClickListener { Apps.launch(this@MainActivity, app.pkg); true }
        }
        row.addView(icon(app.icon, 40))
        val texts = vbox()
        texts.addView(text(app.label, 16f, bold = true).apply { maxLines = 1 })
        texts.addView(text(getString(R.string.row_stats, formatDuration(todayMs), formatDuration(weekMs)), 13f, Palette.MUTED))
        row.addView(texts, lp(0, WRAP, 1f).apply { marginStart = dp(14); marginEnd = dp(10) })
        val desc = describeRule(rule)
        if (desc.isNotEmpty()) {
            row.addView(text(desc, 13f, if (rule?.blocked == true) Palette.DANGER else Palette.WARN, bold = true).apply {
                gravity = Gravity.END
                maxWidth = dp(180)
            })
        }
        row.layoutParams = lp(bottom = dp(8))
        return row
    }
}
