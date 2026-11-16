package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusFaceTest {

    private val t0 = 1_800_000_000_000L
    private val running = WatchTimer.start(WatchDurations(), null, t0, WatchSession.TWO_HOURS, "session-0001")

    private fun phone(running: Boolean, session: PhoneSession? = null, finishAt: Long = t0 + 600_000L) =
        TimerSnapshot(
            active = true,
            running = running,
            phase = "Focus",
            remainingSeconds = 300,
            finishAtMillis = finishAt,
            session = session
        )

    private val phoneSession = PhoneSession(
        sessionId = "phone-session-1",
        origin = "phone",
        phaseName = "FOCUS",
        phaseEndsAt = t0 + 600_000L,
        completedFocus = 1,
        targetFocusBlocks = 4,
        focusSeconds = 1500,
        shortBreakSeconds = 300,
        longBreakSeconds = 900,
        longBreakEvery = 4,
        lastFocusSeconds = 1500,
        subject = "Graphene"
    )

    // --- Controls: exactly the old screen's, never a pause or stop ---------------

    @Test
    fun `a running focus block offers no control at all`() {
        assertEquals(emptyList<FocusAction>(), FocusFaces.watchActions(running))
    }

    @Test
    fun `a break of the watch's own session offers skip break only`() {
        val onBreak = running.copy(phase = WatchPhase.SHORT_BREAK, completedFocus = 1)
        assertEquals(listOf(FocusAction.SKIP_BREAK), FocusFaces.watchActions(onBreak))
    }

    @Test
    fun `a break of a followed phone session cannot be skipped`() {
        val following = running.copy(phase = WatchPhase.SHORT_BREAK, origin = "phone")
        assertEquals(emptyList<FocusAction>(), FocusFaces.watchActions(following))
    }

    @Test
    fun `a run paused by an older build can only be resumed`() {
        val paused = running.copy(running = false, remainingMillis = 60_000L)
        assertEquals(listOf(FocusAction.RESUME), FocusFaces.watchActions(paused))
    }

    @Test
    fun `the phone's running session offers continue here only when it sent the session`() {
        assertEquals(emptyList<FocusAction>(), FocusFaces.phoneActions(phone(running = true)))
        assertEquals(
            listOf(FocusAction.CONTINUE_HERE),
            FocusFaces.phoneActions(phone(running = true, session = phoneSession))
        )
    }

    @Test
    fun `a paused phone run offers resume and never continue here`() {
        assertEquals(
            listOf(FocusAction.PHONE_RESUME),
            FocusFaces.phoneActions(phone(running = false, session = phoneSession))
        )
    }

    @Test
    fun `idle offers the four lengths, the phone, then sign-in only when it applies`() {
        val signedIn = FocusFaces.idleActions(canSignInHere = true, signedIn = true)
        assertEquals(WatchSession.entries.map { IdleAction.Start(it) } + IdleAction.StartOnPhone, signedIn)
        assertEquals(IdleAction.SignIn, FocusFaces.idleActions(canSignInHere = true, signedIn = false).last())
        assertFalse(IdleAction.SignIn in FocusFaces.idleActions(canSignInHere = false, signedIn = false))
    }

    // --- The watch's own face ---------------------------------------------------

    @Test
    fun `interactive countdown is minutes and seconds, rounded up`() {
        val face = FocusFaces.watchFace(running, t0 + 1, ambient = false)
        assertEquals("25:00", face.countdown)
        assertEquals("Block 1 of ${running.targetFocusBlocks}", face.blockLine)
        assertEquals("Break", face.nextLabel)
        assertEquals(running.phaseEndsAt, face.nextAt)
    }

    @Test
    fun `ambient countdown is whole minutes rounded up`() {
        val face = FocusFaces.watchFace(running, t0 + 60_000L + 1, ambient = true)
        assertEquals("24 min", face.countdown)
        assertEquals(1L, FocusFaces.ambientMinutes(1))
        assertEquals(0L, FocusFaces.ambientMinutes(0))
    }

    @Test
    fun `the last focus block ends in done`() {
        val last = running.copy(completedFocus = running.targetFocusBlocks - 1)
        assertEquals("Done", FocusFaces.watchFace(last, t0, ambient = false).nextLabel)
    }

    @Test
    fun `a paused run shows its frozen remainder and no next time`() {
        val paused = running.copy(running = false, remainingMillis = 90_000L)
        val face = FocusFaces.watchFace(paused, t0 + 10_000_000L, ambient = false)
        assertEquals("1:30", face.countdown)
        assertTrue(face.paused)
        assertNull(face.nextAt)
        assertNull(face.nextLabel)
    }

    @Test
    fun `progress is the share of the phase behind`() {
        val half = FocusFaces.watchFace(running, t0 + 750_000L, ambient = false)
        assertEquals(0.5f, half.progress!!, 0.001f)
        assertEquals(TreeStage.SAPLING, half.tree)
        assertEquals(TreeStage.SEED, FocusFaces.watchFace(running, t0, ambient = false).tree)
    }

    @Test
    fun `an event session's face shows the event title, not the course`() {
        val event = WatchTimer.startExact(50, "Maths", t0, eventTitle = "Thermodynamics")
        assertEquals("Thermodynamics", FocusFaces.watchFace(event, t0, ambient = false).subject)
    }

    @Test
    fun `a break rests the tree`() {
        assertEquals(TreeStage.RESTING, FocusFaces.treeStage(isFocus = false, progress = 0.9f))
        assertEquals(TreeStage.TREE, FocusFaces.treeStage(isFocus = true, progress = 0.9f))
    }

    // --- The phone's face ---------------------------------------------------------

    @Test
    fun `the phone's countdown rounds up from its finish time`() {
        val face = FocusFaces.phoneFace(phone(running = true, session = phoneSession), t0 + 1)
        assertEquals("10:00", face.countdown)
        assertEquals("Focus · phone", face.phaseLabel)
        assertEquals("Block 2 of 4", face.blockLine)
        assertEquals("Graphene", face.subject)
        assertEquals(1f - 600f / 1500f, face.progress!!, 0.001f)
    }

    @Test
    fun `a phone push without its session has no progress or block line`() {
        val face = FocusFaces.phoneFace(phone(running = true), t0)
        assertNull(face.progress)
        assertNull(face.blockLine)
    }

    @Test
    fun `a paused phone run shows the remaining seconds it pushed`() {
        val face = FocusFaces.phoneFace(phone(running = false), t0)
        assertEquals("5:00", face.countdown)
        assertTrue(face.paused)
    }

    // --- Progress, cloud and agenda lines -------------------------------------------

    @Test
    fun `goal ring is clamped and green only once met`() {
        assertEquals(0.5f, FocusFaces.goalShare(180, 360), 0.001f)
        assertEquals(1f, FocusFaces.goalShare(500, 360), 0.001f)
        assertEquals(0f, FocusFaces.goalShare(100, 0), 0.001f)
        assertTrue(FocusFaces.goalMet(360, 360))
        assertFalse(FocusFaces.goalMet(10, 0))
    }

    @Test
    fun `unsynced line counts blocks`() {
        assertNull(FocusFaces.unsyncedLine(0))
        assertEquals("1 block waiting for phone", FocusFaces.unsyncedLine(1))
        assertEquals("2 blocks waiting for phone", FocusFaces.unsyncedLine(2))
    }

    @Test
    fun `cloud line prefers the sign-in outcome and is silent without cloud`() {
        assertEquals("Signed in as a", FocusFaces.cloudLine("Signed in as a", true, "b", 0))
        assertNull(FocusFaces.cloudLine(null, false, null, 0))
        assertEquals("Cloud: not signed in", FocusFaces.cloudLine(null, true, null, 3))
        assertEquals("☁ me\n2 waiting for network", FocusFaces.cloudLine(null, true, "me", 2))
    }

    @Test
    fun `agenda empty text explains why`() {
        val event = WatchAgendaEvent("k", "Lab", t0, t0 + 3_600_000L, false)
        assertEquals(
            "Nothing from the phone yet. Open Euler's Lab on the phone.",
            FocusFaces.agendaEmptyText(WatchAgendaList(), 10)
        )
        assertEquals(
            "Calendar access is off on the phone.",
            FocusFaces.agendaEmptyText(WatchAgendaList(present = true, epochDay = 10, allowed = false), 10)
        )
        assertEquals(
            "Not synced today yet. Open Euler's Lab on the phone.",
            FocusFaces.agendaEmptyText(WatchAgendaList(present = true, epochDay = 9, events = listOf(event)), 10)
        )
        assertEquals(
            "Nothing timed on the calendar today.",
            FocusFaces.agendaEmptyText(WatchAgendaList(present = true, epochDay = 10), 10)
        )
        assertNull(FocusFaces.agendaEmptyText(WatchAgendaList(present = true, epochDay = 10, events = listOf(event)), 10))
    }

    // --- Tile arc -------------------------------------------------------------------

    @Test
    fun `tile arc follows the phase while a timer runs`() {
        val ring = FocusFaces.tileRing(360, 100, running, t0 + 750_000L)
        assertEquals(0.5f, ring.progress, 0.001f)
        assertTrue(ring.accent)
        assertEquals(running.phaseEndsAt, ring.phaseEndsAt)
        assertEquals(1500L, ring.phaseSeconds)
    }

    @Test
    fun `tile arc shows the goal when idle, green once met`() {
        val idle = WatchTimerState()
        val ring = FocusFaces.tileRing(360, 90, idle, t0)
        assertEquals(0.25f, ring.progress, 0.001f)
        assertTrue(ring.accent)
        assertNull(ring.phaseEndsAt)
        assertFalse(FocusFaces.tileRing(360, 400, idle, t0).accent)
    }
}
