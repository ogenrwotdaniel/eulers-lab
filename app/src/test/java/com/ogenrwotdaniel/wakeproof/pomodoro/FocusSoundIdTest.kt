package com.ogenrwotdaniel.wakeproof.pomodoro

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSoundIdTest {

    @Test
    fun `every sound resolves by id`() {
        FocusSound.entries.forEach {
            assertEquals(it, FocusSound.fromId(it.id))
        }
    }

    @Test
    fun `retired or unknown ids fall back to ambient`() {
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("spotify"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("nonsense"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId(null))
    }

    @Test
    fun `the 1_5 own-music id brings the music player back`() {
        assertEquals(FocusSound.MY_MUSIC, FocusSound.fromId("library"))
        assertEquals("library", FocusSound.normalizedId("library"))
        assertTrue(FocusSound.MY_MUSIC.ownMusic)
    }

    @Test
    fun `spotify is migrated to ambient, never to own music`() {
        assertEquals(FocusSound.AMBIENT.id, FocusSound.normalizedId("spotify"))
    }

    @Test
    fun `own music falls back to the ambient loop and only it is not a bundled loop`() {
        assertEquals(FocusSound.AMBIENT.res, FocusSound.MY_MUSIC.res)
        assertEquals(listOf(FocusSound.MY_MUSIC), FocusSound.entries.filter { it.ownMusic })
        FocusSound.entries.filter { it != FocusSound.MY_MUSIC }.forEach { assertFalse(it.ownMusic) }
    }
}

class SoundSourceTest {

    @Test
    fun `own music plays the library with its selection and shuffle`() {
        assertEquals(
            SoundSource.Library("album:7:Divenire", shuffle = false),
            soundSourceFor("library", "album:7:Divenire", false)
        )
    }

    @Test
    fun `loops and retired ids play a loop`() {
        assertEquals(SoundSource.Loop(FocusSound.RAIN), soundSourceFor("night_rain", "all", true))
        assertEquals(SoundSource.Loop(FocusSound.AMBIENT), soundSourceFor("spotify", "all", true))
        assertEquals(SoundSource.Loop(FocusSound.AMBIENT), soundSourceFor(null, "all", true))
    }

    @Test
    fun `music that cannot play falls back to the ambient loop, a loop to itself`() {
        assertEquals(FocusSound.AMBIENT, SoundSource.Library("tree:gone", true).fallbackLoop)
        assertEquals(FocusSound.FIREPLACE, SoundSource.Loop(FocusSound.FIREPLACE).fallbackLoop)
    }

    @Test
    fun `a new library generation is a new source, but never restarts a loop`() {
        assertNotEquals(soundSourceFor("library", "all", true, 0), soundSourceFor("library", "all", true, 1))
        assertEquals(soundSourceFor("night_rain", "all", true, 0), soundSourceFor("night_rain", "all", true, 5))
    }
}

class MusicFocusActionTest {

    @Test
    fun `calls and other apps pause the sound for now`() {
        assertEquals(MusicFocusAction.PAUSE_FOR_NOW, musicFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true))
        assertEquals(
            MusicFocusAction.PAUSE_FOR_NOW,
            musicFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, true)
        )
    }

    @Test
    fun `a permanent loss gives focus up`() {
        assertEquals(MusicFocusAction.GIVE_UP, musicFocusAction(AudioManager.AUDIOFOCUS_LOSS, true))
        assertEquals(MusicFocusAction.GIVE_UP, musicFocusAction(AudioManager.AUDIOFOCUS_LOSS, false))
    }

    @Test
    fun `focus back resumes only while the phase wants sound`() {
        assertEquals(MusicFocusAction.RESUME, musicFocusAction(AudioManager.AUDIOFOCUS_GAIN, true))
        assertEquals(MusicFocusAction.RELEASE, musicFocusAction(AudioManager.AUDIOFOCUS_GAIN, false))
        assertEquals(MusicFocusAction.IGNORE, musicFocusAction(12345, true))
    }
}
