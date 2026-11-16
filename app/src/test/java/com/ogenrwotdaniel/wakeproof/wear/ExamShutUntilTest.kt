package com.ogenrwotdaniel.wakeproof.wear

import com.ogenrwotdaniel.wakeproof.distraction.AppGate
import com.ogenrwotdaniel.wakeproof.distraction.ExamWeek
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamShutUntilTest {

    @Test
    fun `no exam week tells the watch nothing is held`() {
        assertEquals(0L, examShutUntilMillis(null, ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `an exam week holds the watch through the whole exam day`() {
        val zone = ZoneId.of("Asia/Seoul")
        val exam = LocalDate.of(2026, 12, 10)
        val until = examShutUntilMillis(ExamWeek("Thermodynamics", exam.toEpochDay()), zone)
        assertEquals(LocalDate.of(2026, 12, 11).atStartOfDay(zone).toInstant().toEpochMilli(), until)
    }

    /**
     * The watch keeps a hand copy of the gated list (`:wear` walls/WatchWalls.kt);
     * there is no shared module, so this catches a phone addition the watch missed.
     */
    @Test
    fun `the watch's copy names every package the phone gates`() {
        val watch = File("../wear/src/main/java/com/ogenrwotdaniel/wakeproof/wear/walls/WatchWalls.kt")
        org.junit.Assume.assumeTrue("watch source not beside the phone module", watch.exists())
        val source = watch.readText()
        val named = AppGate.KNOWN_GATED_PACKAGES + AppGate.KNOWN_BROWSERS - AppGate.GOOGLE_APP - AppGate.PLAY_STORE
        for (pkg in named) assertTrue("$pkg missing on the watch", "\"$pkg\"" in source)
        assertTrue(AppGate.GOOGLE_APP in source && AppGate.PLAY_STORE in source)
    }
}
