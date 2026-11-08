package app.screenkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinScheduleTest {
    private val pkg = "com.example.video"
    private val now = 1_000_000_000L
    private fun m(h: Int, min: Int = 0) = h * 60 + min

    private fun eval(rule: AppRule?, minute: Int, schedulePin: Boolean = false, unlocked: Boolean = false) =
        Enforcer.evaluate(rule, 0, minute, now, 0, 0, unlocked, schedulePin)

    @Test fun appPinOnlyInsideItsWindow() {
        // 21:00 – 00:00
        val r = AppRule(pkg, pinLocked = true, pinFromMin = m(21), pinToMin = 0)
        assertNull(eval(r, m(20, 59)))
        assertEquals(BlockReason.PIN_LOCKED, eval(r, m(21)))
        assertEquals(BlockReason.PIN_LOCKED, eval(r, m(23, 59)))
        assertNull(eval(r, 0))
        assertNull(eval(r, m(12)))
    }

    @Test fun appPinWithoutWindowIsAlways() {
        val r = AppRule(pkg, pinLocked = true)
        assertEquals(BlockReason.PIN_LOCKED, eval(r, m(3)))
        assertEquals(BlockReason.PIN_LOCKED, eval(r, m(15)))
    }

    @Test fun unlockForTheVisitStillWorks() {
        val r = AppRule(pkg, pinLocked = true, pinFromMin = m(21), pinToMin = 0)
        assertNull(eval(r, m(22), unlocked = true))
    }

    @Test fun scheduleAppliesToAppsWithoutRules() {
        assertEquals(BlockReason.PIN_LOCKED, eval(null, m(22), schedulePin = true))
        assertEquals(BlockReason.PIN_LOCKED, eval(AppRule(pkg), m(22), schedulePin = true))
        assertNull(eval(null, m(22), schedulePin = true, unlocked = true))
        assertNull(eval(null, m(22)))
    }

    @Test fun otherBlocksComeBeforeThePin() {
        val r = AppRule(pkg, blocked = true)
        assertEquals(BlockReason.BLOCKED, eval(r, m(22), schedulePin = true))
    }

    @Test fun scheduleCoversAllOrTickedApps() {
        val all = PinSchedule(enabled = true, fromMin = m(20), toMin = m(7))
        assertTrue(Enforcer.pinRequired(null, all, "any.app", m(23)))
        assertTrue(Enforcer.pinRequired(null, all, "any.app", m(6, 59)))
        assertFalse(Enforcer.pinRequired(null, all, "any.app", m(7)))

        val some = all.copy(allApps = false, pkgs = setOf(pkg))
        assertTrue(Enforcer.pinRequired(null, some, pkg, m(23)))
        assertFalse(Enforcer.pinRequired(null, some, "other.app", m(23)))

        assertFalse(Enforcer.pinRequired(null, all.copy(enabled = false), "any.app", m(23)))
    }

    @Test fun ruleOrScheduleEitherRequiresPin() {
        val r = AppRule(pkg, pinLocked = true, pinFromMin = m(9), pinToMin = m(10))
        val s = PinSchedule(enabled = true, fromMin = m(21), toMin = 0, allApps = false, pkgs = setOf(pkg))
        assertTrue(Enforcer.pinRequired(r, s, pkg, m(9, 30)))
        assertTrue(Enforcer.pinRequired(r, s, pkg, m(21, 30)))
        assertFalse(Enforcer.pinRequired(r, s, pkg, m(12)))
    }

    @Test fun sameStartAndEndMeansAllDay() {
        assertTrue(Enforcer.inWindow(m(8), m(8), m(3)))
    }

    @Test fun sanitizeDropsWindowWhenPinOff() {
        val r = sanitizeRule(AppRule(pkg, pinLocked = false, pinFromMin = m(21), pinToMin = 0))
        assertEquals(-1, r.pinFromMin)
        assertTrue(r.isDefault)
    }
}

class PinScheduleDaysTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min
    private val SUN = 1; private val MON = 2; private val FRI = 6; private val SAT = 7

    @Test fun onlyTickedDays() {
        val s = PinSchedule(enabled = true, fromMin = m(5), toMin = m(21), days = PinSchedule.WEEKDAYS)
        assertTrue(s.activeAt(m(12), MON))
        assertTrue(s.activeAt(m(12), FRI))
        assertFalse(s.activeAt(m(12), SAT))
        assertFalse(s.activeAt(m(12), SUN))
        assertFalse(s.activeAt(m(22), MON))
    }

    @Test fun afterMidnightBelongsToTheStartDay() {
        val s = PinSchedule(enabled = true, fromMin = m(21), toMin = m(2), days = setOf(FRI))
        assertTrue(s.activeAt(m(23), FRI))
        assertTrue(s.activeAt(m(1), SAT))   // Friday night, continued
        assertFalse(s.activeAt(m(1), FRI))  // Thursday night: Thursday isn't ticked
        assertFalse(s.activeAt(m(23), SAT))
    }

    @Test fun sundayToMondayWrap() {
        val s = PinSchedule(enabled = true, fromMin = m(22), toMin = m(6), days = setOf(SUN))
        assertTrue(s.activeAt(m(3), MON))
        assertFalse(s.activeAt(m(3), SUN))
    }

    @Test fun noDaysNeverApplies() {
        val s = PinSchedule(enabled = true, fromMin = m(0), toMin = m(0), days = emptySet())
        assertFalse(Enforcer.pinRequired(null, s, "a", m(12), MON))
    }

    @Test fun pinRequiredUsesDays() {
        val s = PinSchedule(enabled = true, fromMin = m(9), toMin = m(17), days = PinSchedule.WEEKEND)
        assertTrue(Enforcer.pinRequired(null, s, "a", m(10), SAT))
        assertFalse(Enforcer.pinRequired(null, s, "a", m(10), MON))
    }
}
