package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchSessionImportTest {

    private val today = 20_376L

    @Test
    fun `a normal block is accepted as is`() {
        assertEquals(
            WatchFocusBlock("id-1", today - 3, 25, "Calculus"),
            WatchSessionImport.accept("id-1", today - 3, 25, "Calculus", today)
        )
    }

    @Test
    fun `a block without an id cannot be deduplicated and is rejected`() {
        assertNull(WatchSessionImport.accept(null, today, 25, null, today))
        assertNull(WatchSessionImport.accept(" ", today, 25, null, today))
    }

    @Test
    fun `zero or absurd minutes are rejected`() {
        assertNull(WatchSessionImport.accept("id", today, 0, null, today))
        assertNull(WatchSessionImport.accept("id", today, WatchSessionImport.MAX_BLOCK_MINUTES + 1, null, today))
    }

    @Test
    fun `tomorrow is a timezone, the day after is a wrong clock`() {
        assertEquals(today + 1, WatchSessionImport.accept("id", today + 1, 25, null, today)?.epochDay)
        assertNull(WatchSessionImport.accept("id", today + 2, 25, null, today))
        assertNull(WatchSessionImport.accept("id", 0L, 25, null, today))
    }

    @Test
    fun `a blank subject reads as untagged`() {
        assertNull(WatchSessionImport.accept("id", today, 25, "", today)!!.subject)
    }

    @Test
    fun `a block keeps its event title beside the course, cut and blank as none`() {
        val block = WatchSessionImport.accept("id", today, 25, "Materials", today, eventTitle = " Graphene seminar ")!!
        assertEquals("Materials", block.subject)
        assertEquals("Graphene seminar", block.eventTitle)
        assertNull(WatchSessionImport.accept("id", today, 25, null, today, eventTitle = " ")!!.eventTitle)
        assertEquals(80, WatchSessionImport.accept("id", today, 25, null, today, eventTitle = "z".repeat(200))!!.eventTitle!!.length)
    }

    @Test
    fun `the event keys match the watch's`() {
        // Hand-kept contract: wear TimerContract, WatchSessionContract, SessionContract.
        assertEquals("t_event_title", WearEventKeys.KEY_TIMER_EVENT_TITLE)
        assertEquals("ws_event_title", WearEventKeys.KEY_WS_EVENT_TITLE)
        assertEquals("s_event_title", WearEventKeys.KEY_SESSION_EVENT_TITLE)
    }
}
