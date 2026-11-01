package app.screenkeeper

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

data class AppInfo(val pkg: String, val label: String, val icon: Drawable?)

object Apps {
    /** Apps the user can open from a phone launcher or the Android TV home screen. */
    fun launchable(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, AppInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val p = ri.activityInfo.packageName
                if (p == ctx.packageName || p in out) continue
                out[p] = AppInfo(p, ri.loadLabel(pm).toString(), ri.loadIcon(pm))
            }
        }
        val home = homePackages(ctx)
        return out.values.filter { it.pkg !in home }.sortedBy { it.label.lowercase() }
    }

    fun homePackages(ctx: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return ctx.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }

    fun info(ctx: Context, pkg: String): AppInfo? = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val ai = pm.getApplicationInfo(pkg, 0)
        AppInfo(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent); true
        } catch (_: Exception) {
            false
        }
    }

    fun openAppInfo(ctx: Context, pkg: String) {
        tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    fun goHome(ctx: Context) {
        tryStart(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }
}

/** Start the first intent that resolves. Returns false if none of them could be opened. */
fun tryStart(ctx: Context, vararg intents: Intent): Boolean {
    for (i in intents) {
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i); return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

object Perms {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun canOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    fun allRequired(ctx: Context): Boolean = hasUsageAccess(ctx) && canOverlay(ctx)

    fun openUsageAccess(a: Activity): Boolean = tryStart(
        a,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${a.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openOverlay(a: Activity): Boolean = tryStart(
        a,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    @Suppress("BatteryLife")
    fun openBattery(a: Activity): Boolean = tryStart(
        a,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun adbCommands(pkg: String): String = """
        adb shell appops set $pkg GET_USAGE_STATS allow
        adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
        adb shell dumpsys deviceidle whitelist +$pkg
    """.trimIndent()
}
