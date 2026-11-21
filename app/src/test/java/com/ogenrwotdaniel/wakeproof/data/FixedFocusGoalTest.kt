package com.ogenrwotdaniel.wakeproof.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedFocusGoalTest {

    @Test
    fun `the goal is fixed from 2026-11-21 at eight hours`() {
        assertEquals(LocalDate.of(2026, 11, 21).toEpochDay(), FIXED_FOCUS_GOAL_SINCE_DAY)
        assertEquals(480, focusGoalBaseOn(FIXED_FOCUS_GOAL_SINCE_DAY, goalBeforeFixed = 360))
        assertEquals(480, focusGoalBaseOn(FIXED_FOCUS_GOAL_SINCE_DAY + 30, goalBeforeFixed = 120))
    }

    @Test
    fun `days before it keep the goal set then, so a won day stays won`() {
        val yesterday = FIXED_FOCUS_GOAL_SINCE_DAY - 1
        assertEquals(360, focusGoalBaseOn(yesterday, goalBeforeFixed = 360))
        // 387 minutes against the 6 h goal of the day was MET; against 8 h it would not be.
        assertEquals(DayOutcome.MET, focusDayOutcome(387, focusGoalBaseOn(yesterday, 360)))
    }

    @Test
    fun `an exam week raises the fixed goal to ten then twelve hours`() {
        val day = FIXED_FOCUS_GOAL_SINCE_DAY
        val base = focusGoalBaseOn(day, 360)
        assertEquals(600, focusGoalOn(day, base, listOf(ExamDay("Calculus", day + 10))))
        assertEquals(720, focusGoalOn(day, base, listOf(ExamDay("Calculus", day + 5))))
        assertEquals(720, focusGoalOn(day, base, listOf(ExamDay("Calculus", day + 1))))
    }
}
