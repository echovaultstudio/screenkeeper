package app.screenkeeper

/** Pure decision logic: given the rule and current state, should the app be blocked, and why? */
object Enforcer {

    fun inAllowedWindow(rule: AppRule, minuteOfDay: Int): Boolean =
        !rule.hasWindow || inWindow(rule.allowedFromMin, rule.allowedToMin, minuteOfDay)

    /** Is [minuteOfDay] in [from, to)? The window may wrap past midnight (20:00–02:00); from == to means all day. */
    fun inWindow(from: Int, to: Int, minuteOfDay: Int): Boolean = when {
        from == to -> true
        from < to -> minuteOfDay in from until to
        else -> minuteOfDay >= from || minuteOfDay < to
    }

    /**
     * Does opening [pkg] need the PIN right now, by its own rule or by the device-wide schedule?
     * [dayOfWeek] is a Calendar.DAY_OF_WEEK value; -1 ignores the schedule's days.
     */
    fun pinRequired(rule: AppRule?, schedule: PinSchedule?, pkg: String, minuteOfDay: Int, dayOfWeek: Int = -1): Boolean {
        if (rule != null && rule.pinLocked && (!rule.hasPinWindow || inWindow(rule.pinFromMin, rule.pinToMin, minuteOfDay))) return true
        if (schedule != null && schedule.enabled && schedule.covers(pkg)) {
            val on = if (dayOfWeek < 0) inWindow(schedule.fromMin, schedule.toMin, minuteOfDay) else schedule.activeAt(minuteOfDay, dayOfWeek)
            if (on) return true
        }
        return false
    }

    fun evaluate(
        rule: AppRule?,
        usedTodayMs: Long,
        minuteOfDay: Int,
        now: Long,
        breakUntil: Long,
        overrideUntil: Long,
        sessionUnlocked: Boolean,
        /** The device-wide PIN schedule requires the PIN for this app right now. */
        schedulePin: Boolean = false,
    ): BlockReason? {
        if (rule == null || rule.isDefault) return if (schedulePin && !sessionUnlocked) BlockReason.PIN_LOCKED else null
        if (overrideUntil > now) return null // PIN-granted extra time
        if (rule.blocked) return BlockReason.BLOCKED
        if (!rule.unlimited) {
            if (!inAllowedWindow(rule, minuteOfDay)) return BlockReason.OUTSIDE_HOURS
            if (rule.dailyLimitMin > 0 && usedTodayMs >= rule.dailyLimitMin * 60_000L) return BlockReason.LIMIT_REACHED
            if (rule.hasBreaks && breakUntil > now) return BlockReason.ON_BREAK
        }
        val rulePin = rule.pinLocked && (!rule.hasPinWindow || inWindow(rule.pinFromMin, rule.pinToMin, minuteOfDay))
        if ((rulePin || schedulePin) && !sessionUnlocked) return BlockReason.PIN_LOCKED
        return null
    }

    /** A gap this long between guard ticks means the device slept (the tick loop pauses in standby). */
    const val SLEEP_GAP_MS = 30_000L

    /**
     * Does a PIN unlock for [unlockedPkg] survive this tick? An unlock lasts for one visit, and a visit
     * ends when another app comes to the front or when the screen goes off — otherwise an app left open
     * when the TV is switched off would come back unlocked (issue #1).
     *
     * [fg] is null while nothing is known to be in front (screen off, or between two activities);
     * that alone doesn't end the visit, only [screenWentOff] or a sleep-sized [tickGapMs] does.
     */
    fun keepUnlock(unlockedPkg: String?, fg: String?, selfPkg: String, screenWentOff: Boolean, tickGapMs: Long): Boolean {
        if (unlockedPkg == null) return false
        if (screenWentOff || tickGapMs >= SLEEP_GAP_MS) return false
        return fg == null || fg == selfPkg || fg == unlockedPkg
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
