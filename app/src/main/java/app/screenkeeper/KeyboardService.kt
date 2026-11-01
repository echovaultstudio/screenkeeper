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

        /** Is the service turned on in the system's accessibility settings? */
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, KeyboardService::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun openSettings(ctx: Context): Boolean = tryStart(
            ctx,
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )

        fun adbCommands(pkg: String): String = """
            adb shell settings put secure enabled_accessibility_services $pkg/$pkg.KeyboardService
            adb shell settings put secure accessibility_enabled 1
        """.trimIndent()
    }
}
