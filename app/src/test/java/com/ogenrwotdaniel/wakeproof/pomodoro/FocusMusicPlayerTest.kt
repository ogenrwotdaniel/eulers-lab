package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMusicPlayerTest {

    @Test
    fun `no sound wanted always fades out, whatever focus is`() {
        MusicFocus.entries.forEach {
            assertEquals(MusicStep.FADE_OUT, musicStep(wanted = false, focus = it, hasPlayer = true, started = true))
        }
    }

    @Test
    fun `nothing is created or started until focus is held`() {
        assertEquals(MusicStep.ASK_FOCUS, musicStep(true, MusicFocus.NONE, hasPlayer = false, started = false))
        listOf(MusicFocus.DELAYED, MusicFocus.PAUSED, MusicFocus.LOST).forEach {
            assertEquals(MusicStep.WAIT_FOR_FOCUS, musicStep(true, it, hasPlayer = false, started = false))
            assertEquals(MusicStep.WAIT_FOR_FOCUS, musicStep(true, it, hasPlayer = true, started = false))
        }
    }

    @Test
    fun `with focus held a missing player is made, a stopped one started`() {
        assertEquals(MusicStep.CREATE_PLAYER, musicStep(true, MusicFocus.HELD, hasPlayer = false, started = false))
        assertEquals(MusicStep.START, musicStep(true, MusicFocus.HELD, hasPlayer = true, started = false))
        assertEquals(MusicStep.KEEP_PLAYING, musicStep(true, MusicFocus.HELD, hasPlayer = true, started = true))
    }

    private val music = SoundSource.Library("all", shuffle = true, generation = 0)

    @Test
    fun `a new generation keeps music that plays, restarts a fallback or nothing`() {
        val bumped = music.copy(generation = 1)
        assertFalse(sourceChangeRestarts(music, bumped, canRestart = false))
        assertTrue(sourceChangeRestarts(music, bumped, canRestart = true))
        assertFalse(sourceChangeRestarts(music, music, canRestart = true))
    }

    @Test
    fun `a real change of source always restarts`() {
        assertTrue(sourceChangeRestarts(music, music.copy(selection = "artist:X"), canRestart = false))
        assertTrue(sourceChangeRestarts(music, music.copy(shuffle = false), canRestart = false))
        assertTrue(sourceChangeRestarts(music, SoundSource.Loop(FocusSound.RAIN), canRestart = false))
        assertTrue(sourceChangeRestarts(null, music, canRestart = false))
        assertFalse(
            sourceChangeRestarts(SoundSource.Loop(FocusSound.RAIN), SoundSource.Loop(FocusSound.RAIN), false)
        )
    }

    private val tick = PomodoroState(
        active = true, running = true, phase = PomodoroPhase.FOCUS,
        remainingSeconds = 5, completedFocusSessions = 2
    )

    @Test
    fun `a pre-rolled fade-out runs only in the phase it was scheduled for`() {
        assertTrue(fadeOutStillDue(tick, tick))
        assertTrue(fadeOutStillDue(tick, tick.copy(remainingSeconds = 3)))
        // The music thread got there after the phase turned.
        assertFalse(fadeOutStillDue(tick, tick.copy(phase = PomodoroPhase.SHORT_BREAK, remainingSeconds = 300)))
        assertFalse(fadeOutStillDue(tick, tick.copy(completedFocusSessions = 3)))
        assertFalse(fadeOutStillDue(tick, tick.copy(remainingSeconds = 0)))
        assertFalse(fadeOutStillDue(tick, PomodoroState()))
    }

    @Test
    fun `a pre-rolled fade-out keeps this phase silent until the phase turns`() {
        val focusPhase = tick.phaseKey()
        val breakPhase = tick.copy(phase = PomodoroPhase.SHORT_BREAK, remainingSeconds = 300).phaseKey()
        assertTrue(soundAllowed(wanted = true, phase = focusPhase, silencedPhase = null))
        assertFalse(soundAllowed(wanted = true, phase = focusPhase, silencedPhase = focusPhase))
        assertTrue(soundAllowed(wanted = true, phase = breakPhase, silencedPhase = focusPhase))
        assertFalse(soundAllowed(wanted = false, phase = breakPhase, silencedPhase = null))
    }

    @Test
    fun `the phase key changes with the phase, not with the countdown`() {
        assertEquals(tick.phaseKey(), tick.copy(remainingSeconds = 1).phaseKey())
        assertTrue(tick.phaseKey() != tick.copy(completedFocusSessions = 3).phaseKey())
        assertTrue(tick.phaseKey() != tick.copy(active = false).phaseKey())
    }
}
