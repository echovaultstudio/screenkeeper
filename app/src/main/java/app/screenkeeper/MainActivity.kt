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

        if (Build.VERSION.SDK_INT >= 33 && !isTv() && !Perms.hasUsageAccess(this)) {
            page.addView(text(getString(R.string.restricted_hint), 14f, Palette.MUTED), lp(top = dp(16)))
            page.addView(button(getString(R.string.restricted_button)) { Apps.openAppInfo(this, packageName) }, lp(WRAP, WRAP, top = dp(8)))
        }
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
        actions += button(getString(R.string.stats_open)) { startActivity(Intent(this, StatsActivity::class.java)) }
        val sched = store.pinSchedule()
        val schedLabel = getString(R.string.pin_sched_open) + ":  " +
            if (sched.enabled && store.hasPin()) getString(R.string.pin_sched_summary_on, formatClock(sched.fromMin) + "–" + formatClock(sched.toMin))
            else getString(R.string.off)
        actions += button(schedLabel) { startActivity(Intent(this, PinScheduleActivity::class.java)) }
        actions += button(getString(R.string.permissions)) { showSetup() }
        actions += button(getString(R.string.refresh)) { render() }
        page.addView(buttonRow(*actions.toTypedArray()), lp(top = dp(14)))

        addRemoteSection(page)

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

    // ---------- Phone remote ----------

    /** On a TV: let a phone control this device. On a phone: open the TV controller. Both are offered everywhere. */
    private fun addRemoteSection(page: LinearLayout) {
        page.addView(text(getString(R.string.remote_heading), 20f, bold = true), lp(top = dp(22), bottom = dp(8)))
        val tv = isTv()
        val controlOthers = button(getString(R.string.remote_control_tv)) {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        val enabled = store.remoteEnabled()
        val allow = button(getString(R.string.remote_allow) + ":  " + getString(if (enabled) R.string.on else R.string.off)) {
            store.setRemoteEnabled(!enabled)
            if (!enabled) store.newPairingCode()
            render()
        }
        if (tv) {
            page.addView(allow, lp(bottom = dp(8)))
        } else {
            page.addView(controlOthers, lp(bottom = dp(8)))
        }
        if (enabled) {
            val ip = Remote.localIp() ?: "?"
            val code = store.pairingCode()
            val card = vbox().apply {
                background = focusBg(radiusDp = 14f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(text(getString(R.string.remote_pair_code), 14f, Palette.MUTED))
            card.addView(text(code.substring(0, 3) + " " + code.substring(3), 34f, Palette.ACCENT, bold = true).apply { letterSpacing = 0.15f })
            card.addView(text(getString(R.string.remote_pair_how, ip), 14f, Palette.MUTED), lp(top = dp(6)))
            val paired = store.pairedDevices()
            if (paired.isNotEmpty()) {
                card.addView(text(getString(R.string.remote_paired, paired.values.joinToString(", ")), 14f, Palette.TEXT), lp(top = dp(10)))
            }
            page.addView(card, lp(bottom = dp(8)))
            val extra = mutableListOf<View>(button(getString(R.string.remote_new_code)) { store.newPairingCode(); render() })
            if (paired.isNotEmpty()) extra += button(getString(R.string.remote_unpair_all)) { store.clearTokens(); render() }
            extra += if (tv) controlOthers else allow
            page.addView(buttonRow(*extra.toTypedArray()), lp(bottom = dp(4)))
            if (tv) addKeyboardRow(page)
        } else {
            page.addView(if (tv) controlOthers else allow, lp(WRAP, WRAP, bottom = dp(4)))
            page.addView(text(getString(if (tv) R.string.remote_hint_tv else R.string.remote_hint_phone), 13f, Palette.MUTED))
        }
    }

    /** TV: the accessibility service that lets a paired phone type into this TV's text fields. */
    private fun addKeyboardRow(page: LinearLayout) {
        KeyboardService.heal(this)
        val running = KeyboardService.isRunning()
        val listed = KeyboardService.isListed(this)
        val state = getString(when { running -> R.string.on; listed -> R.string.keyboard_tv_stuck; else -> R.string.off })
        page.addView(button(getString(R.string.keyboard_tv_row) + ":  " + state) {
            when {
                KeyboardService.selfEnable(this) -> page.postDelayed({ render() }, 1500)
                !KeyboardService.openSettings(this) -> showKeyboardAdb()
            }
        }, lp(WRAP, WRAP, top = dp(8), bottom = dp(4)))
        if (!running) {
            page.addView(text(getString(if (listed) R.string.keyboard_tv_stuck_hint else R.string.keyboard_tv_hint), 13f, Palette.MUTED))
            page.addView(button(getString(R.string.keyboard_tv_adb)) { showKeyboardAdb() }, lp(WRAP, WRAP, top = dp(6)))
        }
    }

    private fun showKeyboardAdb() {
        info(getString(R.string.adb_title), getString(R.string.keyboard_tv_adb_body) + "\n\n" + KeyboardService.adbCommands(packageName))
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
