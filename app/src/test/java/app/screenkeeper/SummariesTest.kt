package app.screenkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class SummariesTest {
    private val H = Time.HOUR
    private val M = Time.MINUTE

    private fun day(vararg apps: Pair<String, Long>, hour: Int = 20) = DayRecord(
        apps.toMap(),
        apps.associate { it.first to 2 },
        LongArray(24).also { it[hour] = apps.sumOf { a -> a.second } },
    )

    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun monthTotalsAverageAndTopApps() {
        val data = MonthData(
            days = mutableMapOf(
                1 to day("yt" to 2 * H, "kodi" to 30 * M),
                2 to day("yt" to 1 * H),
                3 to day(),
            ),
            labels = mutableMapOf("yt" to "YouTube", "kodi" to "Kodi"),
        )
        val r = Summaries.month(2026, 3, data, prevTotal = 7 * H, now = at(2026, 4, 2), locale = Locale.US)
        assertEquals(3 * H + 30 * M, r.total)
        assertEquals((3 * H + 30 * M) / 3, r.avgPerDay) // 3 tracked days
        assertEquals(2, r.activeDays)
        assertEquals(31, r.bars.size)
        assertEquals("YouTube", r.apps[0].label)
        assertEquals(4, r.apps[0].opens)
        assertEquals(20, r.peakHour)
        assertEquals(-0.5, r.change!!, 1e-9)
        assertEquals("March 2026", r.title)
        assertTrue(r.busiest!!.first.contains("1"))
    }

    @Test fun yearCombinesMonthsAndEstimates() {
        val months = (1..12).associateWith { MonthData() }.toMutableMap()
        months[1] = MonthData(estimate = mutableMapOf("yt" to 10 * H), labels = mutableMapOf("yt" to "YouTube"))
        months[2] = MonthData(days = mutableMapOf(5 to day("yt" to 3 * H, "plex" to 1 * H)))
        val r = Summaries.year(2026, months, prevTotal = 0, now = at(2026, 6, 1), locale = Locale.US)
        assertEquals(14 * H, r.total)
        assertTrue(r.estimated)
        assertEquals(31 + 1, r.trackedDays) // January estimated over its 31 days + one recorded day
        assertEquals("January", r.busiest!!.first)
        assertEquals(13 * H, r.apps.first { it.pkg == "yt" }.ms)
        assertNull(r.change)
        assertEquals(12, r.bars.size)
    }

    @Test fun emptyMonth() {
        val r = Summaries.month(2026, 2, MonthData(), 0, at(2026, 2, 10), Locale.US)
        assertEquals(0, r.total)
        assertNull(r.peakHour)
        assertNull(r.busiest)
        assertEquals(28, r.bars.size)
    }

    @Test fun addByHourSplitsAcrossHours() {
        val start = Calendar.getInstance().apply { clear(); set(2026, 0, 10, 19, 30) }.timeInMillis
        val hours = LongArray(24)
        Time.addByHour(start, start + 90 * M, hours)
        assertEquals(30 * M, hours[19])
        assertEquals(60 * M, hours[20])
        assertEquals(90 * M, hours.sum())
    }

    @Test fun monthKeys() {
        assertEquals("2026-01", UsageHistory.shiftMonth("2025-12", 1))
        assertEquals("2025-12", UsageHistory.shiftMonth("2026-01", -1))
        assertNull(UsageHistory.parseMonth("2026-13"))
    }
}
