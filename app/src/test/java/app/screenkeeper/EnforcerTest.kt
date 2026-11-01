package app.screenkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnforcerTest {
    private val pkg = "com.example.video"
    private val now = 1_000_000_000L
    private fun eval(
        rule: AppRule?, used: Long = 0, minute: Int = 12 * 60,
        breakUntil: Long = 0, override: Long = 0, unlocked: Boolean = false,
    ) = Enforcer.evaluate(rule, used, minute, now, breakUntil, override, unlocked)

    @Test fun noRuleNeverBlocks() {
        assertNull(eval(null))
        assertNull(eval(AppRule(pkg), used = 99 * Time.HOUR))
    }

    @Test fun limitReachedAtExactlyTheLimit() {
        val r = AppRule(pkg, dailyLimitMin = 60)
        assertNull(eval(r, used = 59 * Time.MINUTE))
        assertEquals(BlockReason.LIMIT_REACHED, eval(r, used = 60 * Time.MINUTE))
    }

    @Test fun blockedWinsOverEverythingButOverride() {
        val r = AppRule(pkg, blocked = true, unlimited = true)
        assertEquals(BlockReason.BLOCKED, eval(r))
        assertNull(eval(r, override = now + 1))
    }

    @Test fun allowedWindowNormalAndWrapping() {
        val day = AppRule(pkg, allowedFromMin = 7 * 60, allowedToMin = 20 * 60)
        assertNull(eval(day, minute = 7 * 60))
        assertEquals(BlockReason.OUTSIDE_HOURS, eval(day, minute = 20 * 60))
        assertEquals(BlockReason.OUTSIDE_HOURS, eval(day, minute = 6 * 60 + 59))

        val night = AppRule(pkg, allowedFromMin = 20 * 60, allowedToMin = 2 * 60)
        assertNull(eval(night, minute = 23 * 60))
        assertNull(eval(night, minute = 60))
        assertEquals(BlockReason.OUTSIDE_HOURS, eval(night, minute = 12 * 60))
    }

    @Test fun unlimitedIgnoresTimeRulesButNotPin() {
        val r = AppRule(pkg, unlimited = true, dailyLimitMin = 1, pinLocked = true)
        assertEquals(BlockReason.PIN_LOCKED, eval(r, used = Time.HOUR))
        assertNull(eval(r, used = Time.HOUR, unlocked = true))
    }

    @Test fun breakBlocksUntilItEnds() {
        val r = AppRule(pkg, breakEveryMin = 30, breakForMin = 5)
        assertEquals(BlockReason.ON_BREAK, eval(r, breakUntil = now + 1))
        assertNull(eval(r, breakUntil = now))
    }

    @Test fun breakTrackerStartsBreakAfterContinuousUse() {
        val r = AppRule(pkg, breakEveryMin = 30, breakForMin = 5)
        val t = BreakTracker()
        var clock = 0L
        var started: Long? = null
        repeat(30 * 60) { clock += 1000; started = started ?: t.tick(pkg, r, clock, 1000) }
        assertNotNull(started)
        assertEquals(clock + 5 * Time.MINUTE, started)
    }

    @Test fun breakTrackerResetsAfterLongPause() {
        val r = AppRule(pkg, breakEveryMin = 30, breakForMin = 5)
        val t = BreakTracker()
        var clock = 0L
        repeat(20 * 60) { clock += 1000; t.tick(pkg, r, clock, 1000) }
        clock += 6 * Time.MINUTE // away longer than a break
        t.tick(pkg, r, clock, 1000)
        assertEquals(1000L, t.streakMs(pkg))
    }

    @Test fun formatting() {
        assertEquals("0m", formatDuration(59_000))
        assertEquals("1h", formatDuration(Time.HOUR))
        assertEquals("1h 30m", formatMinutes(90))
        assertEquals("07:30", formatClock(450))
        assertEquals("4:05", formatCountdown(245_000))
    }
}
