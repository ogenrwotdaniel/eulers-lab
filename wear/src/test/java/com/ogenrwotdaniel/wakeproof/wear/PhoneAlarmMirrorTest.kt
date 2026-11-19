package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAlarmMirrorTest {

    private val now = 10_000_000L

    @Test
    fun `a fresh ring buzzes, a stopped one does not`() {
        assertTrue(phoneAlarmBuzzes(ringing = true, startedAt = now - 5_000L, now = now))
        assertFalse(phoneAlarmBuzzes(ringing = false, startedAt = now - 5_000L, now = now))
    }

    @Test
    fun `a ring heard long after it started is not buzzed`() {
        assertFalse(phoneAlarmBuzzes(true, now - PHONE_ALARM_STALE_MILLIS, now))
        assertFalse(phoneAlarmBuzzes(true, 0L, now))
    }

    @Test
    fun `the buzz ends on its own if the phone never says it stopped`() {
        val pattern = phoneAlarmBuzzPattern()
        val total = pattern.sum()
        assertTrue(total <= PHONE_ALARM_BUZZ_MILLIS)
        assertTrue(total > PHONE_ALARM_BUZZ_MILLIS - 2_000L)
        assertEquals(0, pattern.size % 2)
    }

    @Test
    fun `the reading alarm taps lightly, and still ends on its own`() {
        val gentle = phoneAlarmBuzzPattern(gentle = true)
        assertTrue(gentle.sum() in (PHONE_ALARM_BUZZ_MILLIS - 2_000L)..PHONE_ALARM_BUZZ_MILLIS)
        // Pulses are the odd entries; each is short.
        assertTrue(gentle.filterIndexed { i, _ -> i % 2 == 1 }.all { it < 500L })
        // The contract key matches the phone's WearAlarmSync.KEY_GENTLE.
        assertEquals("a_gentle", PhoneAlarmContract.KEY_GENTLE)
    }
}
