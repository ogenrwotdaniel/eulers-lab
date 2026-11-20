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
        val named = AppGate.KNOWN_GATED_PACKAGES + AppGate.KNOWN_BROWSERS - AppGate.GOOGLE_APP
        for (pkg in named) assertTrue("$pkg missing on the watch", "\"$pkg\"" in source)
        assertTrue(AppGate.GOOGLE_APP in source)
        // The Play Store is open on both since 2026-11-20.
        assertTrue(AppGate.PLAY_STORE !in AppGate.KNOWN_GATED_PACKAGES)
        assertTrue("PLAY_STORE,\n" !in source)
        // Gmail is spared the category rule on both (2026-11-20).
        for (pkg in AppGate.CATEGORY_SPARED) assertTrue("$pkg not spared on the watch", "\"$pkg\"" in source)
    }

    /** The watch's schedule is a hand copy too; a window change made on one side only would pass both modules' tests. */
    @Test
    fun `the watch's copy of the open window matches the phone's`() {
        val watch = File("../wear/src/main/java/com/ogenrwotdaniel/wakeproof/wear/walls/WatchWalls.kt")
        org.junit.Assume.assumeTrue("watch source not beside the phone module", watch.exists())
        val source = watch.readText()
        fun minutes(name: String): Int {
            val m = Regex("const val $name = (\\d+) \\* 60(?: \\+ (\\d+))?").find(source)
                ?: error("$name not found on the watch")
            return m.groupValues[1].toInt() * 60 + (m.groupValues[2].ifEmpty { "0" }).toInt()
        }
        val rules = com.ogenrwotdaniel.wakeproof.distraction.WallRules()
        assertEquals(rules.openWindowStartMinutes, minutes("OPEN_WINDOW_START_MINUTES"))
        assertEquals(rules.openWindowEndMinutes, minutes("OPEN_WINDOW_END_MINUTES"))
        assertEquals(rules.bedtimeStartMinutes, minutes("BEDTIME_START_MINUTES"))
    }
}
