package app.screenkeeper

/** Pure decision logic: given the rule and current state, should the app be blocked, and why? */
object Enforcer {

    fun inAllowedWindow(rule: AppRule, minuteOfDay: Int): Boolean {
        if (!rule.hasWindow) return true
        val from = rule.allowedFromMin
        val to = rule.allowedToMin
        return if (from < to) minuteOfDay in from until to
        else minuteOfDay >= from || minuteOfDay < to // wraps past midnight, e.g. 20:00–02:00
    }

    fun evaluate(
        rule: AppRule?,
        usedTodayMs: Long,
        minuteOfDay: Int,
        now: Long,
        breakUntil: Long,
        overrideUntil: Long,
        sessionUnlocked: Boolean,
    ): BlockReason? {
        if (rule == null || rule.isDefault) return null
        if (overrideUntil > now) return null // PIN-granted extra time
        if (rule.blocked) return BlockReason.BLOCKED
        if (!rule.unlimited) {
            if (!inAllowedWindow(rule, minuteOfDay)) return BlockReason.OUTSIDE_HOURS
            if (rule.dailyLimitMin > 0 && usedTodayMs >= rule.dailyLimitMin * 60_000L) return BlockReason.LIMIT_REACHED
            if (rule.hasBreaks && breakUntil > now) return BlockReason.ON_BREAK
        }
        if (rule.pinLocked && !sessionUnlocked) return BlockReason.PIN_LOCKED
        return null
    }
}

/**
 * Tracks continuous use per app for watch breaks. A streak resets once the app has been
 * out of the foreground for at least the break length.
 */
class BreakTracker {
    private val streak = HashMap<String, Long>()
    private val lastSeen = HashMap<String, Long>()

    /** Call once per tick while [pkg] is in the foreground. Returns a break end time if a break starts now. */
    fun tick(pkg: String, rule: AppRule?, now: Long, dtMs: Long): Long? {
        if (rule == null || rule.unlimited || !rule.hasBreaks) return null
        val breakMs = rule.breakForMin * 60_000L
        val last = lastSeen[pkg]
        if (last != null && now - last >= breakMs) streak[pkg] = 0L
        lastSeen[pkg] = now
        val s = (streak[pkg] ?: 0L) + dtMs
        if (s >= rule.breakEveryMin * 60_000L) {
            streak[pkg] = 0L
            return now + breakMs
        }
        streak[pkg] = s
        return null
    }

    fun streakMs(pkg: String): Long = streak[pkg] ?: 0L
}
