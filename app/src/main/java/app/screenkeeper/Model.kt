package app.screenkeeper

/**
 * Rules for one app. Plain data, no Android dependencies, so it can be unit-tested on the JVM.
 * A rule equal to [AppRule] with only `pkg` set means "no rules" and is not stored.
 */
data class AppRule(
    val pkg: String,
    /** Daily time limit in minutes. 0 = no limit. */
    val dailyLimitMin: Int = 0,
    /** Never allow this app (unless a PIN override is active). */
    val blocked: Boolean = false,
    /** Exempt from limits, allowed hours and breaks. */
    val unlimited: Boolean = false,
    /** Ask for the PIN every time the app is opened. */
    val pinLocked: Boolean = false,
    /** Allowed window as minute-of-day. -1 = any time. The window may wrap past midnight. */
    val allowedFromMin: Int = -1,
    val allowedToMin: Int = -1,
    /** Watch breaks: after [breakEveryMin] of continuous use, block for [breakForMin]. 0 = off. */
    val breakEveryMin: Int = 0,
    val breakForMin: Int = 0,
) {
    val isDefault: Boolean get() = this == AppRule(pkg)
    val hasWindow: Boolean get() = allowedFromMin >= 0 && allowedToMin >= 0 && allowedFromMin != allowedToMin
    val hasBreaks: Boolean get() = breakEveryMin > 0 && breakForMin > 0
}

enum class BlockReason { BLOCKED, OUTSIDE_HOURS, LIMIT_REACHED, ON_BREAK, PIN_LOCKED }
