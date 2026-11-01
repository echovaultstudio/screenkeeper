package app.screenkeeper

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * The "Rules" section of an app page: one button per rule, each opening a picker.
 * Used both for apps on this device and, through the remote API, for apps on a TV.
 */
class RuleEditor(
    private val a: Activity,
    private val hasPin: () -> Boolean,
    /** A rule needs a PIN but none exists: create one, then call `done`. */
    private val createPin: (done: () -> Unit) -> Unit,
    /** A rule was changed; persist it and re-render. */
    private val onChange: (AppRule) -> Unit,
) {
    var focusTag: String? = null
    private var current = AppRule("")

    /** Appends the rules heading and rows for [rule] to [page]. */
    fun addTo(page: LinearLayout, rule: AppRule) {
        current = rule
        page.addView(a.text(a.getString(R.string.rules_heading), 20f, bold = true), lp(top = a.dp(20), bottom = a.dp(8)))
        page.addView(setting("limit", a.getString(R.string.rule_limit), if (rule.dailyLimitMin > 0) formatMinutes(rule.dailyLimitMin) else off()) { pickLimit() })
        page.addView(setting("hours", a.getString(R.string.rule_hours),
            if (rule.hasWindow) formatClock(rule.allowedFromMin) + " – " + formatClock(rule.allowedToMin) else a.getString(R.string.any_time)) { pickHours() })
        page.addView(setting("break", a.getString(R.string.rule_break),
            if (rule.hasBreaks) a.getString(R.string.break_value, formatMinutes(rule.breakEveryMin), formatMinutes(rule.breakForMin)) else off()) { pickBreak() })
        page.addView(setting("block", a.getString(R.string.rule_block), onOff(rule.blocked)) {
            update(rule.copy(blocked = !rule.blocked))
        })
        page.addView(setting("unlimited", a.getString(R.string.rule_unlimited), onOff(rule.unlimited)) {
            update(if (rule.unlimited) rule.copy(unlimited = false)
                   else rule.copy(unlimited = true, dailyLimitMin = 0, allowedFromMin = -1, allowedToMin = -1, breakEveryMin = 0, breakForMin = 0))
        })
        page.addView(setting("pin", a.getString(R.string.rule_pin), onOff(rule.pinLocked)) {
            if (!rule.pinLocked && !hasPin()) {
                createPin { update(current.copy(pinLocked = true)) }
            } else {
                update(rule.copy(pinLocked = !rule.pinLocked))
            }
        })
    }

    /** A settings-style button; [value] empty means a plain action. */
    fun setting(tag: String, title: String, value: String, onClick: () -> Unit): View {
        val b: Button = a.button(if (value.isEmpty()) title else "$title:  $value") {
            focusTag = tag
            onClick()
        }
        b.tag = tag
        b.layoutParams = lp(bottom = a.dp(8))
        return b
    }

    /** Focus the row that was last used, or [default]. */
    fun restoreFocus(page: LinearLayout, default: String = "limit") {
        val target = focusTag?.let { page.findViewWithTag<View>(it) } ?: page.findViewWithTag(default)
        target?.requestFocus()
    }

    private fun off() = a.getString(R.string.off)
    private fun onOff(v: Boolean) = a.getString(if (v) R.string.on else R.string.off)

    private fun update(rule: AppRule) = onChange(sanitizeRule(rule))

    private fun pickLimit() {
        val mins = listOf(0, 15, 30, 45, 60, 90, 120, 180, 240, 360)
        a.pickFrom(a.getString(R.string.rule_limit), mins.map { if (it == 0) off() else formatMinutes(it) }) { i ->
            update(current.copy(dailyLimitMin = mins[i]))
        }
    }

    private fun pickHours() {
        val slots = (0 until 48).map { it * 30 }
        val labels = slots.map { formatClock(it) }
        a.pickFrom(a.getString(R.string.rule_hours), listOf(a.getString(R.string.any_time), a.getString(R.string.set_window))) { choice ->
            if (choice == 0) {
                update(current.copy(allowedFromMin = -1, allowedToMin = -1))
            } else {
                a.pickFrom(a.getString(R.string.allowed_from), labels) { f ->
                    a.pickFrom(a.getString(R.string.allowed_until), labels) { t ->
                        update(current.copy(allowedFromMin = slots[f], allowedToMin = slots[t]))
                    }
                }
            }
        }
    }

    private fun pickBreak() {
        val every = listOf(0, 30, 45, 60, 90, 120)
        val length = listOf(5, 10, 15, 20, 30)
        a.pickFrom(a.getString(R.string.break_every), every.map { if (it == 0) off() else formatMinutes(it) }) { i ->
            if (every[i] == 0) {
                update(current.copy(breakEveryMin = 0, breakForMin = 0))
            } else {
                a.pickFrom(a.getString(R.string.break_length), length.map { formatMinutes(it) }) { j ->
                    update(current.copy(breakEveryMin = every[i], breakForMin = length[j]))
                }
            }
        }
    }
}
