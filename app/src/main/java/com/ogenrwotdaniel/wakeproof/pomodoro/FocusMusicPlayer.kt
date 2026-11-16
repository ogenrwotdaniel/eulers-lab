package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.music.NowPlaying
import com.ogenrwotdaniel.wakeproof.music.Track
import com.ogenrwotdaniel.wakeproof.music.playOrder
import com.ogenrwotdaniel.wakeproof.music.previousRestartsSong
import com.ogenrwotdaniel.wakeproof.music.queueIndexAfter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the study sound stands with Android's audio focus. */
enum class MusicFocus {
    /** Not asked for, given back, or lost for good. */
    NONE,
    HELD,
    /** Asked for during a call; Android grants it later with a GAIN. */
    DELAYED,
    /** Taken for now by a call, navigation or another app; a GAIN gives it back. */
    PAUSED,
    /**
     * Taken for good by another app. Not asked for again until the timer's next
     * wish (a new phase or setting), so a song ending cannot grab it back.
     */
    LOST
}

/** The one thing [musicStep] says to do next. */
enum class MusicStep { FADE_OUT, ASK_FOCUS, WAIT_FOR_FOCUS, CREATE_PLAYER, START, KEEP_PLAYING }

/**
 * The study sound's state machine, as a pure function of what is wanted and what
 * is held. Sound plays only when the phase wants it AND focus is held; until
 * focus is held nothing is created or started, so no sound ever plays over a
 * call or over the app that took focus away.
 */
fun musicStep(wanted: Boolean, focus: MusicFocus, hasPlayer: Boolean, started: Boolean): MusicStep = when {
    !wanted -> MusicStep.FADE_OUT
    focus == MusicFocus.NONE -> MusicStep.ASK_FOCUS
    focus != MusicFocus.HELD -> MusicStep.WAIT_FOR_FOCUS
    !hasPlayer -> MusicStep.CREATE_PLAYER
    !started -> MusicStep.START
    else -> MusicStep.KEEP_PLAYING
}

/**
 * Whether a new [next] source tears down what plays now. A new library
 * generation alone (access changed) restarts only when there is nothing worth
 * keeping ([canRestart]: no player yet, or the library had failed and the
 * ambient fallback plays); otherwise music that plays fine would jump back to a
 * reshuffled first song every time the permission was checked.
 */
fun sourceChangeRestarts(current: SoundSource?, next: SoundSource, canRestart: Boolean): Boolean {
    if (current == null) return true
    if (current is SoundSource.Library && next is SoundSource.Library &&
        current.copy(generation = next.generation) == next
    ) {
        return current.generation != next.generation && canRestart
    }
    return current != next
}

/**
 * Whether a fade-out scheduled at [scheduled] (a tick just before its phase
 * ends) still belongs to the phase running [now]. The music thread may get to it
 * late; once the phase has moved on, fading would silence the next phase.
 */
fun fadeOutStillDue(scheduled: PomodoroState, now: PomodoroState): Boolean =
    now.active && now.running &&
        now.phase == scheduled.phase &&
        now.completedFocusSessions == scheduled.completedFocusSessions &&
        now.remainingSeconds in 1..scheduled.remainingSeconds

/** Which phase of which session the timer is in; a change of it is a phase turning. */
data class PhaseKey(val active: Boolean, val phase: PomodoroPhase, val completedFocusSessions: Int)

/** This state's [PhaseKey]. */
fun PomodoroState.phaseKey() = PhaseKey(active, phase, completedFocusSessions)

/**
 * Whether the sound may play: the phase wants it, and the pre-rolled fade-out
 * has not already silenced the end of this very phase ([silencedPhase]).
 * Without the second part, anything that re-ran the state machine in the last
 * seconds of a phase (a settings write, a library change, a song ending)
 * brought the sound back up and carried it into the silent next phase.
 */
fun soundAllowed(wanted: Boolean, phase: PhaseKey?, silencedPhase: PhaseKey?): Boolean =
    wanted && (silencedPhase == null || silencedPhase != phase)

/**
 * Plays the study sound for [PomodoroTimerService]: a bundled loop or the user's
 * own songs, with fades, audio focus and the ambient fallback.
 *
 * Every field is touched only on its own thread. Preparing a song from a picked
 * folder or MediaStore and reading its tags block on a content provider; done on
 * the main thread, a slow provider froze the timer screen. Each entry point
 * posts there, and does nothing once [shutDown] has been called.
 */
class FocusMusicPlayer(
    private val context: Context,
    private val library: MusicLibrary,
    private val publishNowPlaying: (NowPlaying?) -> Unit
) {
    private enum class PlayerState { NONE, PREPARED, STARTED, PAUSED }

    private val thread = HandlerThread("focus-music").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())

    @Volatile private var shutDown = false

    // What the timer wants.
    private var wanted = false
    private var fadeSeconds = 0
    private var source: SoundSource? = null

    // What is.
    private var player: MediaPlayer? = null
    /** Tracked here: MediaPlayer cannot be asked, and pause() in the wrong state is an error. */
    private var playerState = PlayerState.NONE
    private var queue: List<Track> = emptyList()
    private var queueIndex = 0
    /** MediaPlayer has no getVolume, so the ramp tracks where it left off. */
    private var volume = 0f
    private var fadeJob: Job? = null
    private var createJob: Job? = null
    /** Bumped at every teardown, so a player created for an older source is thrown away. */
    private var epoch = 0
    private var focus = MusicFocus.NONE
    private var focusRequest: AudioFocusRequest? = null
    /** The phase the timer is in, as of its last wish. */
    private var phase: PhaseKey? = null
    /** The phase whose end the pre-rolled fade-out silenced; see [soundAllowed]. */
    private var silencedPhase: PhaseKey? = null
    /** The player fading out in [shutDown]; a focus loss silences it at once. */
    private var dyingPlayer: MediaPlayer? = null

    /**
     * The timer's wish: sound or not, from which source, fading over [fade]
     * seconds, in [phaseNow]. Sent on every change, phase turns included.
     * Never lifts the early end-of-phase silence: a wish can be built from a
     * tick before the fade-out and arrive after it. Only [phaseExtended] does.
     */
    fun want(shouldPlay: Boolean, next: SoundSource, fade: Int, phaseNow: PhaseKey) = post {
        fadeSeconds = fade
        phase = phaseNow
        val canRestart = player == null || isFallback()
        if (sourceChangeRestarts(source, next, canRestart)) teardown()
        // Every wish is a real change (the timer sends only changes): ask again.
        if (focus == MusicFocus.LOST) focus = MusicFocus.NONE
        source = next
        wanted = shouldPlay
        reconcile()
    }

    /**
     * A shared-session realign moved this phase's end later. [stillBeforeFade] is
     * asked on the music thread against the live timer, so only a phase that really
     * has more than the fade length left gets its sound back; otherwise the sound
     * would stay off for all the time the phase gained.
     */
    fun phaseExtended(extended: PhaseKey, stillBeforeFade: () -> Boolean) = post {
        // Only the phase that was silenced: a realign that jumps to a later phase
        // must not lift the old one's silence and replay its stale wish there.
        if (silencedPhase == extended && phase == extended && stillBeforeFade()) {
            silencedPhase = null
            reconcile()
        }
    }

    /** Next / previous song. Moves only the song, never the session. */
    fun skip(delta: Int) = post { skipTrack(delta) }

    /**
     * The pre-rolled fade-out before a phase whose sound is off. [stillDue] is
     * asked on the music thread, so a late run cannot fade the next phase.
     */
    fun fadeOutBeforePhaseEnd(stillDue: () -> Boolean) = post {
        if (!stillDue()) return@post
        // Marked whatever the player is doing: sound paused for a call or waiting
        // for delayed focus at this tick must not come back for the phase's last
        // seconds and carry on into the silent next phase.
        silencedPhase = phase
        if (playerState == PlayerState.STARTED) fadeOutThenPause()
    }

    /**
     * Ends playback for good: fades out, releases, gives focus back, then ends
     * the thread. Nothing posted afterwards creates or starts a player.
     */
    fun shutDown(fade: Int) {
        shutDown = true
        scope.launch {
            createJob?.cancel()
            fadeJob?.cancel()
            val dying = player
            val wasStarted = playerState == PlayerState.STARTED
            dyingPlayer = dying.takeIf { wasStarted }
            player = null
            playerState = PlayerState.NONE
            queue = emptyList()
            publishNowPlaying(null)
            if (dying != null) {
                if (wasStarted) ramp(dying, 0f, fade)
                dyingPlayer = null
                runCatching { dying.release() }
            }
            abandonFocus()
        }.invokeOnCompletion { thread.quitSafely() }
    }

    private fun post(block: () -> Unit) {
        scope.launch { if (!shutDown) block() }
    }

    private fun isFallback() = source is SoundSource.Library && player != null && queue.isEmpty()

    private fun peak() = if (source is SoundSource.Library && queue.isNotEmpty()) LIBRARY_VOLUME else LOOP_VOLUME

    /** The single place that turns wishes into playback; see [musicStep]. */
    private fun reconcile() {
        if (shutDown) return
        val allowed = soundAllowed(wanted, phase, silencedPhase)
        when (musicStep(allowed, focus, player != null, playerState == PlayerState.STARTED)) {
            MusicStep.FADE_OUT -> fadeOutThenPause()
            MusicStep.ASK_FOCUS -> if (requestFocus()) reconcile()
            MusicStep.WAIT_FOR_FOCUS -> Unit
            MusicStep.CREATE_PLAYER -> createPlayer()
            MusicStep.START -> {
                val p = player ?: return
                // Start from silence so the sound arrives under you rather than
                // snapping on at full level the instant the phase flips.
                p.applyVolume(if (fadeSeconds > 0) 0f else peak())
                if (runCatching { p.start() }.isSuccess) {
                    playerState = PlayerState.STARTED
                    rampTo(peak())
                } else {
                    onStartFailed()
                }
            }
            // Also cancels a fade-out in flight when the sound is wanted again.
            MusicStep.KEEP_PLAYING -> rampTo(peak())
        }
    }

    private fun rampTo(target: Float) {
        val p = player ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch { ramp(p, target, fadeSeconds) }
    }

    /**
     * Eases down to silence and only then pauses, so resuming picks the sound up
     * where it left off. Focus is given back either way: a song that had just
     * ended or a call holding it paused must not keep other apps quiet through
     * a silent phase.
     */
    private fun fadeOutThenPause() {
        fadeJob?.cancel()
        val p = player
        if (p == null || playerState != PlayerState.STARTED) {
            abandonFocus()
            return
        }
        fadeJob = scope.launch {
            ramp(p, 0f, fadeSeconds)
            // The song may have changed under the ramp; pause only what plays.
            if (p === player) pausePlayer()
            abandonFocus()
        }
    }

    /**
     * start() threw: the player is broken. A song gives way to the ambient loop;
     * a loop that will not start either is released with focus given back,
     * rather than a broken player being retried and focus held for no sound.
     */
    private fun onStartFailed() {
        if (source is SoundSource.Library && queue.isNotEmpty()) {
            fallBackToLoop()
            return
        }
        fadeJob?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        playerState = PlayerState.NONE
        abandonFocus()
    }

    private fun pausePlayer() {
        val p = player ?: return
        if (playerState != PlayerState.STARTED) return
        if (runCatching { p.pause() }.isSuccess) playerState = PlayerState.PAUSED
    }

    private fun teardown() {
        epoch++
        createJob?.cancel()
        fadeJob?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        playerState = PlayerState.NONE
        queue = emptyList()
        volume = 0f
        publishNowPlaying(null)
    }

    // ---- audio focus ----

    /** True once focus is held; DELAYED waits for the GAIN, a refusal waits for the next wish. */
    private fun requestFocus(): Boolean {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setWillPauseWhenDucked(true)
            // Asked during a call, focus comes later instead of never: without
            // this the phase stayed silent after the call ended.
            .setAcceptsDelayedFocusGain(true)
            // The request travels with each callback: one queued for a request
            // already given back must not change the state of the next one.
            .setOnAudioFocusChangeListener({ change -> onFocusChange(request, change) }, handler)
            .build()
        val result = runCatching {
            context.getSystemService(AudioManager::class.java).requestAudioFocus(request)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focus = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> MusicFocus.HELD
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> MusicFocus.DELAYED
            else -> MusicFocus.NONE
        }
        focusRequest = request.takeIf { focus != MusicFocus.NONE }
        return focus == MusicFocus.HELD
    }

    private fun abandonFocus() {
        focusRequest?.let { request ->
            runCatching { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) }
        }
        focusRequest = null
        focus = MusicFocus.NONE
    }

    private fun onFocusChange(from: AudioFocusRequest, change: Int) {
        if (from !== focusRequest) return
        val action = musicFocusAction(change, soundAllowed(wanted, phase, silencedPhase))
        if (shutDown) {
            // A call or another app took over while the sound fades out at the
            // end of the session: stop at once instead of playing over it.
            if (action == MusicFocusAction.PAUSE_FOR_NOW || action == MusicFocusAction.GIVE_UP) {
                dyingPlayer?.let { runCatching { it.pause() } }
                dyingPlayer = null
            }
            return
        }
        when (action) {
            MusicFocusAction.PAUSE_FOR_NOW -> {
                focus = MusicFocus.PAUSED
                fadeJob?.cancel()
                pausePlayer()
            }
            MusicFocusAction.GIVE_UP -> {
                fadeJob?.cancel()
                pausePlayer()
                abandonFocus()
                // Lost for good: the next wish for sound (a new phase) asks again.
                focus = MusicFocus.LOST
            }
            MusicFocusAction.RESUME -> {
                focus = MusicFocus.HELD
                reconcile()
            }
            MusicFocusAction.RELEASE -> abandonFocus()
            MusicFocusAction.IGNORE -> Unit
        }
    }

    // ---- creating players ----

    private fun createPlayer() {
        if (createJob?.isActive == true) return
        val forSource = source ?: return
        val forEpoch = epoch
        createJob = scope.launch {
            val created = create(forSource)
            // Shut down or a new source while the library was read: not ours to play.
            if (shutDown || forEpoch != epoch) {
                created?.player?.let { runCatching { it.release() } }
                return@launch
            }
            if (created == null) {
                abandonFocus()
                return@launch
            }
            install(created)
            reconcile()
        }
    }

    private class Created(val player: MediaPlayer, val queue: List<Track>, val index: Int)

    private fun install(created: Created) {
        player = created.player
        playerState = PlayerState.PREPARED
        queue = created.queue
        queueIndex = created.index
        created.player.applyVolume(volume)
        publishNowPlaying(created.queue.getOrNull(created.index)?.let(::nowPlayingFor))
    }

    /**
     * The player for [forSource]. The user's music falls back to the ambient loop
     * when not one song can be read (empty or vanished folder, permission
     * revoked, every file broken): the session asked for sound.
     */
    private suspend fun create(forSource: SoundSource): Created? = when (forSource) {
        is SoundSource.Loop -> createLoop(forSource.sound)
        is SoundSource.Library -> createLibrary(forSource) ?: createLoop(forSource.fallbackLoop)
    }

    private fun createLoop(sound: FocusSound): Created? =
        runCatching { MediaPlayer.create(context, sound.res, AUDIO_ATTRIBUTES, 0) }.getOrNull()
            ?.apply { isLooping = true }
            ?.let { Created(it, emptyList(), 0) }

    /**
     * One MediaPlayer for the whole queue, reset and re-pointed at each song:
     * the fade ramps hold a reference to the player, so swapping instances
     * mid-fade would leave the ramp turning a released player's volume.
     */
    private suspend fun createLibrary(forSource: SoundSource.Library): Created? {
        val tracks = try {
            library.tracksFor(MusicSelection.decode(forSource.selection))
        } catch (e: CancellationException) {
            // A teardown or shutdown cancelled the read; nothing may be built after it.
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (tracks.isEmpty() || shutDown) return null
        val order = playOrder(tracks, forSource.shuffle)
        // Created on the music thread, so its callbacks arrive there too.
        val mp = MediaPlayer()
        mp.setOnCompletionListener { advance(it) }
        // A file that will not play is skipped, not fatal. Returning true stops
        // MediaPlayer from also firing onCompletion for the broken one.
        mp.setOnErrorListener { failed, _, _ ->
            advance(failed)
            true
        }
        val index = prepareFrom(mp, order, 0)
        if (index < 0) {
            runCatching { mp.release() }
            return null
        }
        return Created(mp, order, index)
    }

    // ---- the queue ----

    /**
     * Points [mp] at [order]'s entry [from] (wrapping), skipping any file that
     * will not prepare. The index that prepared, or -1 when none does. Blocks on
     * the content provider; music thread only.
     */
    private fun prepareFrom(mp: MediaPlayer, order: List<Track>, from: Int): Int {
        repeat(order.size) { attempt ->
            if (shutDown) return -1
            val i = queueIndexAfter(from, attempt, order.size)
            val prepared = runCatching {
                mp.reset()
                mp.setAudioAttributes(AUDIO_ATTRIBUTES)
                mp.setDataSource(context, order[i].uri)
                mp.prepare()
            }.isSuccess
            if (prepared) {
                // reset() drops the volume back to full; carry the fade's level.
                runCatching { mp.setVolume(volume, volume) }
                return i
            }
        }
        return -1
    }

    /** Loads queue entry [index] into the current player; false when no song plays. */
    private fun loadTrack(index: Int): Boolean {
        val p = player ?: return false
        playerState = PlayerState.NONE
        val i = prepareFrom(p, queue, index)
        if (i < 0 || shutDown) return false
        queueIndex = i
        playerState = PlayerState.PREPARED
        publishNowPlaying(nowPlayingFor(queue[i]))
        return true
    }

    /**
     * The next song once one ends or fails. It starts only while the phase wants
     * sound and focus is held; a song ending on the focus-to-break boundary used
     * to start the next one through a silent break. A fade-out in flight carries
     * on over the new song.
     */
    private fun advance(mp: MediaPlayer) {
        if (shutDown || mp !== player) return
        if (!loadTrack(queueIndex + 1)) {
            // A load cut short by shutdown is not "nothing plays".
            if (!shutDown) fallBackToLoop()
            return
        }
        if (soundAllowed(wanted, phase, silencedPhase) && focus == MusicFocus.HELD) {
            if (runCatching { mp.start() }.isSuccess) playerState = PlayerState.STARTED
            else fallBackToLoop()
        }
    }

    /**
     * No song in the queue plays any more (the folder was deleted or its grant
     * revoked mid-session): the ambient loop takes over at the same level, and
     * [reconcile] decides whether it may sound (phase and focus).
     */
    private fun fallBackToLoop() {
        val current = source ?: return
        fadeJob?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        playerState = PlayerState.NONE
        queue = emptyList()
        publishNowPlaying(null)
        val loop = createLoop(current.fallbackLoop)
        if (loop == null) {
            abandonFocus()
            return
        }
        install(loop)
        reconcile()
    }

    private fun skipTrack(delta: Int) {
        if (shutDown) return
        val p = player ?: return
        if (source !is SoundSource.Library || queue.isEmpty()) return
        val wasStarted = playerState == PlayerState.STARTED
        if (delta < 0 && previousRestartsSong(runCatching { p.currentPosition }.getOrDefault(0))) {
            runCatching { p.seekTo(0) }
            return
        }
        // loadTrack has already tried every song; failing means none plays
        // (unless shutdown cut it short).
        if (!loadTrack(queueIndex + delta)) {
            if (!shutDown) fallBackToLoop()
            return
        }
        if (!wasStarted) return
        if (runCatching { p.start() }.isSuccess) playerState = PlayerState.STARTED
        else fallBackToLoop()
    }

    /** A song from a picked folder only knows its file name until it plays; read its tags now. */
    private fun nowPlayingFor(track: Track): NowPlaying {
        if (track.documentUri == null) return NowPlaying(track.title, track.artist)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri)
            NowPlaying(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: track.title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() } ?: ""
            )
        } catch (_: Exception) {
            NowPlaying(track.title, "")
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- volume ----

    private fun MediaPlayer.applyVolume(v: Float) {
        volume = v
        runCatching { setVolume(v, v) }
    }

    /**
     * Walks the volume to [to] over [seconds] on a curve: the ear hears loudness
     * roughly logarithmically, so squaring the progress spends more of the ramp
     * down low, which reads as a smooth fade.
     */
    private suspend fun ramp(p: MediaPlayer, to: Float, seconds: Int) {
        val from = volume
        // Nowhere to go: done at once. Comparing exactly, a fade-out repeated at
        // the phase boundary crawled from 0.0001 to 0 for the whole fade length.
        if (seconds <= 0 || kotlin.math.abs(from - to) < 0.001f) {
            p.applyVolume(to)
            return
        }
        val steps = seconds * FADE_STEPS_PER_SECOND
        val stepMillis = 1000L / FADE_STEPS_PER_SECOND
        for (step in 1..steps) {
            delay(stepMillis)
            val progress = step.toFloat() / steps
            val eased = if (to > from) progress * progress else 1f - (1f - progress) * (1f - progress)
            p.applyVolume(from + (to - from) * eased)
        }
    }

    companion object {
        /**
         * Playback level for the bundled loops, levelled at a matched -14 LUFS
         * by `tools/sounds/generate.py`.
         */
        private const val LOOP_VOLUME = 0.7f
        /** Commercial masters already sit loud; the media volume rocker is the user's control. */
        private const val LIBRARY_VOLUME = 0.85f
        /** Ramp resolution. 20 Hz is well under the threshold where steps are audible. */
        private const val FADE_STEPS_PER_SECOND = 20

        private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
