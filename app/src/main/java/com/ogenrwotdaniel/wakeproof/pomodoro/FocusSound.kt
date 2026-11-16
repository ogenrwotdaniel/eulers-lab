package com.ogenrwotdaniel.wakeproof.pomodoro

import androidx.annotation.RawRes
import com.ogenrwotdaniel.wakeproof.R

/**
 * The study sounds: the bundled ambient loops, plus [MY_MUSIC], the user's own
 * songs played by the timer itself (see `music/MusicLibrary.kt`).
 *
 * Generated assets — `tools/sounds/generate.py` renders them and that script,
 * not this file, is where a sound is changed. Two things about them matter here:
 *
 * * [id] is simultaneously the raw resource name and the value stored in the
 *   `focusSoundId` setting, so a typo falls back to [AMBIENT] silently rather
 *   than failing loudly.
 * * There is deliberately **no per-sound gain**. The generator levels every
 *   loop to the same K-weighted loudness. The five hand-tuned gains that used
 *   to live here were levelling by peak and missed by up to 5 dB, which is why
 *   switching sound used to be a jump in level.
 *
 * [description] is what the picker shows under the name; these are noise beds,
 * and "Brown noise" tells most people nothing about whether they want it.
 *
 * [res] is the loop that actually plays. For [MY_MUSIC] it is the ambient loop,
 * the fallback when no song can be read (an empty or vanished folder, a revoked
 * permission): a session that asked for sound gets sound, not silence.
 */
enum class FocusSound(
    val id: String,
    val label: String,
    val description: String,
    @param:RawRes val res: Int,
    /** True for the user's own music rather than a bundled loop. */
    val ownMusic: Boolean = false
) {
    AMBIENT(
        "focus_ambient",
        "Ambient tones",
        "A slow, wide chord with nowhere to resolve to",
        R.raw.focus_ambient
    ),
    RAIN(
        "night_rain",
        "Night rain",
        "Steady rain from indoors, with the odd close drop",
        R.raw.night_rain
    ),
    FIREPLACE(
        "fireplace",
        "Fireplace",
        "Low roar and crackles, a couple of metres away",
        R.raw.fireplace
    ),
    OCEAN(
        "ocean_waves",
        "Ocean waves",
        "Swells that brighten as they break, then withdraw",
        R.raw.ocean_waves
    ),
    WIND(
        "forest_wind",
        "Wind in the pines",
        "Gusts hissing through needles — movement without events",
        R.raw.forest_wind
    ),
    DEEP(
        "deep_noise",
        "Deep noise",
        "Dark brown noise. The most neutral thing to work under",
        R.raw.deep_noise
    ),
    STATIC(
        "soft_static",
        "Soft static",
        "Pink noise with the fizz taken off. Good against chatter",
        R.raw.soft_static
    ),

    /**
     * The user's own songs from this phone, played inside Euler's Lab. Its id is
     * the one 1.5.0 stored, so a choice saved then comes back as music.
     */
    MY_MUSIC(
        "library",
        "My music",
        "Your own songs from this phone, played here in Euler's Lab. Nothing is uploaded",
        R.raw.focus_ambient,
        ownMusic = true
    );

    companion object {
        /**
         * Rewrites Spotify's retired id to the bundled ambient loop. Spotify was
         * removed for good on 2026-11-14 and no outside player replaces it, so a
         * stored "spotify" must not read as [MY_MUSIC] (which would quietly start
         * reading the user's files when they never chose that).
         */
        fun normalizedId(id: String?): String =
            when (id) {
                "spotify", null -> AMBIENT.id
                else -> id
            }

        /** The bundled loop for [id], falling back to [AMBIENT] for any unknown id. */
        fun fromId(id: String?): FocusSound =
            entries.firstOrNull { it.id == normalizedId(id) } ?: AMBIENT
    }
}

/**
 * The one sound source in play: a bundled loop, or the user's own music. Exactly
 * one, so picking music silences the loop instead of stacking a second track on it.
 */
sealed interface SoundSource {
    data class Loop(val sound: FocusSound) : SoundSource

    /**
     * [selection] is an encoded `MusicSelection`; a change to any field rebuilds
     * the queue. [generation] is `MusicLibrary.generation`: bumped when access
     * changes (READ_MEDIA_AUDIO granted, a folder picked again), so a session
     * that fell back to the ambient loop retries the music without the
     * selection itself having changed.
     */
    data class Library(val selection: String, val shuffle: Boolean, val generation: Int = 0) : SoundSource

    /**
     * The bundled loop this source plays when it cannot play itself. For music
     * that is the ambient loop: a folder that has gone, a revoked permission or
     * a library with no songs would otherwise leave a session silent.
     */
    val fallbackLoop: FocusSound
        get() = when (this) {
            is Loop -> sound
            is Library -> FocusSound.AMBIENT
        }
}

/** The source the settings ask for: [FocusSound.MY_MUSIC] plays the library, anything else its loop. */
fun soundSourceFor(
    soundId: String?,
    musicSelection: String,
    musicShuffle: Boolean,
    libraryGeneration: Int = 0
): SoundSource {
    val sound = FocusSound.fromId(soundId)
    return if (sound.ownMusic) {
        SoundSource.Library(musicSelection, musicShuffle, libraryGeneration)
    } else {
        // A loop ignores the library's generation, so a permission change
        // never restarts a rain loop.
        SoundSource.Loop(sound)
    }
}

/** What the study sound does on an audio-focus change; see [musicFocusAction]. */
enum class MusicFocusAction { PAUSE_FOR_NOW, GIVE_UP, RESUME, RELEASE, IGNORE }

/**
 * A call, navigation prompt or another app's transient focus pauses the sound
 * until focus comes back ([PAUSE_FOR_NOW]); a permanent loss pauses it and
 * lets focus go ([GIVE_UP]), and the next phase that wants sound asks again.
 * Neither touches the session. Focus coming back resumes only while the phase
 * still wants sound; otherwise it is handed straight back ([RELEASE]).
 * Ducking counts as a pause: the request sets `setWillPauseWhenDucked(true)`.
 */
fun musicFocusAction(change: Int, soundWanted: Boolean): MusicFocusAction = when (change) {
    android.media.AudioManager.AUDIOFOCUS_LOSS -> MusicFocusAction.GIVE_UP
    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MusicFocusAction.PAUSE_FOR_NOW
    android.media.AudioManager.AUDIOFOCUS_GAIN ->
        if (soundWanted) MusicFocusAction.RESUME else MusicFocusAction.RELEASE
    else -> MusicFocusAction.IGNORE
}
