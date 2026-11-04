package app.screenkeeper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/** Full-screen notice shown on top of an app that is blocked right now. */
class BlockActivity : Activity() {
    private lateinit var store: RuleStore
    private val handler = Handler(Looper.getMainLooper())
    private var pkg = ""
    private var reason = BlockReason.BLOCKED
    private var countdown: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (reason != BlockReason.ON_BREAK) return
            val left = store.breakUntil(pkg) - System.currentTimeMillis()
            if (left <= 0) {
                // Break is over: go straight back to the app.
                Apps.launch(this@BlockActivity, pkg)
                finish()
                return
            }
            countdown?.text = formatCountdown(left)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        readIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        GuardState.blockVisible = true
        GuardState.blockPkg = pkg
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        GuardState.blockVisible = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun readIntent(i: Intent) {
        pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        reason = try {
            BlockReason.valueOf(i.getStringExtra(EXTRA_REASON) ?: "")
        } catch (_: IllegalArgumentException) {
            BlockReason.BLOCKED
        }
    }

    private fun goHome() {
        Apps.goHome(this)
        finish()
    }

    private fun render() {
        val (_, page) = newPage()
        page.gravity = Gravity.CENTER_HORIZONTAL
        val app = Apps.info(this, pkg)
        val rule = store.ruleOrDefault(pkg)

        page.addView(icon(app?.icon, 96), lp(dp(96), dp(96), top = dp(if (isTv()) 24 else 64)))
        page.addView(text(app?.label ?: pkg, 26f, bold = true).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, top = dp(16)))

        val (headline, detail) = when (reason) {
            BlockReason.BLOCKED -> getString(R.string.block_blocked) to getString(R.string.block_blocked_d)
            BlockReason.OUTSIDE_HOURS -> getString(R.string.block_hours) to
                getString(R.string.block_hours_d, formatClock(rule.allowedFromMin), formatClock(rule.allowedToMin))
            BlockReason.LIMIT_REACHED -> getString(R.string.block_limit) to
                getString(R.string.block_limit_d, formatMinutes(rule.dailyLimitMin))
            BlockReason.ON_BREAK -> getString(R.string.block_break) to getString(R.string.block_break_d)
            BlockReason.PIN_LOCKED -> getString(R.string.block_pin) to pinDetail(rule)
        }
        page.addView(text(headline, 22f, Palette.ACCENT, bold = true).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, top = dp(20)))
        page.addView(text(detail, 16f, Palette.MUTED).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, top = dp(6)))

        countdown = null
        if (reason == BlockReason.ON_BREAK) {
            countdown = text("", 44f, Palette.TEXT, bold = true).apply { gravity = Gravity.CENTER }
            page.addView(countdown, lp(WRAP, WRAP, top = dp(16)))
        }

        val home = button(getString(R.string.go_home)) { goHome() }.apply { gravity = Gravity.CENTER }
        page.addView(home, lp(dp(300), WRAP, top = dp(28)))
        if (store.hasPin()) {
            val label = if (reason == BlockReason.PIN_LOCKED) R.string.enter_pin else R.string.pin_extra_time
            page.addView(button(getString(label)) { askPin() }.apply { gravity = Gravity.CENTER }, lp(dp(300), WRAP, top = dp(10)))
        }
        home.requestFocus()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Says which setting asks for the PIN, so a schedule that "isn't working" is easy to explain. */
    private fun pinDetail(rule: AppRule): String {
        val minute = Time.minuteOfDay(System.currentTimeMillis())
        if (rule.pinLocked && !rule.hasPinWindow) return getString(R.string.block_pin_own_always)
        if (rule.pinLocked && Enforcer.inWindow(rule.pinFromMin, rule.pinToMin, minute)) {
            return getString(R.string.block_pin_own_until, formatClock(rule.pinToMin))
        }
        val s = store.pinSchedule()
        if (s.enabled && s.covers(pkg) && Enforcer.inWindow(s.fromMin, s.toMin, minute)) {
            return if (s.fromMin == s.toMin) getString(R.string.block_pin_d) else getString(R.string.block_pin_until, formatClock(s.toMin))
        }
        return getString(R.string.block_pin_d)
    }

    private fun askPin() {
        PinPad.verify(this, store, getString(R.string.enter_pin)) {
            if (reason == BlockReason.PIN_LOCKED) {
                GuardState.unlockedPkg = pkg
            } else {
                store.setOverride(pkg, System.currentTimeMillis() + EXTRA_TIME_MS)
            }
            GuardState.blockVisible = false
            Apps.launch(this, pkg)
            finish()
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_TIME_MS = 15 * 60_000L

        fun intent(ctx: Context, pkg: String, reason: BlockReason): Intent =
            Intent(ctx, BlockActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_REASON, reason.name)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
    }
}
