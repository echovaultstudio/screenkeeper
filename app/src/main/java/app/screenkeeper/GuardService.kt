package app.screenkeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
    private var homePkgs: Set<String> = emptySet()

    private var today: UsageTracker.Snapshot? = null
    private var todayAt = 0L
    private var lastFg: String? = null
    private var lastTick = 0L
    private var lastBlockLaunch = 0L
    private var homeRefreshedAt = 0L

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
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 5_000L)
        lastTick = now
        if (!Perms.hasUsageAccess(this)) return
        if (now - homeRefreshedAt > 10 * Time.MINUTE) {
            homePkgs = Apps.homePackages(this) + "com.android.systemui"
            homeRefreshedAt = now
        }

        val fg = watcher.poll(now) ?: return
        // A PIN unlock lasts for one visit: forget it once a different app (or home) is in front.
        if (fg != packageName && fg != GuardState.unlockedPkg) GuardState.unlockedPkg = null
        if (fg == packageName || fg in homePkgs) {
            lastFg = fg
            return
        }

        val rule = store.rule(fg)
        if (rule == null) {
            lastFg = fg
            return
        }
        breaks.tick(fg, rule, now, dt)?.let { store.setBreakUntil(fg, it) }

        val used = usedToday(fg, now)
        lastFg = fg
        val reason = Enforcer.evaluate(
            rule = rule,
            usedTodayMs = used,
            minuteOfDay = Time.minuteOfDay(now),
            now = now,
            breakUntil = store.breakUntil(fg),
            overrideUntil = store.overrideUntil(fg),
            sessionUnlocked = GuardState.unlockedPkg == fg,
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
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_QUICKBOOT ->
                if (Perms.allRequired(context)) GuardService.start(context)
        }
    }

    companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
