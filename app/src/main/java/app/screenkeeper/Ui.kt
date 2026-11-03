package app.screenkeeper

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Palette {
    const val BG = 0xFF0F1419.toInt()
    const val CARD = 0xFF1A232D.toInt()
    const val FOCUS = 0xFF24615C.toInt()
    const val ACCENT = 0xFF4FD1C5.toInt()
    const val ACCENT_DIM = 0xFF2B6F69.toInt()
    const val TEXT = 0xFFE8EDF2.toInt()
    const val MUTED = 0xFF8A97A6.toInt()
    const val WARN = 0xFFF6AD55.toInt()
    const val DANGER = 0xFFFC8181.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.isTv(): Boolean =
    getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** Background that shows a clear outline when focused, so the D-pad position is always visible on TV. */
fun Context.focusBg(normal: Int = Palette.CARD, radiusDp: Float = 12f): Drawable {
    fun shape(c: Int, stroke: Boolean) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dpf(radiusDp)
        if (stroke) setStroke(dp(2), Palette.ACCENT)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Palette.FOCUS, true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Palette.FOCUS, false))
        addState(intArrayOf(), shape(normal, false))
    }
}

fun Context.text(s: CharSequence, sizeSp: Float = 16f, color: Int = Palette.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(label: CharSequence, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    setTextColor(Palette.TEXT)
    background = focusBg()
    isFocusable = true
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    setPadding(dp(18), dp(12), dp(18), dp(12))
    setOnClickListener { onClick() }
}

fun Context.vbox(paddingDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(paddingDp)
    setPadding(p, p, p, p)
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.icon(d: Drawable?, sizeDp: Int) = ImageView(this).apply {
    setImageDrawable(d)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = top
        marginEnd = end
        bottomMargin = bottom
    }

/** A row of buttons that scrolls sideways on narrow phones. */
fun Context.buttonRow(vararg buttons: View): View {
    val row = hbox()
    buttons.forEach { row.addView(it, lp(WRAP, WRAP, end = dp(10))) }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

/** Root scroll container with TV overscan-safe margins. */
fun Activity.newPage(): Pair<ScrollView, LinearLayout> {
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Palette.BG)
        isFillViewport = true
    }
    val page = vbox()
    if (isTv()) page.setPadding(dp(56), dp(32), dp(56), dp(32)) else page.setPadding(dp(16), dp(20), dp(16), dp(24))
    scroll.addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
    setContentView(scroll)
    return scroll to page
}

fun Activity.pickFrom(title: String, items: List<String>, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setItems(items.toTypedArray()) { _, which -> onPick(which) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** One-line text input dialog. [numeric] shows a number keyboard. */
fun Activity.prompt(title: String, message: String?, hint: String, numeric: Boolean, onText: (String) -> Unit) {
    val input = android.widget.EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val wrap = vbox(20).apply { addView(input, lp()) }
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setMessage(message)
        .setView(wrap)
        .setPositiveButton(android.R.string.ok) { _, _ -> onText(input.text.toString().trim()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    input.requestFocus()
}

fun Activity.info(title: String, message: String): AlertDialog =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()

/**
 * A 4-digit PIN pad that works with touch, the D-pad, and the number keys on a TV remote.
 * [onEntered] is called with each complete PIN; call [error] to reject it or [dismiss] to accept.
 */
class PinPad(
    private val activity: Activity,
    title: String,
    private val onEntered: (String, PinPad) -> Unit,
) {
    private val entered = StringBuilder()
    private val dots: TextView
    private val msg: TextView
    private val dialog: AlertDialog

    init {
        val a = activity
        val root = a.vbox(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(a.text(title, 20f, bold = true).apply { gravity = Gravity.CENTER }, lp())
        dots = a.text("", 30f, Palette.ACCENT).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
        root.addView(dots, lp(top = a.dp(12)))
        msg = a.text("", 14f, Palette.DANGER).apply { gravity = Gravity.CENTER }
        root.addView(msg, lp(top = a.dp(4)))

        val grid = GridLayout(a).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", CANCEL, "0", DEL)
        var first: View? = null
        for (k in keys) {
            val b = a.button(k) { press(k) }.apply {
                gravity = Gravity.CENTER
                textSize = 20f
            }
            if (first == null) first = b
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = a.dp(76)
                height = a.dp(58)
                setMargins(a.dp(5), a.dp(5), a.dp(5), a.dp(5))
            })
        }
        root.addView(grid, lp(WRAP, WRAP, top = a.dp(12)))

        dialog = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog)
            .setView(root)
            .setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press((code - KeyEvent.KEYCODE_0).toString()); true }
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press((code - KeyEvent.KEYCODE_NUMPAD_0).toString()); true }
                    KeyEvent.KEYCODE_DEL -> { press(DEL); true }
                    else -> false
                }
            }
            .create()
        refresh()
        dialog.show()
        first?.requestFocus()
    }

    private fun press(k: String) {
        when (k) {
            CANCEL -> { dialog.dismiss(); return }
            DEL -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            else -> if (entered.length < 4) entered.append(k)
        }
        msg.text = ""
        refresh()
        if (entered.length == 4) {
            val pin = entered.toString()
            dots.postDelayed({ if (dialog.isShowing) onEntered(pin, this) }, 120)
        }
    }

    private fun refresh() {
        dots.text = (0 until 4).joinToString(" ") { if (it < entered.length) "●" else "○" }
    }

    fun error(message: String) {
        entered.clear()
        msg.text = message
        refresh()
    }

    fun dismiss() = dialog.dismiss()

    companion object {
        private const val DEL = "⌫"
        private const val CANCEL = "Cancel"

        /** Ask for the existing PIN; calls [onOk] when it is correct. */
        fun verify(activity: Activity, store: RuleStore, title: String, onOk: () -> Unit) {
            PinPad(activity, title) { pin, pad ->
                when (store.checkPin(pin)) {
                    PinResult.OK -> { pad.dismiss(); onOk() }
                    PinResult.WRONG -> pad.error(activity.getString(R.string.pin_wrong))
                    PinResult.LOCKED_OUT -> pad.error(activity.getString(R.string.pin_locked_out))
                }
            }
        }

        /** Choose and confirm a new PIN, then store it locally. */
        fun create(activity: Activity, store: RuleStore, onDone: () -> Unit) {
            choose(activity) { pin -> store.setPin(pin); onDone() }
        }

        /** Choose and confirm a new PIN; [onChosen] decides where it goes (local store or a TV). */
        fun choose(activity: Activity, onChosen: (String) -> Unit) {
            PinPad(activity, activity.getString(R.string.pin_choose)) { first, pad ->
                pad.dismiss()
                PinPad(activity, activity.getString(R.string.pin_confirm)) { second, pad2 ->
                    if (first == second) {
                        pad2.dismiss()
                        onChosen(first)
                    } else {
                        pad2.error(activity.getString(R.string.pin_mismatch))
                    }
                }
            }
        }
    }
}

/** Simple bar chart: one bar per day, today highlighted. */
class BarChartView(ctx: Context) : View(ctx) {
    var data: List<Pair<String, Long>> = emptyList()
        set(v) { field = v; invalidate() }

    /** Draw every Nth label only (31 days or 24 hours don't fit otherwise). */
    var labelEvery = 1
    /** Print the time above each bar. Off for dense charts. */
    var showValues = true
    /** Bar drawn in the bright colour; -1 = the last one (today), -2 = none. */
    var highlight = -1

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = ctx.dpf(12f)
        textAlign = Paint.Align.CENTER
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = ctx.dpf(11f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(150))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = data.size
        if (n == 0) return
        val max = maxOf(data.maxOf { it.second }, Time.HOUR / 2).toFloat()
        val colW = width.toFloat() / n
        val top = context.dpf(18f)
        val bottom = height - context.dpf(22f)
        val barW = minOf(colW * 0.55f, context.dpf(44f))
        data.forEachIndexed { i, (name, ms) ->
            val cx = colW * i + colW / 2
            val h = (bottom - top) * (ms / max)
            val hi = if (highlight == -1) n - 1 else highlight
            bar.color = if (i == hi) Palette.ACCENT else Palette.ACCENT_DIM
            rect.set(cx - barW / 2, bottom - maxOf(h, context.dpf(2f)), cx + barW / 2, bottom)
            c.drawRoundRect(rect, context.dpf(5f), context.dpf(5f), bar)
            if (i % labelEvery == 0) c.drawText(name, cx, height - context.dpf(5f), label)
            if (showValues && ms >= 60_000L) c.drawText(formatDuration(ms), cx, rect.top - context.dpf(5f), value)
        }
    }
}

fun describeRule(r: AppRule?): String {
    if (r == null || r.isDefault) return ""
    val parts = ArrayList<String>()
    if (r.blocked) parts += "Blocked"
    if (r.unlimited) parts += "Unlimited"
    if (r.dailyLimitMin > 0) parts += formatMinutes(r.dailyLimitMin) + "/day"
    if (r.hasWindow) parts += formatClock(r.allowedFromMin) + "–" + formatClock(r.allowedToMin)
    if (r.hasBreaks) parts += "Breaks"
    if (r.pinLocked) parts += if (r.hasPinWindow) "PIN " + formatClock(r.pinFromMin) + "–" + formatClock(r.pinToMin) else "PIN"
    return parts.joinToString(" · ")
}
