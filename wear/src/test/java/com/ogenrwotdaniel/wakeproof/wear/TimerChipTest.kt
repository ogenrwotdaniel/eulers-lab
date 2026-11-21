package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerChipTest {

    @Test
    fun `a wall-clock finish time lands on the elapsedRealtime scale the chip counts on`() {
        val nowWall = 1_795_000_000_000L // 2026-11
        val nowElapsed = 3_600_000L // an hour after boot
        val finish = nowWall + 25 * 60_000L
        // 25 minutes left, counted from the boot clock: not 57 years.
        assertEquals(nowElapsed + 25 * 60_000L, wallToElapsedRealtime(finish, nowWall, nowElapsed))
    }
}
