package app.screenkeeper

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone keyboard, TV side. An accessibility service that types into whatever text field is
 * focused on the TV, driven by the remote-control server ([RemoteServer]) when a paired phone
 * sends text. It reads no screen content on its own and does nothing until a phone asks.
 *
 * Works with normal Android text fields (search boxes, logins, browsers). Apps that draw their
 * own on-screen keyboard instead of using a real text field can't be typed into this way.
 */
class KeyboardService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setWanted(this, true) // remember it was on, so it can be switched back on after an update
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        hideBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** What the phone sees about the focused field. */
    data class Field(val text: String, val hint: String, val password: Boolean, val app: String)

    sealed class Outcome {
        data class Ok(val field: Field) : Outcome()
        object NoField : Outcome()
        object Failed : Outcome()
        object Unsupported : Outcome()
    }

    fun current(): Outcome = onMain { node ->
        Outcome.Ok(describe(node))
    }

    fun setText(text: String): Outcome = onMain { node ->
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return@onMain Outcome.Failed
        // Put the cursor at the end so a later physical/IME edit continues where the phone left off.
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        node.refresh()
        val shown = if (node.isPassword) "•".repeat(text.length) else text
        showBubble(getString(R.string.keyboard_bubble, shown.ifEmpty { "…" }))
        Outcome.Ok(describe(node))
    }

    /** The keyboard's Enter / Search / Go key. Needs Android 11+ (the Shield runs Android 11). */
    fun enter(): Outcome = onMain { node ->
        if (Build.VERSION.SDK_INT < 30) return@onMain Outcome.Unsupported
        val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (ok) showBubble(getString(R.string.keyboard_bubble_sent))
        if (ok) Outcome.Ok(describe(node)) else Outcome.Failed
    }

    // ---- Bubble on the TV showing what the phone is typing ----

    private var bubble: TextView? = null
    private val hideBubbleLater = Runnable { hideBubble() }

    /** Main thread only. An accessibility overlay needs no extra permission and never takes focus. */
    private fun showBubble(msg: String) {
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val view = bubble ?: TextView(this).apply {
                textSize = 22f
                setTextColor(Palette.TEXT)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.START // keep the end, where the typing happens
                maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
                setPadding(dp(24), dp(14), dp(24), dp(14))
                background = GradientDrawable().apply {
                    setColor(0xEE1A232D.toInt())
                    cornerRadius = dpf(18f)
                    setStroke(dp(2), Palette.ACCENT)
                }
            }.also { v ->
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(36)
                }
                wm.addView(v, lp)
                bubble = v
            }
            view.text = msg
            main.removeCallbacks(hideBubbleLater)
            main.postDelayed(hideBubbleLater, 4_000)
        } catch (_: Throwable) {
            bubble = null
        }
    }

    private fun hideBubble() {
        main.removeCallbacks(hideBubbleLater)
        val v = bubble ?: return
        bubble = null
        try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (_: Throwable) {}
    }

    private fun describe(n: AccessibilityNodeInfo): Field {
        val hintText = if (Build.VERSION.SDK_INT >= 26) n.hintText else null
        // On many fields getText() returns the hint when empty; don't send the hint back as text.
        val showingHint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val txt = if (n.isPassword || showingHint) "" else n.text?.toString().orEmpty()
        val hint = (hintText ?: n.contentDescription ?: n.viewIdResourceName?.substringAfter('/'))?.toString().orEmpty()
        val pkg = n.packageName?.toString().orEmpty()
        return Field(txt.take(MAX_TEXT), hint.take(200), n.isPassword, Apps.info(this, pkg)?.label ?: pkg)
    }

    /** Accessibility node calls run on the main thread; the server waits for the answer. */
    private fun onMain(action: (AccessibilityNodeInfo) -> Outcome): Outcome {
        val result = AtomicReference<Outcome>(Outcome.Failed)
        val done = CountDownLatch(1)
        main.post {
            try {
                val node = findField()
                result.set(if (node == null) Outcome.NoField else action(node))
            } catch (_: Throwable) {
                result.set(Outcome.Failed)
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    /** The focused text field, or else the first editable field on screen (e.g. a search page before anything has focus). */
    private fun findField(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val root = rootInActiveWindow ?: return null
        var firstEditable: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            val n = queue.poll() ?: break
            visited++
            if (n.isEditable && n.isVisibleToUser) {
                if (n.isFocused) return n
                if (firstEditable == null) firstEditable = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return firstEditable
    }

    companion object {
        const val MAX_TEXT = 2000

        @Volatile
        var instance: KeyboardService? = null
            private set

        private val main = Handler(Looper.getMainLooper())

        private const val PREFS = "screenkeeper_keyboard"
        private const val WANTED = "wanted"

        /** Running right now (bound by the system). */
        fun isRunning(): Boolean = instance != null

        /** Ticked in the system's accessibility settings (it may still not be running). */
        fun isListed(ctx: Context): Boolean {
            val me = ComponentName(ctx, KeyboardService::class.java)
            return enabledList(ctx).any { ComponentName.unflattenFromString(it) == me }
        }

        fun isEnabled(ctx: Context): Boolean = isRunning() || isListed(ctx)

        private fun enabledList(ctx: Context): List<String> =
            (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && it != "null" }

        fun wanted(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WANTED, false)
        fun setWanted(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WANTED, on).apply()

        fun canSelfEnable(ctx: Context): Boolean =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /**
         * Switch the service on by rewriting the accessibility setting: take it out, then put it back,
         * which makes the system bind it again even when it was "ticked but not running" (what an app
         * update can leave behind). Needs WRITE_SECURE_SETTINGS, granted over adb. Other services stay on.
         */
        fun selfEnable(ctx: Context): Boolean {
            if (!canSelfEnable(ctx)) return false
            return try {
                val cr = ctx.contentResolver
                val me = ComponentName(ctx, KeyboardService::class.java)
                val others = enabledList(ctx).filter { ComponentName.unflattenFromString(it) != me }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                setWanted(ctx, true)
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** After boot or an update: if the keyboard was on before and isn't running now, switch it back on. */
        fun heal(ctx: Context) {
            if (isRunning() || !wanted(ctx)) return
            selfEnable(ctx)
        }

        /**
         * Open the accessibility settings. Android TV builds (the Shield among them) often have no
         * handler for the standard shortcut, which shows "no app can do this", so try the TV
         * settings app's own screens first and only start what actually exists.
         */
        fun openSettings(ctx: Context): Boolean {
            val pm = ctx.packageManager
            val tv = "com.android.tv.settings"
            val candidates = listOf(
                android.content.Intent().setClassName(tv, "$tv.accessibility.AccessibilityActivity"),
                android.content.Intent().setClassName(tv, "$tv.accessibility.AccessibilityServicesActivity"),
                android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.content.Intent().setClassName(tv, "$tv.MainSettings"),
                android.content.Intent(Settings.ACTION_SETTINGS),
            )
            for (i in candidates) {
                val info = pm.resolveActivity(i, 0)?.activityInfo ?: continue
                if (!info.exported || info.packageName == "android") continue // the "no app can do this" resolver
                if (tryStart(ctx, i)) return true
            }
            return false
        }

        /** Shell commands (no "adb shell" prefix) that switch the keyboard on and let the TV keep it on. */
        fun enableShellCommands(pkg: String, current: String): List<String> {
            val me = "$pkg/$pkg.KeyboardService"
            val others = current.trim().split(':').filter { it.isNotBlank() && it != "null" && it != me && it != "$pkg/.KeyboardService" }
            return listOf(
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                "settings put secure enabled_accessibility_services " + others.joinToString(":").ifEmpty { "\"\"" },
                "settings put secure enabled_accessibility_services " + (others + me).joinToString(":"),
                "settings put secure accessibility_enabled 1",
            )
        }

        fun adbCommands(pkg: String): String = """
            adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
            adb shell settings put secure enabled_accessibility_services $pkg/$pkg.KeyboardService
            adb shell settings put secure accessibility_enabled 1
        """.trimIndent()
    }
}
