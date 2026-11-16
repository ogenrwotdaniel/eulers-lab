package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [blockLabels] and [eventTitleOf]: a block keeps its course, and the event beside it. */
class SessionSubjectTest {
    @Test
    fun `an event session keeps the course and records the event apart`() {
        assertEquals(
            BlockLabels("Mechanical Engineering", "Thermodynamics lecture"),
            blockLabels("  Thermodynamics lecture ", null, null, "Mechanical Engineering")
        )
    }

    @Test
    fun `a follower records the origin's course and event`() {
        assertEquals(
            BlockLabels("Deep Learning", "CV reading group"),
            blockLabels(null, "Deep Learning", "CV reading group", "Mechanical Engineering")
        )
    }

    @Test
    fun `blank labels fall back to the active course and no event`() {
        assertEquals(BlockLabels("Mechanical Engineering", null), blockLabels("  ", " ", " ", "Mechanical Engineering"))
        assertEquals(BlockLabels("Materials", null), blockLabels(null, null, null, "Materials"))
    }

    @Test
    fun `this device's own event wins over a followed one`() {
        assertEquals("Own", blockLabels("Own", null, "Followed", "a").eventTitle)
    }

    @Test
    fun `a long title is cut to the kept length and a blank one is none`() {
        assertEquals(EVENT_TITLE_MAX_CHARS, eventTitleOf("x".repeat(500))!!.length)
        assertNull(eventTitleOf("   "))
        assertNull(eventTitleOf(null))
    }
}
