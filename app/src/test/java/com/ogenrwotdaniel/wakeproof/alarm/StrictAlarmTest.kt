package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictAlarmTest {

    private fun alarm(
        id: Long,
        type: AlarmType = AlarmType.PUSHUP,
        hour: Int = 6,
        minute: Int = 5,
        enabled: Boolean = true,
        deleted: Boolean = false,
        daysMask: Int = 127,
        label: String = ""
    ) = Alarm(
        id = id, type = type, hour = hour, minute = minute, enabled = enabled, label = label,
        deleted = deleted, daysMask = daysMask, syncId = "s$id", updatedAt = 1L, pending = false
    )

    @Test
    fun `an edited, disabled or deleted locked alarm is written back, restamped`() {
        val slot = StrictAlarm.SLOTS[0]
        val fixed = strictAlarmFixed(
            alarm(1, type = AlarmType.MATH, hour = 7, enabled = false, deleted = true, daysMask = 31),
            slot,
            now = 99L
        )!!
        assertEquals(4, fixed.hour)
        assertEquals(0, fixed.minute)
        assertEquals(AlarmType.PUSHUP, fixed.type)
        assertTrue(fixed.enabled)
        assertFalse(fixed.deleted)
        assertEquals(StrictAlarm.DAYS_MASK, fixed.daysMask)
        assertEquals(99L, fixed.updatedAt)
        assertTrue(fixed.pending)
    }

    @Test
    fun `a locked alarm already as it must be is left alone`() {
        assertNull(strictAlarmFixed(alarm(1, hour = 4, minute = 0), StrictAlarm.SLOTS[0], now = 99L))
    }

    @Test
    fun `a locked alarm from the old 03 00 slot moves to 04 00 with its label, a custom label stays`() {
        val moved = strictAlarmFixed(alarm(1, hour = 3, minute = 0, label = "Locked 3:00"), StrictAlarm.SLOTS[0], 99L)!!
        assertEquals(4, moved.hour)
        assertEquals(0, moved.minute)
        assertEquals("Locked 4:00", moved.label)
        val custom = strictAlarmFixed(alarm(2, hour = 6, minute = 5, label = "Up!"), StrictAlarm.SLOTS[0], 99L)!!
        assertEquals("Up!", custom.label)
        // A label written for the removed 05:40 slot is still known as WakeProof's own.
        assertTrue(isStrictLabel("Locked 5:40"))
    }

    @Test
    fun `the 04 00 pushups are pinned under key 0, and dropped keys are never reused`() {
        assertEquals(
            listOf(StrictAlarm.Slot(4, 0)),
            StrictAlarm.SLOTS
        )
        assertEquals(listOf(0), StrictAlarm.PINNED.map { it.index })
        assertTrue(StrictAlarm.PINNED.none { it.index in StrictAlarm.DROPPED_KEYS })
        // 05:40 pushups, 08:20 day plan, 19:30 review, and 19:40 top three: their rows are deleted, their keys never reused.
        assertEquals(listOf(1, 2, 3, 4), StrictAlarm.DROPPED_KEYS)
        // An old copy of the 03:00 or 05:40 pushups synced back stays silent.
        assertTrue(3 * 60 in StrictAlarm.silentMinutes(AlarmType.PUSHUP))
        assertTrue(5 * 60 + 40 in StrictAlarm.silentMinutes(AlarmType.PUSHUP))
        assertTrue(isStrictLabel("Locked 19:40"))
        assertTrue(isStrictLabel("Locked 19:50"))
    }

    @Test
    fun `day-plan and shutdown alarms are retired like water`() {
        assertTrue(AlarmType.PLAN.retired)
        assertTrue(AlarmType.SHUTDOWN.retired)
        val rows = listOf(
            alarm(1, type = AlarmType.SHUTDOWN, hour = 19, minute = 30),
            alarm(2, type = AlarmType.SHUTDOWN, hour = 19, minute = 40),
            alarm(3, type = AlarmType.SHUTDOWN, hour = 21, minute = 0)
        )
        assertEquals(listOf(1L, 2L, 3L), retiredAlarms(rows).map { it.id })
        assertEquals(listOf(1L), retiredAlarms(listOf(alarm(1, type = AlarmType.PLAN, hour = 8, minute = 20), alarm(2))).map { it.id })
    }

    @Test
    fun `live water alarms are retired, tombstones are not`() {
        val rows = listOf(
            alarm(1, type = AlarmType.WATER, hour = 12, minute = 50),
            alarm(2, type = AlarmType.WATER, hour = 9, minute = 0),
            alarm(3, type = AlarmType.WATER, deleted = true),
            alarm(4, type = AlarmType.WATER, enabled = false),
            alarm(5), alarm(6, type = AlarmType.MATH), alarm(7, type = AlarmType.REVIEW)
        )
        assertEquals(listOf(1L, 2L, 4L), retiredAlarms(rows).map { it.id })
    }

    @Test
    fun `the watch backs up the one locked alarm, and the night runs to it`() {
        assertEquals(listOf(4 * 60), StrictAlarm.hardMinutes)
        assertEquals(4 * 60, StrictAlarm.lastSlotMinute)
        assertEquals(emptySet<Int>(), StrictAlarm.silentMinutes(AlarmType.MATH))
    }

    @Test
    fun `the earliest live pushup alarm is adopted`() {
        val chosen = strictAlarmCandidate(
            listOf(
                alarm(1, hour = 4, deleted = true),
                alarm(2, type = AlarmType.MATH, hour = 1),
                alarm(3, hour = 6),
                alarm(4, hour = 5, minute = 30)
            ),
            StrictAlarm.SLOTS[0]
        )
        assertEquals(4L, chosen?.id)
    }

    @Test
    fun `a slot never adopts another kind of alarm, nor one already locked`() {
        assertNull(strictAlarmCandidate(listOf(alarm(2, type = AlarmType.MATH, hour = 8)), StrictAlarm.SLOTS[0]))
        assertNull(strictAlarmCandidate(listOf(alarm(1, enabled = false)), StrictAlarm.SLOTS[0]))
        assertEquals(
            3L,
            strictAlarmCandidate(listOf(alarm(1, hour = 4), alarm(3, hour = 6)), StrictAlarm.SLOTS[0], taken = setOf("s1"))?.id
        )
    }

    @Test
    fun `an unlocked pushup alarm on a locked slot is a duplicate, nothing else is`() {
        val rows = listOf(
            alarm(1, hour = 4, minute = 0), alarm(2, hour = 4, minute = 0), alarm(3, hour = 5, minute = 40),
            alarm(4, hour = 4, minute = 0, type = AlarmType.MATH), alarm(5, hour = 6, minute = 0),
            alarm(6, hour = 4, minute = 0, deleted = true),
            // The old locked pairs, synced back from before a move, never ring either.
            alarm(7, hour = 3, minute = 0), alarm(8, hour = 5, minute = 30), alarm(9, hour = 6, minute = 5),
            // A pushup alarm at 08:20 is the user's own.
            alarm(11, hour = 8, minute = 20)
        )
        assertEquals(listOf(2L, 3L, 7L, 8L, 9L), strictAlarmDuplicates(rows, setOf("s1")).map { it.id })
        assertEquals(emptyList<Long>(), strictAlarmDuplicates(rows, emptySet()).map { it.id })
    }

    @Test
    fun `the one morning alarm is 04 00, at 10 pushups`() {
        assertEquals(listOf(StrictAlarm.Slot(4, 0)), StrictAlarm.SLOTS.filter { it.hard })
        assertEquals(10, StrictAlarm.REPS)
    }

    @Test
    fun `a cut-off ring resumes within the day, not after it`() {
        val now = 100L * StrictAlarm.RESUME_WITHIN_MILLIS
        assertTrue(shouldResumeRing(5L, now - 60_000L, now))
        assertFalse(shouldResumeRing(5L, now - StrictAlarm.RESUME_WITHIN_MILLIS, now))
        assertFalse(shouldResumeRing(-1L, now, now))
        assertFalse(shouldResumeRing(5L, 0L, now))
    }

    @Test
    fun `a ring stamped in the future still resumes`() {
        assertTrue(shouldResumeRing(5L, 2_000L, 1_000L))
    }
}
