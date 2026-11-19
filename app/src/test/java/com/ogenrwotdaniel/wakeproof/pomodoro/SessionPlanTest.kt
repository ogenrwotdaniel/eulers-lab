package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPlanTest {

    @Test
    fun `the reading preset is one unbroken 30-minute block whatever the settings`() {
        // At 50/10 the nearest whole session would have been a 50-minute block, at 25/5 a 25-minute one.
        for ((focus, rest) in listOf(50 to 10, 25 to 5)) {
            val plan = sessionPlan(READING_SESSION_MINUTES, focus, rest, 30)
            assertEquals(30, plan.focusMinutes)
            assertEquals(1, plan.focusBlocks)
            assertEquals(30, plan.lastFocusMinutes)
        }
    }

    @Test
    fun `three hours is no preset any more, only a length`() {
        assertEquals(SessionPlan(25, 5, 15, focusBlocks = 6, longBreakEvery = 4), sessionPlan(180, 25, 5, 15))
    }

    @Test
    fun `other lengths run the user's own blocks, long break every fourth`() {
        assertEquals(SessionPlan(25, 5, 15, focusBlocks = 4, longBreakEvery = 4), sessionPlan(120, 25, 5, 15))
    }

    @Test
    fun `a start with no length is an hour, never a session that cannot end`() {
        assertEquals(2, sessionPlan(0, 25, 5, 15).focusBlocks)
    }

    @Test
    fun `only the 30 min option owes no summary, everything else does`() {
        val reading = sessionPlan(READING_SESSION_MINUTES, 25, 5, 15)
        assertEquals(false, sessionOwesSummary(reading.focusBlocks, reading.focusMinutes * 60, reading.lastFocusMinutes * 60))
        // A calendar event of 25 or 35 minutes is no exception.
        for (minutes in listOf(25, 30, 35)) {
            val event = exactSessionPlan(minutes)
            assertEquals(true, sessionOwesSummary(event.focusBlocks, event.focusMinutes * 60, event.lastFocusMinutes * 60))
        }
        val hour = sessionPlan(60, 25, 5, 15)
        assertEquals(true, sessionOwesSummary(hour.focusBlocks, hour.focusMinutes * 60, hour.lastFocusMinutes * 60))
        // No known plan: owed, as before.
        assertEquals(true, sessionOwesSummary(0, 1_500, 1_500))
    }
}
