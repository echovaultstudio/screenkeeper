package app.screenkeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that checks the front app once per second and puts the block
 * screen over it when a rule says so. Needs Usage access + "Display over other apps".
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: RuleStore
    private lateinit var tracker: UsageTracker
    private lateinit var watcher: ForegroundWatcher
    private val breaks = BreakTracker()
    private var remote: RemoteServer? = null
    private var homePkgs: Set<String> = emptySet()

    private var today: UsageTracker.Snapshot? = null
    private var todayAt = 0L
    private var lastFg: String? = null
    private var lastTick = 0L
    private var lastBlockLaunch = 0L
    private var homeRefreshedAt = 0L
    private var screenWentOff = false
    private lateinit var history: UsageHistory
    private val historyWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var historySavedAt = 0L

    /** TV standby / phone screen off ends every unlocked session (issue #1). Runs on the main thread, like tick(). */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) relock()
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = RuleStore(this)
        tracker = UsageTracker(this)
        watcher = ForegroundWatcher(this)
        history = UsageHistory(this)
        createChannels()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOffReceiver, filter)
    }

    /** Forget PIN unlocks: the locked app and the settings screens ask for the PIN again. */
    private fun relock() {
        screenWentOff = true
        GuardState.unlockedPkg = null
        Admin.lock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        try { unregisterReceiver(screenOffReceiver) } catch (_: IllegalArgumentException) {}
        historyWorker.shutdown()
        remote?.stop()
        remote = null
        super.onDestroy()
    }

    /** The remote-control server follows the setting; checked every tick so the settings screen needs no plumbing. */
    private fun syncRemote() {
        val wanted = store.remoteEnabled()
        val r = remote
        if (wanted && (r == null || !r.running)) {
            val server = r ?: RemoteServer(this, store).also { remote = it }
            server.start()
        } else if (!wanted && r != null) {
            r.stop()
            remote = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val now = System.currentTimeMillis()
        val gap = if (lastTick == 0L) 0L else now - lastTick
        val dt = gap.coerceIn(0L, 5_000L)
        lastTick = now
        syncRemote()
        if (!Perms.hasUsageAccess(this)) return
        // Long-term statistics: save the last two days every 10 minutes, off the main thread.
        if (now - historySavedAt > 10 * Time.MINUTE) {
            historySavedAt = now
            historyWorker.execute { try { history.record() } catch (t: Throwable) { Log.w(TAG, "history failed", t) } }
        }
        if (now - homeRefreshedAt > 10 * Time.MINUTE) {
            homePkgs = Apps.homePackages(this) + "com.android.systemui"
            homeRefreshedAt = now
        }

        // A PIN unlock lasts for one visit: forget it once a different app (or home) is in front, or the
        // screen went off. Checked before the null return below, because while the TV is in standby there
        // is no front app — returning first is what let a locked app come back unlocked (issue #1).
        val polled = watcher.poll(now)
        val screenOff = watcher.consumeScreenOff() || screenWentOff
        screenWentOff = false
        val unlocked = GuardState.unlockedPkg
        if (unlocked != null && !Enforcer.keepUnlock(unlocked, polled, packageName, screenOff, gap)) {
            GuardState.unlockedPkg = null
            if (screenOff || gap >= Enforcer.SLEEP_GAP_MS) Admin.lock()
        }
        val fg = polled ?: return
        if (fg == packageName || fg in homePkgs) {
            lastFg = fg
            return
        }

        val rule = store.rule(fg)
        val minute = Time.minuteOfDay(now)
        val schedule = store.pinSchedule()
        val schedulePin = store.hasPin() && Enforcer.pinRequired(null, schedule, fg, minute, Time.dayOfWeek(now))
        if (rule == null) {
            lastFg = fg
            if (schedulePin && GuardState.unlockedPkg != fg) launchBlock(fg, BlockReason.PIN_LOCKED, now)
            return
        }
        breaks.tick(fg, rule, now, dt)?.let { store.setBreakUntil(fg, it) }

        val used = usedToday(fg, now)
        lastFg = fg
        val reason = Enforcer.evaluate(
            rule = rule,
            usedTodayMs = used,
            minuteOfDay = minute,
            now = now,
            breakUntil = store.breakUntil(fg),
            overrideUntil = store.overrideUntil(fg),
            sessionUnlocked = GuardState.unlockedPkg == fg,
            schedulePin = schedulePin,
        )
        if (reason != null) launchBlock(fg, reason, now) else maybeWarn(fg, rule, used, now)
    }

    /** Today's foreground time for [pkg], refreshed every 15 s or when the front app changes. */
    private fun usedToday(pkg: String, now: Long): Long {
        val dayStart = Time.startOfDay(now)
        var snap = today
        if (snap == null || snap.start != dayStart || now - todayAt > 15_000L || pkg != lastFg) {
            snap = tracker.compute(dayStart, now)
            today = snap
            todayAt = now
        }
        val base = snap.perApp[pkg] ?: 0L
        return if (snap.foreground == pkg) base + (now - todayAt) else base
    }

    private fun launchBlock(pkg: String, reason: BlockReason, now: Long) {
        if (GuardState.blockVisible && GuardState.blockPkg == pkg) return
        if (now - lastBlockLaunch < 1_500L) return
        lastBlockLaunch = now
        try {
            startActivity(BlockActivity.intent(this, pkg, reason))
        } catch (t: Throwable) {
            Log.w(TAG, "could not show block screen", t)
        }
    }

    private fun maybeWarn(pkg: String, rule: AppRule, used: Long, now: Long) {
        if (rule.unlimited || rule.dailyLimitMin <= 0) return
        val remaining = rule.dailyLimitMin * Time.MINUTE - used
        val day = Time.startOfDay(now)
        if (remaining in 1..(5 * Time.MINUTE) && store.warnedDay(pkg) != day) {
            store.setWarnedDay(pkg, day)
            val label = Apps.info(this, pkg)?.label ?: pkg
            val n = Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.warn_title, label))
                .setContentText(getString(R.string.warn_text, formatDuration(remaining + 59_999L)))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(pkg.hashCode(), n)
        }
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val TAG = "Screenkeeper"
        private const val TICK_MS = 1_000L
        private const val NOTIF_ID = 1
        private const val CH_SERVICE = "guard"
        private const val CH_ALERTS = "alerts"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, GuardService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "could not start guard service", t)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_QUICKBOOT -> {
                KeyboardService.heal(context)
                if (Perms.allRequired(context)) GuardService.start(context)
            }
        }
    }

    companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
