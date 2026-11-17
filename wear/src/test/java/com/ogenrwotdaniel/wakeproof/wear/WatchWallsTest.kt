package com.ogenrwotdaniel.wakeproof.wear

import android.content.pm.ApplicationInfo
import com.ogenrwotdaniel.wakeproof.wear.walls.CLOCKWORK_TIME_UNKNOWN
import com.ogenrwotdaniel.wakeproof.wear.walls.EXAM_HOLD_MAX_MILLIS
import com.ogenrwotdaniel.wakeproof.wear.walls.WallInputs
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchGate
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchResumed
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchSchedule
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchWall
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchWallGrant
import com.ogenrwotdaniel.wakeproof.wear.walls.clockIsAutomatic
import com.ogenrwotdaniel.wakeproof.wear.walls.clockText
import com.ogenrwotdaniel.wakeproof.wear.walls.missingWallGrants
import com.ogenrwotdaniel.wakeproof.wear.walls.openingText
import com.ogenrwotdaniel.wakeproof.wear.walls.wallDetail
import com.ogenrwotdaniel.wakeproof.wear.walls.wallRemovesNotifications
import com.ogenrwotdaniel.wakeproof.wear.walls.wallsStatusLine
import com.ogenrwotdaniel.wakeproof.wear.walls.watchWallVerdict
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchWallsTest {

    // 2026-11-14 is a Saturday, 2026-11-16 a Monday.
    private val inWindow = LocalDateTime.of(2026, 11, 14, 12, 30)
    private val mondayNoon = LocalDateTime.of(2026, 11, 16, 12, 0)
    private val now = 1_800_000_000_000L

    private fun inputs(
        at: LocalDateTime = inWindow,
        autoTime: Boolean = true,
        watchSession: Boolean = false,
        phoneSession: Boolean = false,
        summaryUntil: Long = 0L,
        examUntil: Long = 0L
    ) = WallInputs(now, at, autoTime, watchSession, phoneSession, summaryUntil, examUntil)

    @Test
    fun `named gated apps, social and video categories and browsers are gated`() {
        val none = emptySet<String>()
        assertTrue(WatchGate.isGated("com.whatsapp", ApplicationInfo.CATEGORY_UNDEFINED, false, none))
        assertTrue(WatchGate.isGated("com.google.android.apps.youtube.music", -1, false, none))
        assertTrue(WatchGate.isGated("com.appfour.wearbrowser", -1, false, none))
        assertTrue(WatchGate.isGated("com.android.chrome", -1, false, none))
        assertTrue(WatchGate.isGated("org.example.feed", ApplicationInfo.CATEGORY_SOCIAL, false, none))
        assertTrue(WatchGate.isGated("org.example.clips", ApplicationInfo.CATEGORY_VIDEO, false, none))
        assertTrue(WatchGate.isGated("org.example.browser", -1, true, none))
    }

    @Test
    fun `the watch copy keeps every phone package`() {
        assertTrue(WatchGate.KNOWN_GATED.containsAll(WatchGate.PHONE_KNOWN_GATED))
        assertTrue(WatchGate.KNOWN_GATED.containsAll(WatchGate.PHONE_KNOWN_BROWSERS))
    }

    @Test
    fun `unknown apps fail open and essentials are never gated`() {
        assertFalse(WatchGate.isGated("org.example.bank", ApplicationInfo.CATEGORY_PRODUCTIVITY, false, emptySet()))
        assertFalse(WatchGate.isGated("org.example.unknown", -1, false, emptySet()))
        // A home or dialer that declares itself social is still never walled.
        assertFalse(WatchGate.isGated("org.example.home", ApplicationInfo.CATEGORY_SOCIAL, false, setOf("org.example.home")))
        for (pkg in listOf(
            "com.ogenrwotdaniel.wakeproof", "com.android.systemui", "com.google.android.apps.wearable.settings",
            "com.samsung.android.wear.shealth", "com.samsung.android.emergency", "com.samsung.android.dialer",
            "com.google.android.wearable.app", "com.samsung.android.honeyboard"
        )) {
            assertFalse(pkg, WatchGate.isGated(pkg, ApplicationInfo.CATEGORY_SOCIAL, true, emptySet()))
        }
    }

    @Test
    fun `gated apps open only 12 25 to 12 50, every day`() {
        assertNull(watchWallVerdict(inputs()))
        for (day in 16..22) {
            assertNull(watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, day, 12, 25))))
            assertNull(watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, day, 12, 49))))
            assertEquals(WatchWall.SCHEDULE, watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, day, 12, 24))))
            assertEquals(WatchWall.SCHEDULE, watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, day, 12, 50))))
            assertEquals(WatchWall.SCHEDULE, watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, day, 15, 0))))
        }
    }

    @Test
    fun `the phone's window in force applies on the watch, but only ever tighter`() {
        val default = WatchSchedule.DEFAULT_WINDOW
        assertEquals(default, WatchSchedule.window(-1, -1))
        // A stricter window kept on the phone (12:25–12:40) holds here too.
        val strict = WatchSchedule.window(12 * 60 + 25, 12 * 60 + 40)
        assertEquals(WatchSchedule.Window(12 * 60 + 25, 12 * 60 + 40), strict)
        assertEquals(
            WatchWall.SCHEDULE,
            watchWallVerdict(inputs(at = LocalDateTime.of(2026, 11, 16, 12, 45)).copy(window = strict))
        )
        assertTrue(wallDetail(WatchWall.SCHEDULE, mondayNoon, window = strict).contains("12:25–12:40"))
        // Nothing the phone sends widens the fixed window, or empties it.
        assertEquals(default, WatchSchedule.window(8 * 60, 20 * 60))
        assertEquals(default, WatchSchedule.window(14 * 60, 16 * 60))
        assertEquals(default, WatchSchedule.window(12 * 60 + 50, 12 * 60 + 25))
    }

    @Test
    fun `sessions, the summary, exam week and a manual clock shut the window`() {
        assertEquals(WatchWall.FOCUS, watchWallVerdict(inputs(watchSession = true)))
        assertEquals(WatchWall.FOCUS, watchWallVerdict(inputs(phoneSession = true)))
        assertEquals(WatchWall.SUMMARY, watchWallVerdict(inputs(summaryUntil = now + 60_000L)))
        assertEquals(WatchWall.EXAM, watchWallVerdict(inputs(examUntil = now + 86_400_000L)))
        assertEquals(WatchWall.CLOCK, watchWallVerdict(inputs(autoTime = false, watchSession = true)))
    }

    @Test
    fun `lapsed or impossible phone holds hold nothing`() {
        assertNull(watchWallVerdict(inputs(summaryUntil = now)))
        assertNull(watchWallVerdict(inputs(summaryUntil = now + 31 * 60_000L)))
        assertNull(watchWallVerdict(inputs(examUntil = now - 1L)))
        assertNull(watchWallVerdict(inputs(examUntil = now + EXAM_HOLD_MAX_MILLIS + 1L)))
    }

    @Test
    fun `the next opening is named`() {
        assertEquals(LocalDateTime.of(2026, 11, 16, 12, 25), WatchSchedule.nextOpening(mondayNoon))
        assertEquals(LocalDateTime.of(2026, 11, 15, 12, 25), WatchSchedule.nextOpening(LocalDateTime.of(2026, 11, 14, 12, 50)))
        assertEquals(inWindow, WatchSchedule.nextOpening(inWindow))
        assertEquals("today at 12:25", openingText(LocalDateTime.of(2026, 11, 16, 12, 25), mondayNoon))
        assertEquals("tomorrow at 12:25", openingText(LocalDateTime.of(2026, 11, 15, 12, 25), LocalDateTime.of(2026, 11, 14, 17, 0)))
        assertEquals("Saturday at 12:25", openingText(LocalDateTime.of(2026, 11, 21, 12, 25), mondayNoon))
        assertTrue(wallDetail(WatchWall.SCHEDULE, mondayNoon).contains("today at 12:25"))
        assertTrue(wallDetail(WatchWall.SCHEDULE, mondayNoon).contains("every day 12:25–12:50"))
        assertTrue(wallDetail(WatchWall.FOCUS, inWindow).contains("after this"))
    }

    @Test
    fun `the summary hold removes no notification`() {
        assertFalse(wallRemovesNotifications(null))
        assertFalse(wallRemovesNotifications(WatchWall.SUMMARY))
        assertTrue(wallRemovesNotifications(WatchWall.SCHEDULE))
        assertTrue(wallRemovesNotifications(WatchWall.FOCUS))
    }

    @Test
    fun `missing grants are listed most important first`() {
        assertEquals(emptyList<WatchWallGrant>(), missingWallGrants(true, true, true, true))
        assertEquals(
            listOf(WatchWallGrant.USAGE_ACCESS, WatchWallGrant.NOTIFICATION_LISTENER),
            missingWallGrants(usageAccess = false, overlay = true, notificationListener = false, postNotifications = true)
        )
    }

    @Test
    fun `the app in front is the newest package with something resumed`() {
        val state = WatchResumed()
        state.onResumed("com.whatsapp", "Main", 1L)
        state.onResumed("com.whatsapp", "Chat", 2L)
        state.onLeft("com.whatsapp", "Main")
        assertEquals("com.whatsapp", state.foreground())
        state.onResumed("com.android.server.telecom", "InCall", 3L)
        assertEquals("com.android.server.telecom", state.foreground())
        // The call ends; WhatsApp never paused, so it is in front again.
        state.onLeft("com.android.server.telecom", "InCall")
        assertEquals("com.whatsapp", state.foreground())
        state.clear()
        assertNull(state.foreground())
    }

    @Test
    fun `walls line names the grants the walls need, and is gone once they are given`() {
        assertEquals(
            "Walls off: allow usage access, display over other apps",
            wallsStatusLine(listOf(WatchWallGrant.USAGE_ACCESS, WatchWallGrant.OVERLAY, WatchWallGrant.POST_NOTIFICATIONS))
        )
        assertNull(wallsStatusLine(listOf(WatchWallGrant.POST_NOTIFICATIONS)))
        assertNull(wallsStatusLine(emptyList()))
    }

    @Test
    fun `a watch taking its time from the phone counts as automatic`() {
        // Seen on the Galaxy Watch Ultra: auto_time=1, clockwork_auto_time=0 (from phone).
        assertTrue(clockIsAutomatic(autoTime = 1, clockworkAutoTime = 0))
        assertTrue(clockIsAutomatic(autoTime = 0, clockworkAutoTime = 0))
        assertTrue(clockIsAutomatic(autoTime = 0, clockworkAutoTime = 1))
        assertFalse(clockIsAutomatic(autoTime = 0, clockworkAutoTime = 2))
        assertFalse(clockIsAutomatic(autoTime = 0, clockworkAutoTime = CLOCKWORK_TIME_UNKNOWN))
        assertTrue(clockIsAutomatic(autoTime = 1, clockworkAutoTime = CLOCKWORK_TIME_UNKNOWN))
        // Wear's explicit off wins over a stale stock AUTO_TIME.
        assertFalse(clockIsAutomatic(autoTime = 1, clockworkAutoTime = 2))
    }

    @Test
    fun `wall times follow the watch's 12 or 24 hour setting`() {
        assertEquals("14:00", clockText(14, 0, is24Hour = true, Locale.ENGLISH))
        assertEquals("2:00 PM", clockText(14, 0, is24Hour = false, Locale.ENGLISH))
        assertEquals("12:05 AM", clockText(0, 5, is24Hour = false, Locale.ENGLISH))
        assertEquals("12:00 PM", clockText(12, 0, is24Hour = false, Locale.ENGLISH))
        val twelve = clockText(12, 25, is24Hour = false)
        assertEquals("Saturday at $twelve", openingText(LocalDateTime.of(2026, 11, 21, 12, 25), mondayNoon, is24Hour = false))
        assertTrue(wallDetail(WatchWall.SCHEDULE, mondayNoon, is24Hour = false).contains("every day $twelve–"))
    }

    @Test
    fun `an exam wall names the first opening after the exam, not this window`() {
        val saturdayInWindow = LocalDateTime.of(2026, 11, 21, 12, 30)
        val examEnd = LocalDateTime.of(2026, 11, 24, 0, 0)
        val detail = wallDetail(WatchWall.EXAM, saturdayInWindow, examEnd = examEnd)
        assertFalse(detail.contains("after this"))
        assertTrue(detail, detail.contains("Tuesday at 12:25"))
        // Without an exam end the focus wall inside the window still says "after this".
        assertTrue(wallDetail(WatchWall.FOCUS, saturdayInWindow).contains("after this"))
        // Given a believed exam end, any wall names the opening after the exam.
        val eve = LocalDateTime.of(2026, 11, 16, 17, 0)
        val scheduleDetail = wallDetail(WatchWall.SCHEDULE, eve, examEnd = examEnd)
        assertTrue(scheduleDetail, scheduleDetail.contains("Tuesday 24 November at 12:25"))
        // An end past what the verdict believes (60 days) moves nothing.
        val stale = wallDetail(WatchWall.SCHEDULE, eve, examEnd = eve.plusDays(90))
        assertFalse(stale, stale.contains("next open"))
        // With no exam end known, tomorrow is not promised (an exam week may begin at midnight).
        val evening = wallDetail(WatchWall.FOCUS, eve)
        assertFalse(evening, evening.contains("next open"))
        assertTrue(wallDetail(WatchWall.SCHEDULE, eve).contains("every day 12:25–12:50"))
    }

    @Test
    fun `walls line says the walls are up when only notification access is missing`() {
        assertEquals(
            "Walls up; allow notification access to clear their alerts",
            wallsStatusLine(listOf(WatchWallGrant.NOTIFICATION_LISTENER))
        )
    }
}
