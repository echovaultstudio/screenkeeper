package app.screenkeeper

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Run [work] on [bg]; deliver the result or the error on the UI thread (skipped if the activity is gone). */
fun <T> Activity.io(bg: ExecutorService, work: () -> T, fail: (RemoteException) -> Unit, ok: (T) -> Unit) {
    bg.execute {
        val result = try {
            Result.success(work())
        } catch (e: RemoteException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(RemoteException(0, e.message ?: e.javaClass.simpleName))
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            result.fold(ok) { fail(it as RemoteException) }
        }
    }
}

fun decodeIcon(ctx: android.content.Context, b64: String?): Drawable? {
    if (b64.isNullOrEmpty()) return null
    return try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapDrawable(ctx.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    } catch (_: Exception) {
        null
    }
}

/** Phone side: find a TV, pair with it, then show its dashboard and open its apps' rules. */
class RemoteActivity : Activity() {
    private lateinit var store: RuleStore
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private var discovery: RemoteDiscovery? = null
    private var focusPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        discovery?.stop()
        discovery = null
        super.onPause()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        discovery?.stop(); discovery = null
        val saved = store.savedRemote()
        if (saved == null) showFinder() else showDashboard(saved)
    }

    // ---------- Find and pair ----------

    private fun showFinder() {
        val (_, page) = newPage()
        page.addView(text(getString(R.string.remote_title), 26f, bold = true))
        page.addView(text(getString(R.string.remote_intro), 15f, Palette.MUTED), lp(top = dp(8), bottom = dp(16)))

        val list = vbox()
        val searching = text(getString(R.string.remote_searching), 14f, Palette.MUTED)
        list.addView(searching, lp(bottom = dp(10)))
        page.addView(list)

        val manual = button(getString(R.string.remote_enter_ip)) {
            prompt(getString(R.string.remote_enter_ip), getString(R.string.remote_ip_help), "192.168.1.50", numeric = false) { host ->
                if (host.isNotEmpty()) connect(host, host)
            }
        }
        val adb = button(getString(R.string.adb_update_open)) {
            startActivity(Intent(this, AdbUpdateActivity::class.java))
        }
        page.addView(buttonRow(manual, adb), lp(top = dp(8)))
        manual.requestFocus()

        val d = RemoteDiscovery(this) { name, host ->
            if (searching.parent != null) list.removeView(searching)
            list.addView(deviceRow(name, host) { connect(host, name) }, lp(bottom = dp(8)))
        }
        discovery = d
        d.start()
    }

    private fun deviceRow(name: String, host: String, onClick: () -> Unit): View {
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
        }
        val texts = vbox()
        texts.addView(text(name, 17f, bold = true))
        texts.addView(text(host, 13f, Palette.MUTED))
        row.addView(texts, lp(0, WRAP, 1f))
        row.addView(text("›", 24f, Palette.ACCENT))
        return row
    }

    private fun connect(host: String, fallbackName: String) {
        val wait = info(getString(R.string.remote_connecting), host)
        io(bg, { RemoteClient.probe(host) }, { e -> wait.dismiss(); showError(e) }) { probe ->
            wait.dismiss()
            if (probe == null) {
                info(getString(R.string.remote_not_found), getString(R.string.remote_not_found_d, host))
            } else {
                askCode(host, probe.optString("name").ifBlank { fallbackName })
            }
        }
    }

    private fun askCode(host: String, name: String) {
        prompt(getString(R.string.remote_pair_title, name), getString(R.string.remote_pair_help), "123456", numeric = true) { code ->
            val body = JSONObject().put("code", code).put("name", Build.MODEL)
            io(bg, { RemoteClient(host, null).post("/pair", body) }, { e ->
                if (e.status == 403) {
                    info(getString(R.string.remote_wrong_code), getString(R.string.remote_wrong_code_d))
                } else showError(e)
            }) { res ->
                store.setSavedRemote(host, res.getString("token"), res.optString("name").ifBlank { name })
                render()
            }
        }
    }

    // ---------- Dashboard ----------

    private fun showDashboard(saved: RuleStore.SavedRemote) {
        val (_, page) = newPage()
        page.addView(text(saved.name, 26f, bold = true))
        val loading = text(getString(R.string.loading), 15f, Palette.MUTED)
        page.addView(loading, lp(top = dp(12)))
        val client = RemoteClient(saved.host, saved.token)
        io(bg, { client.get("/state") }, { e -> page.removeView(loading); addError(page, saved, e) }) { state ->
            page.removeView(loading)
            buildDashboard(page, saved, client, state)
        }
    }

    private fun addError(page: LinearLayout, saved: RuleStore.SavedRemote, e: RemoteException) {
        val msg = when (e.status) {
            401 -> getString(R.string.remote_unpaired)
            0 -> getString(R.string.remote_unreachable, saved.host)
            else -> e.message ?: "Error ${e.status}"
        }
        page.addView(text(msg, 15f, Palette.WARN), lp(top = dp(12)))
        page.addView(text(getString(R.string.remote_unreachable_help), 14f, Palette.MUTED), lp(top = dp(6)))
        val retry = button(getString(R.string.refresh)) { render() }
        page.addView(buttonRow(retry, button(getString(R.string.remote_forget)) { store.clearSavedRemote(); render() }), lp(top = dp(16)))
        retry.requestFocus()
    }

    private fun showError(e: RemoteException) {
        info(getString(R.string.remote_error), e.message ?: "Error ${e.status}")
    }

    private fun buildDashboard(page: LinearLayout, saved: RuleStore.SavedRemote, client: RemoteClient, state: JSONObject) {
        val hasPin = state.optBoolean("hasPin")
        val today = state.optLong("today")
        val week = state.optLong("week")
        page.addView(text(getString(R.string.today_total, formatDuration(today)), 30f, Palette.ACCENT, bold = true), lp(top = dp(10)))
        page.addView(text(getString(R.string.week_total, formatDuration(week), formatDuration(week / 7)), 15f, Palette.MUTED), lp(top = dp(2)))
        val days = state.optJSONArray("days")
        if (days != null) {
            page.addView(BarChartView(this).apply {
                data = (0 until days.length()).map { days.getJSONObject(it).optString("label") to days.getJSONObject(it).optLong("ms") }
            }, lp(top = dp(14)))
        }

        val actions = mutableListOf<View>()
        actions += button(getString(R.string.keyboard_open)) {
            startActivity(Intent(this, RemoteKeyboardActivity::class.java))
        }
        actions += button(getString(R.string.stats_open)) {
            startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_REMOTE, true))
        }
        actions += button(getString(R.string.pin_sched_open)) {
            startActivity(Intent(this, PinScheduleActivity::class.java).putExtra(PinScheduleActivity.EXTRA_REMOTE, true))
        }
        actions += button(getString(if (hasPin) R.string.change_pin else R.string.set_pin)) {
            val setIt = { current: String? ->
                PinPad.choose(this) { pin ->
                    val body = JSONObject().put("pin", pin)
                    if (current != null) body.put("current", current)
                    io(bg, { client.post("/pin", body) }, ::showError) { render() }
                }
            }
            if (hasPin) verifyRemote(client, getString(R.string.enter_current_pin)) { setIt(it) } else setIt(null)
        }
        if (hasPin) {
            actions += button(getString(R.string.remove_pin)) {
                verifyRemote(client, getString(R.string.enter_current_pin)) { current ->
                    io(bg, { client.post("/pin/clear", JSONObject().put("current", current)) }, ::showError) { render() }
                }
            }
            actions += button(getString(R.string.lock_now)) {
                io(bg, { client.post("/lock") }, ::showError) { info(getString(R.string.lock_now), getString(R.string.remote_locked_d, saved.name)) }
            }
        }
        actions += button(getString(R.string.adb_update_open)) {
            startActivity(Intent(this, AdbUpdateActivity::class.java))
        }
        actions += button(getString(R.string.refresh)) { render() }
        actions += button(getString(R.string.remote_forget)) {
            io(bg, { try { client.post("/unpair") } catch (_: Exception) {}; true }, { }) { }
            store.clearSavedRemote()
            render()
        }
        page.addView(buttonRow(*actions.toTypedArray()), lp(top = dp(14)))

        page.addView(text(getString(R.string.apps_heading), 20f, bold = true), lp(top = dp(22)))
        page.addView(text(getString(R.string.remote_apps_hint, saved.name), 13f, Palette.MUTED), lp(top = dp(2), bottom = dp(10)))

        val apps = state.optJSONArray("apps") ?: return
        val rows = (0 until apps.length()).map { apps.getJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> { it.optLong("today") }.thenByDescending { it.optLong("week") }.thenBy { it.optString("label").lowercase() })
        var toFocus: View? = null
        for (a in rows) {
            val pkg = a.optString("pkg")
            val rule = a.optJSONObject("rule")?.let { ruleFromJson(pkg, it) }
            val iconB64 = a.optString("icon").takeIf { !a.isNull("icon") }
            val row = appRow(a.optString("label"), decodeIcon(this, iconB64), a.optLong("today"), a.optLong("week"), rule) {
                focusPkg = pkg
                startActivity(
                    Intent(this, RemoteAppActivity::class.java)
                        .putExtra(RemoteAppActivity.EXTRA_PKG, pkg)
                        .putExtra(RemoteAppActivity.EXTRA_LABEL, a.optString("label"))
                        .putExtra(RemoteAppActivity.EXTRA_ICON, iconB64)
                )
            }
            page.addView(row)
            if (pkg == focusPkg) toFocus = row
        }
        toFocus?.requestFocus()
    }

    private fun appRow(label: String, icon: Drawable?, todayMs: Long, weekMs: Long, rule: AppRule?, onClick: () -> Unit): View {
        val row = hbox().apply {
            background = focusBg(radiusDp = 14f)
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(icon(icon, 40))
        val texts = vbox()
        texts.addView(text(label, 16f, bold = true).apply { maxLines = 1 })
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

    /** Ask for the TV's current PIN and check it over the network. */
    private fun verifyRemote(client: RemoteClient, title: String, onOk: (String) -> Unit) {
        PinPad(this, title) { pin, pad ->
            io(bg, { client.post("/pin/check", JSONObject().put("pin", pin)).optString("result") }, { e -> pad.dismiss(); showError(e) }) { r ->
                when (r) {
                    PinResult.OK.name -> { pad.dismiss(); onOk(pin) }
                    PinResult.LOCKED_OUT.name -> pad.error(getString(R.string.pin_locked_out))
                    else -> pad.error(getString(R.string.pin_wrong))
                }
            }
        }
    }
}
