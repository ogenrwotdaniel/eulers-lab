package com.ogenrwotdaniel.wakeproof.distraction

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallRulesTest {

    private fun fmt(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    @Test
    fun `a stored value counts only when it is stricter than the fixed one`() {
        val bedtime = WallRule.BEDTIME_START_MINUTES
        assertEquals(16 * 60, bedtime.fixedOrStricter(null))
        assertEquals(16 * 60, bedtime.fixedOrStricter(21 * 60))
        assertEquals(16 * 60, bedtime.fixedOrStricter(16 * 60))
        assertEquals(16 * 60, bedtime.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(14 * 60, bedtime.fixedOrStricter(14 * 60))
        // A bedtime stored before the window is read as the window's start (then reset by fixedRules).
        assertEquals(12 * 60 + 25, bedtime.fixedOrStricter(11 * 60))
        val start = WallRule.OPEN_WINDOW_START_MINUTES
        assertEquals(12 * 60 + 25, start.fixedOrStricter(8 * 60 + 10))
        assertEquals(12 * 60 + 25, start.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(12 * 60 + 30, start.fixedOrStricter(12 * 60 + 30))
        val end = WallRule.OPEN_WINDOW_END_MINUTES
        assertEquals(12 * 60 + 50, end.fixedOrStricter(16 * 60))
        assertEquals(12 * 60 + 40, end.fixedOrStricter(12 * 60 + 40))
        assertEquals(RULE_ON, WallRule.GATE_BROWSERS.fixedOrStricter(RULE_OFF))
    }

    @Test
    fun `a stored window the bedtime wall or new start now covers falls back to the fixed one`() {
        val rules = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 8 * 60 + 50 else null }
        assertEquals(12 * 60 + 25 to 12 * 60 + 50, gatedOpenWindow(rules))
        val kept = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 12 * 60 + 40 else null }
        assertEquals(12 * 60 + 25 to 12 * 60 + 40, gatedOpenWindow(kept))
        // The old Saturday–Sunday 14:00–16:00 window stored on a device leaves no minute
        // of the new one open, so the fixed daily window comes back.
        val weekend = fixedRules {
            when (it) {
                WallRule.OPEN_WINDOW_START_MINUTES -> 14 * 60
                WallRule.OPEN_WINDOW_END_MINUTES -> 16 * 60
                else -> null
            }
        }
        assertEquals(12 * 60 + 25 to 12 * 60 + 50, gatedOpenWindow(weekend))
    }

    @Test
    fun `a stored bedtime that closes the whole window falls back with the window`() {
        // Kept as stricter (12:00 < 16:00), but it leaves no minute of the window open.
        val early = fixedRules { if (it == WallRule.BEDTIME_START_MINUTES) 12 * 60 else null }
        assertEquals(12 * 60 + 25 to 12 * 60 + 50, gatedOpenWindow(early))
        assertEquals(16 * 60, early.bedtimeStartMinutes)
        // One that still leaves part of the window open is the user's own tightening.
        val partial = fixedRules { if (it == WallRule.BEDTIME_START_MINUTES) 12 * 60 + 40 else null }
        assertEquals(12 * 60 + 25 to 12 * 60 + 40, gatedOpenWindow(partial))
        // An empty stored window resets alone when the stored bedtime leaves the fixed one partly open.
        val both = fixedRules {
            when (it) {
                WallRule.OPEN_WINDOW_START_MINUTES -> 6 * 60
                WallRule.OPEN_WINDOW_END_MINUTES -> 8 * 60
                WallRule.BEDTIME_START_MINUTES -> 12 * 60 + 40
                else -> null
            }
        }
        assertEquals(12 * 60 + 25 to 12 * 60 + 40, gatedOpenWindow(both))
        // A stricter stored window survives when resetting the bedtime alone reopens it.
        val stricterWindow = fixedRules {
            when (it) {
                WallRule.OPEN_WINDOW_START_MINUTES -> 12 * 60 + 30
                WallRule.OPEN_WINDOW_END_MINUTES -> 12 * 60 + 45
                WallRule.BEDTIME_START_MINUTES -> 12 * 60
                else -> null
            }
        }
        assertEquals(12 * 60 + 30 to 12 * 60 + 45, gatedOpenWindow(stricterWindow))
    }

    @Test
    fun `gated apps open for the whole daily window, which bedtime does not touch`() {
        assertEquals(12 * 60 + 25 to 12 * 60 + 50, gatedOpenWindow(WallRules()))
        // An earlier window that starts before BEDTIME_END_MINUTES waits for it.
        val early = WallRules(mapOf(WallRule.OPEN_WINDOW_START_MINUTES to 8 * 60))
        assertEquals(8 * 60 + 20 to 12 * 60 + 50, gatedOpenWindow(early))
        val noBedtime = WallRules(mapOf(WallRule.BEDTIME_START_MINUTES to RULE_OFF_MINUTES))
        assertEquals(12 * 60 + 25 to 12 * 60 + 50, gatedOpenWindow(noBedtime))
        assertNull(gatedOpenWindow(WallRules(mapOf(WallRule.OPEN_WINDOW_START_MINUTES to RULE_OFF_MINUTES))))
    }

    @Test
    fun `defaults are a 16 00 bedtime and a daily 12 25 to 12 50 window with no shut days`() {
        val rules = WallRules()
        assertTrue(rules.gateBrowsers && rules.sessionDnd)
        assertEquals(16 * 60, rules.bedtimeStartMinutes)
        assertEquals(12 * 60 + 25, rules.openWindowStartMinutes)
        assertEquals(12 * 60 + 50, rules.openWindowEndMinutes)
    }

    @Test
    fun `bedtime runs from 16 00 to 08 20, whatever the alarms did`() {
        val start = 16 * 60
        assertFalse(bedtimeActive(15 * 60 + 59, start))
        assertTrue(bedtimeActive(16 * 60, start))
        assertTrue(bedtimeActive(23 * 60, start))
        assertTrue(bedtimeActive(6 * 60 + 30, start))
        assertTrue(bedtimeActive(8 * 60 + 19, start))
        assertFalse(bedtimeActive(8 * 60 + 20, start))
        assertFalse(bedtimeActive(14 * 60, start))
        assertFalse(bedtimeActive(23 * 60, RULE_OFF_MINUTES))
    }

    @Test
    fun `browsers are gated only while the rule is on`() {
        val samsung = "com.sec.android.app.sbrowser"
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(samsung, other, null, isBrowser = true, gateBrowsers = true))
        assertFalse(AppGate.isGated(samsung, other, null, isBrowser = true, gateBrowsers = false))
    }

    @Test
    fun `chrome is gated like every browser, and so are the sites saved from it as apps`() {
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(AppGate.CHROME, other, null, isBrowser = true, gateBrowsers = true))
        assertTrue(AppGate.isGated("com.chrome.beta", other, null, isBrowser = true, gateBrowsers = true))
        // A shell answers for no web link, so only its name marks it.
        assertTrue(AppGate.isGated("org.chromium.webapk.a1b2c3", other, null, isBrowser = false, gateBrowsers = true))
        assertFalse(AppGate.isGated("org.chromium.webapk.a1b2c3", other, null, isBrowser = false, gateBrowsers = false))
    }

    @Test
    fun `gated apps open 12 25 to 12 50, midday, every day`() {
        val rules = WallRules()
        val start = rules.openWindowStartMinutes
        val end = rules.openWindowEndMinutes
        assertTrue(outsideOpenWindow(12 * 60 + 24, start, end))
        assertFalse(outsideOpenWindow(12 * 60 + 25, start, end))
        assertFalse(outsideOpenWindow(12 * 60 + 49, start, end))
        assertTrue(outsideOpenWindow(12 * 60 + 50, start, end))
        // Midday, not just after midnight.
        assertTrue(outsideOpenWindow(0 * 60 + 30, start, end))
        assertTrue(outsideOpenWindow(15 * 60, start, end))
    }

    @Test
    fun `nextOpenAfterToday is tomorrow at 12 25`() {
        assertEquals("tomorrow at 12:25", nextOpenAfterToday(true, 12 * 60 + 25, 12 * 60 + 50, ::fmt))
        assertNull(nextOpenAfterToday(false, 12 * 60 + 25, 12 * 60 + 50, ::fmt))
        assertNull(nextOpenAfterToday(true, RULE_OFF_MINUTES, 12 * 60 + 50, ::fmt))
        assertNull(nextOpenAfterToday(true, 12 * 60 + 50, 12 * 60 + 25, ::fmt))
    }

    @Test
    fun `windowWallCopy names the daily window and only a same-day opening`() {
        val (title, morning) = windowWallCopy("Instagram", 9 * 60, 12 * 60 + 25, 12 * 60 + 50, ::fmt)
        assertEquals("Not open now", title)
        assertTrue(morning, morning.contains("between 12:25 and 12:50 each day"))
        assertTrue(morning, morning.contains("Next open today at 12:25"))
        // After the window, tomorrow is not promised: an exam week may start at midnight.
        val (_, evening) = windowWallCopy("Instagram", 17 * 60, 12 * 60 + 25, 12 * 60 + 50, ::fmt)
        assertTrue(evening, evening.contains("between 12:25 and 12:50 each day"))
        assertFalse(evening, evening.contains("Next open"))
        val (_, empty) = windowWallCopy("Instagram", 17 * 60, 12 * 60 + 50, 12 * 60 + 25, ::fmt)
        assertTrue(empty, empty.contains("shut right now"))
    }

    @Test
    fun `the walls stand in front of a gated app inside the window and leave other apps alone`() {
        fun verdict(bedtime: Boolean = false) =
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, bedtime = bedtime
            )
        assertEquals(GateVerdict.ALLOW, verdict())
        assertEquals(GateVerdict.BEDTIME_WALL, verdict(bedtime = true))

        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = false, bedtime = true
            )
        )
    }
}

class OpenWindowTest {

    @Test
    fun `gated apps open only inside the configured window`() {
        val start = 8 * 60 + 10
        val end = 8 * 60 + 50
        assertTrue(outsideOpenWindow(8 * 60 + 9, start, end))
        assertFalse(outsideOpenWindow(8 * 60 + 10, start, end))
        assertFalse(outsideOpenWindow(8 * 60 + 49, start, end))
        assertTrue(outsideOpenWindow(8 * 60 + 50, start, end))
        assertTrue(outsideOpenWindow(3 * 60, start, end))
        assertFalse(outsideOpenWindow(3 * 60, RULE_OFF_MINUTES, end))
        // A window that ends before it starts is open at no time.
        assertTrue(outsideOpenWindow(15 * 60, 16 * 60, 15 * 60))
    }

    @Test
    fun `outside the window comes before bedtime`() {
        fun verdict(gated: Boolean, outsideWindow: Boolean = true) = gateVerdict(
            essential = false, focusSessionActive = false, onFocusList = false,
            capSpent = false, gated = gated,
            outsideWindow = outsideWindow, bedtime = true
        )
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = true))
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = false))
        assertEquals(GateVerdict.ALLOW, verdict(gated = false, outsideWindow = false))
    }

}
