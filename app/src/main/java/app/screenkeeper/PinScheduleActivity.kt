package app.screenkeeper

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Device-wide PIN schedule: pick the hours, then tick "All apps" or the individual apps that
 * need the PIN in that window. Edits this device, or the paired TV when opened with [EXTRA_REMOTE].
 * Every change is saved straight away.
 */
class PinScheduleActivity : Activity() {
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var store: RuleStore
    private var remote: RemoteClient? = null
    private var schedule = PinSchedule()
    private var hasPin = false
    private var apps: List<Triple<String, String, Drawable?>> = emptyList() // pkg, label, icon
    /** Each app's own rule, to spot apps whose own PIN setting asks outside the schedule. */
    private var rules: Map<String, AppRule> = emptyMap()
    private var loaded = false
    private var focusTag: String? = null
    private lateinit var page: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        if (intent.getBooleanExtra(EXTRA_REMOTE, false)) {
            val saved = store.savedRemote() ?: run { finish(); return }
            remote = RemoteClient(saved.host, saved.token)
        }
        page = newPage().second
        load()
    }

    override fun onResume() {
        super.onResume()
        // Local settings sit behind the PIN gate, like the rest of the app's settings.
        if (remote == null && store.hasPin() && !Admin.isUnlocked()) { finish(); return }
        if (remote == null) Admin.touch()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    // ---------- Loading and saving ----------

    private fun load() {
        page.removeAllViews()
        page.addView(text(getString(R.string.loading), 15f, Palette.MUTED))
        val r = remote
        io(bg, {
            if (r != null) {
                val s = r.get("/pinschedule")
                val state = r.get("/state")
                val arr = state.optJSONArray("apps")
                val objs = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
                val list = objs.map { a ->
                    Triple(a.optString("pkg"), a.optString("label"), decodeIcon(this, a.optString("icon").takeIf { !a.isNull("icon") }))
                }
                val rs = objs.mapNotNull { a -> a.optJSONObject("rule")?.let { a.optString("pkg") to ruleFromJson(a.optString("pkg"), it) } }.toMap()
                Loaded(pinScheduleFromJson(s.optJSONObject("schedule") ?: JSONObject()), s.optBoolean("hasPin"), list, rs)
            } else {
                val home = Apps.homePackages(this)
                val list = Apps.launchable(this).filter { it.pkg != packageName && it.pkg !in home }.map { Triple(it.pkg, it.label, it.icon) }
                Loaded(store.pinSchedule(), store.hasPin(), list, store.allRules())
            }
        }, ::showError) { d ->
            schedule = d.schedule
            hasPin = d.hasPin
            apps = d.apps.sortedBy { it.second.lowercase() }
            rules = d.rules
            loaded = true
            render()
        }
    }

    /** Save [s] here or on the TV, then redraw. */
    private fun save(s: PinSchedule) {
        val r = remote
        if (r == null) {
            store.setPinSchedule(s)
            schedule = s
            render()
            return
        }
        io(bg, { r.put("/pinschedule", pinScheduleToJson(s)) }, ::showError) { res ->
            schedule = res.optJSONObject("schedule")?.let { pinScheduleFromJson(it) } ?: s
            render()
        }
    }

    private class Loaded(
        val schedule: PinSchedule,
        val hasPin: Boolean,
        val apps: List<Triple<String, String, Drawable?>>,
        val rules: Map<String, AppRule>,
    )

    /** Apps whose own "Require PIN to open" also asks, so they can ask outside the schedule's hours. */
    private fun ownPinApps(): List<Triple<String, String, Drawable?>> =
        apps.filter { rules[it.first]?.pinLocked == true }

    /**
     * "Let the schedule decide": turn off these apps' own PIN setting and, when the schedule
     * covers only ticked apps, tick them, so the schedule's hours alone decide when they ask.
     */
    private fun handOverToSchedule(list: List<Triple<String, String, Drawable?>>) {
        val changed = list.mapNotNull { rules[it.first] }.map { sanitizeRule(it.copy(pinLocked = false, pinFromMin = -1, pinToMin = -1)) }
        val newSchedule = if (schedule.allApps) schedule else schedule.copy(pkgs = schedule.pkgs + list.map { it.first })
        val r = remote
        if (r == null) {
            changed.forEach { store.save(it) }
            rules = store.allRules()
            save(newSchedule)
            return
        }
        io(bg, {
            for (rule in changed) r.put("/rule/${rule.pkg}", ruleToJson(rule))
            r.put("/pinschedule", pinScheduleToJson(newSchedule))
        }, ::showError) { res ->
            rules = rules.toMutableMap().apply { changed.forEach { c -> if (c.isDefault) remove(c.pkg) else put(c.pkg, c) } }
            schedule = res.optJSONObject("schedule")?.let { pinScheduleFromJson(it) } ?: newSchedule
            render()
        }
    }

    private fun ownPinText(rule: AppRule): String =
        if (rule.hasPinWindow) getString(R.string.pin_sched_own_window, formatClock(rule.pinFromMin) + "–" + formatClock(rule.pinToMin))
        else getString(R.string.pin_sched_own_always)

    /** Turning the schedule on needs a PIN; make one first if there is none. */
    private fun withPin(then: () -> Unit) {
        if (hasPin) { then(); return }
        PinPad.choose(this) { pin ->
            val r = remote
            if (r == null) {
                store.setPin(pin); Admin.unlock(); hasPin = true; then()
            } else {
                io(bg, { r.post("/pin", JSONObject().put("pin", pin)) }, ::showError) { hasPin = true; then() }
            }
        }
    }

    private fun showError(e: RemoteException) {
        val msg = when (e.status) {
            404 -> getString(R.string.pin_sched_old_tv)
            401 -> getString(R.string.remote_unpaired)
            0 -> getString(R.string.remote_unreachable, store.savedRemote()?.host ?: "")
            else -> e.message ?: "Error ${e.status}"
        }
        if (!loaded) {
            page.removeAllViews()
            page.addView(text(msg, 15f, Palette.WARN))
            page.addView(button(getString(R.string.refresh)) { load() }, lp(WRAP, WRAP, top = dp(12)))
        } else {
            info(getString(R.string.remote_error), msg)
        }
    }

    // ---------- Screen ----------

    private fun render() {
        page.removeAllViews()
        val s = schedule
        page.addView(text(getString(R.string.pin_sched_title), 26f, bold = true))
        remote?.let { page.addView(text(store.savedRemote()?.name ?: "", 14f, Palette.MUTED), lp(top = dp(2))) }
        page.addView(text(getString(R.string.pin_sched_intro), 14f, Palette.MUTED), lp(top = dp(6), bottom = dp(14)))

        page.addView(tickRow("enabled", getString(R.string.pin_sched_enabled), null, s.enabled, bold = true) {
            if (s.enabled) save(s.copy(enabled = false)) else withPin { save(schedule.copy(enabled = true)) }
        }, lp(bottom = dp(8)))

        val slots = (0 until 48).map { it * 30 }
        val labels = slots.map { formatClock(it) }
        val from = settingButton("from", getString(R.string.pin_from) + ":  " + formatClock(s.fromMin)) {
            pickFrom(getString(R.string.pin_from), labels) { save(schedule.copy(fromMin = slots[it])) }
        }
        val until = settingButton("until", getString(R.string.pin_until) + ":  " + formatClock(s.toMin)) {
            pickFrom(getString(R.string.pin_until), labels) { save(schedule.copy(toMin = slots[it])) }
        }
        page.addView(buttonRow(from, until))
        page.addView(text(windowText(s), 13f, if (s.enabled) Palette.ACCENT else Palette.MUTED), lp(top = dp(6)))

        // Apps with their own PIN setting ask whenever that setting says so, even outside the schedule.
        val own = ownPinApps()
        if (own.isNotEmpty()) {
            val card = vbox().apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Palette.CARD); cornerRadius = dpf(14f); setStroke(dp(2), Palette.WARN)
                }
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(text(resources.getQuantityString(R.plurals.pin_sched_conflict_title, own.size, own.size), 16f, Palette.WARN, bold = true))
            card.addView(text(getString(R.string.pin_sched_conflict_body), 14f), lp(top = dp(4)))
            for ((pkg, label, _) in own.take(12)) {
                card.addView(text("•  $label — " + ownPinText(rules.getValue(pkg)), 14f, Palette.MUTED), lp(top = dp(4)))
            }
            if (own.size > 12) card.addView(text("•  …", 14f, Palette.MUTED))
            card.addView(settingButton("handover", getString(R.string.pin_sched_conflict_fix)) { handOverToSchedule(own) }, lp(top = dp(10)))
            page.addView(card, lp(top = dp(14)))
        }

        page.addView(text(getString(R.string.pin_sched_apps), 20f, bold = true), lp(top = dp(22), bottom = dp(8)))
        page.addView(tickRow("all", getString(R.string.pin_sched_all), getString(R.string.pin_sched_all_d), s.allApps, bold = true) {
            save(schedule.copy(allApps = !schedule.allApps))
        }, lp(bottom = dp(8)))

        if (!s.allApps) {
            val all = settingButton("tickall", getString(R.string.pin_sched_tick_all)) { save(schedule.copy(pkgs = apps.map { it.first }.toSet())) }
            val none = settingButton("ticknone", getString(R.string.pin_sched_tick_none)) { save(schedule.copy(pkgs = emptySet())) }
            page.addView(buttonRow(all, none), lp(bottom = dp(6)))
            page.addView(text(resources.getQuantityString(R.plurals.pin_sched_count, s.pkgs.size, s.pkgs.size), 13f, Palette.MUTED), lp(bottom = dp(8)))
            for ((pkg, label, icon) in apps) {
                val ownRule = rules[pkg]?.takeIf { it.pinLocked }
                page.addView(tickRow("app:$pkg", label, ownRule?.let { ownPinText(it) }, pkg in s.pkgs, icon = icon) {
                    val cur = schedule.pkgs
                    save(schedule.copy(pkgs = if (pkg in cur) cur - pkg else cur + pkg))
                }, lp(bottom = dp(6)))
            }
        }

        val target = focusTag?.let { page.findViewWithTag<View>(it) } ?: page.findViewWithTag("enabled")
        target?.requestFocus()
    }

    private fun windowText(s: PinSchedule): String {
        val window = formatClock(s.fromMin) + " – " + formatClock(s.toMin)
        val who = if (s.allApps) getString(R.string.pin_sched_who_all)
                  else resources.getQuantityString(R.plurals.pin_sched_who_some, s.pkgs.size, s.pkgs.size)
        return if (s.enabled) getString(R.string.pin_sched_active, who, window) else getString(R.string.pin_sched_inactive, window)
    }

    private fun settingButton(tag: String, label: String, onClick: () -> Unit) = button(label) {
        focusTag = tag
        onClick()
    }.apply { this.tag = tag }

    /** A full-width focusable row with a tick box; the whole row toggles (works with the D-pad). */
    private fun tickRow(tag: String, title: String, sub: String?, checked: Boolean, bold: Boolean = false, icon: Drawable? = null, onToggle: () -> Unit): View {
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            isClickable = true
            this.tag = tag
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { focusTag = tag; onToggle() }
        }
        val box = CheckBox(this).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.ACCENT, Palette.MUTED))
        }
        row.addView(box)
        if (icon != null) row.addView(icon(icon, 32).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        val texts = vbox()
        texts.addView(text(title, 16f, bold = bold).apply { maxLines = 1 })
        if (sub != null) texts.addView(text(sub, 13f, Palette.MUTED))
        row.addView(texts, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
        return row
    }

    companion object {
        const val EXTRA_REMOTE = "remote"
    }
}
