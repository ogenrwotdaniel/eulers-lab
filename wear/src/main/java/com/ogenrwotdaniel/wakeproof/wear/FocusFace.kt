package com.ogenrwotdaniel.wakeproof.wear

/**
 * Pure decisions behind the watch screens (2026-11-15, the Compose rebuild):
 * what the Focus page shows for the watch's own timer or the phone's, which
 * controls it offers, and the Progress page's ring. No Android, no clock of its
 * own; unit-tested in `FocusFaceTest`. The composables only draw these.
 *
 * The controls are exactly the ones the framework-view screen had before the
 * rebuild. Nothing here may add a way to pause, stop or reset a session: there
 * is none (nuclear focus, as on the phone).
 */

/** Where a running session's countdown comes from. */
enum class FaceSource { WATCH, PHONE }

/** A control on the Focus page while a session runs. */
enum class FocusAction(val label: String) {
    /** The watch's own run, paused by a build from before nuclear focus ([WatchTimer.resume]). */
    RESUME("Resume"),

    /** A break of a session started on this watch ([WatchTimer.skipBreak]). */
    SKIP_BREAK("Skip break"),

    /** Run the phone's session here too ([WatchTimerEngine.follow]). */
    CONTINUE_HERE("Continue here"),

    /** A phone run paused by an older phone build ([TimerCommands.RESUME]). */
    PHONE_RESUME("Resume")
}

/** A session's start options when nothing runs, in the order they are offered. */
sealed interface IdleAction {
    data class Start(val session: WatchSession) : IdleAction
    data object StartOnPhone : IdleAction
    data object SignIn : IdleAction
}

/** How far a focus block's tree has grown, drawn from the block's own progress. */
enum class TreeStage(val glyph: String, val label: String) {
    SEED("🌱", "Tree planted"),
    SAPLING("🌿", "Tree growing"),
    TREE("🌳", "Tree almost grown"),
    RESTING("🌳", "Break · tree resting")
}

/** Everything the Focus page shows while a session runs. */
data class RunningFace(
    val source: FaceSource,
    /** "Focus", "Short break" — with " · phone" for the phone's timer. */
    val phaseLabel: String,
    val isFocus: Boolean,
    /** "24:13" when interactive; whole minutes, rounded up, in ambient ("25 min"). */
    val countdown: String,
    val paused: Boolean,
    /** How much of the current phase has passed, 0..1; null when unknown. */
    val progress: Float?,
    /** The event's title for an event session, else the course. */
    val subject: String?,
    /** Focus blocks done and planned; [blocksTarget] 0 = unknown. */
    val blocksDone: Int,
    val blocksTarget: Int,
    /** "Block 2 of 4"; null when the phone did not say. */
    val blockLine: String?,
    /** "Break" / "Focus" / "Done", and the wall-clock time it comes; null while paused. */
    val nextLabel: String?,
    val nextAt: Long?,
    val tree: TreeStage?,
    val actions: List<FocusAction>
)

/**
 * The progress tile's arc: [progress] 0..1 as of the render; [accent] orange
 * (focus, or a goal not yet met), else green; [phaseEndsAt] and [phaseSeconds]
 * let the tile renderer move it on by itself while a phase runs.
 */
data class TileRing(val progress: Float, val accent: Boolean, val phaseEndsAt: Long?, val phaseSeconds: Long)

object FocusFaces {

    /** "4:05" — minutes and seconds, minutes unbounded ("125:00"). */
    fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /**
     * Whole minutes left, rounded UP: ambient updates once a minute, and a
     * seconds digit up to a minute stale would be a wrong number.
     */
    fun ambientMinutes(remainingSeconds: Long): Long = (remainingSeconds + 59) / 60

    /** How long the current phase of [state] runs in all. */
    fun phaseMillis(state: WatchTimerState): Long =
        if (state.phase == WatchPhase.FOCUS) state.focusMillisOfBlock(state.completedFocus + 1)
        else state.durations.millisOf(state.phase)

    /** Share of [totalMillis] already behind when [leftMillis] remain, clamped to 0..1. */
    fun elapsedShare(leftMillis: Long, totalMillis: Long): Float? {
        if (totalMillis <= 0L) return null
        return (1f - leftMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
    }

    /** The tree for a focus phase [progress] through; [RESTING] on a break. */
    fun treeStage(isFocus: Boolean, progress: Float?): TreeStage = when {
        !isFocus -> TreeStage.RESTING
        progress == null || progress < 1f / 3 -> TreeStage.SEED
        progress < 2f / 3 -> TreeStage.SAPLING
        else -> TreeStage.TREE
    }

    /**
     * The controls for the watch's own run: Resume only for a run paused by an
     * older build (it would otherwise be stuck), Skip break only on a break of
     * a session started here — the phone keeps the time of one it started.
     */
    fun watchActions(state: WatchTimerState): List<FocusAction> = buildList {
        if (!state.running) add(FocusAction.RESUME)
        if (state.phase != WatchPhase.FOCUS && !state.following) add(FocusAction.SKIP_BREAK)
    }

    /**
     * The controls for the phone's run: the phone refuses pause and stop, so
     * none are offered. Resume rescues a run paused by an older phone build;
     * Continue here runs the same session on the wrist.
     */
    fun phoneActions(timer: TimerSnapshot): List<FocusAction> = buildList {
        if (!timer.running) add(FocusAction.PHONE_RESUME)
        if (timer.running && timer.session != null) add(FocusAction.CONTINUE_HERE)
    }

    /** The watch's own run at [now]. */
    fun watchFace(state: WatchTimerState, now: Long, ambient: Boolean): RunningFace {
        val isFocus = state.phase == WatchPhase.FOCUS
        val block = if (isFocus) state.completedFocus + 1 else state.completedFocus
        val of = if (state.targetFocusBlocks > 0) " of ${state.targetFocusBlocks}" else ""
        val lastBlock = state.targetFocusBlocks in 1..block && isFocus
        val remaining = state.remainingSecondsAt(if (state.running) now else 0L)
        val progress = elapsedShare(state.remainingMillisAt(now), phaseMillis(state))
        return RunningFace(
            source = FaceSource.WATCH,
            phaseLabel = state.phase.label,
            isFocus = isFocus,
            countdown = if (ambient) "${ambientMinutes(remaining)} min" else clock(remaining),
            paused = !state.running,
            progress = progress,
            // An event session shows the event's name; its course stays in subject for the logs.
            subject = state.eventTitle ?: state.subject,
            blocksDone = state.completedFocus,
            blocksTarget = state.targetFocusBlocks,
            blockLine = "Block $block$of",
            nextLabel = when {
                !state.running -> null
                lastBlock -> "Done"
                isFocus -> "Break"
                else -> "Focus"
            },
            nextAt = state.phaseEndsAt.takeIf { state.running },
            tree = treeStage(isFocus, progress),
            actions = watchActions(state)
        )
    }

    /**
     * The phone's run at [now], mirrored. Counted from its absolute finish time,
     * rounded UP and flipped on that time's own second boundaries as the phone
     * does, so the two read alike between pushes.
     */
    fun phoneFace(timer: TimerSnapshot, now: Long): RunningFace {
        val phase = timer.phase.ifBlank { "Focus" }
        val session = timer.session
        val isFocus = session?.phaseName?.let { it == WatchPhase.FOCUS.name } ?: phase.startsWith("Focus")
        val msLeft = if (timer.running) (timer.finishAtMillis - now).coerceAtLeast(0L)
        else timer.remainingSeconds * 1_000L
        val remaining = if (timer.running) (msLeft + 999) / 1000L else timer.remainingSeconds.toLong()
        val total = session?.let { phoneSessionPhaseSeconds(it) * 1_000L } ?: 0L
        val progress = elapsedShare(msLeft, total)
        val block = session?.let { if (isFocus) it.completedFocus + 1 else it.completedFocus }
        return RunningFace(
            source = FaceSource.PHONE,
            phaseLabel = "$phase · phone",
            isFocus = isFocus,
            countdown = clock(remaining),
            paused = !timer.running,
            progress = progress,
            subject = session?.eventTitle ?: session?.subject,
            blocksDone = session?.completedFocus ?: 0,
            blocksTarget = session?.targetFocusBlocks ?: 0,
            blockLine = block?.let { b ->
                "Block $b" + (session.targetFocusBlocks.takeIf { it > 0 }?.let { " of $it" } ?: "")
            },
            nextLabel = null,
            nextAt = null,
            tree = treeStage(isFocus, progress),
            actions = phoneActions(timer)
        )
    }

    /** The current phase's whole length in the phone's session, in seconds; 0 when unknown. */
    fun phoneSessionPhaseSeconds(session: PhoneSession): Int = when (session.phaseName) {
        WatchPhase.FOCUS.name -> WatchTimer.focusSecondsForBlock(
            session.completedFocus + 1,
            session.targetFocusBlocks,
            session.focusSeconds,
            session.lastFocusSeconds.takeIf { it > 0 } ?: session.focusSeconds
        )
        WatchPhase.SHORT_BREAK.name -> session.shortBreakSeconds
        WatchPhase.LONG_BREAK.name -> session.longBreakSeconds
        else -> 0
    }

    /** Start options when idle: the four lengths, then the phone, then cloud sign-in when it applies. */
    fun idleActions(canSignInHere: Boolean, signedIn: Boolean): List<IdleAction> = buildList {
        WatchSession.entries.forEach { add(IdleAction.Start(it)) }
        add(IdleAction.StartOnPhone)
        if (canSignInHere && !signedIn) add(IdleAction.SignIn)
    }

    /** Today's focus against the goal for the ring: 0..1, 0 with no goal. */
    fun goalShare(minutes: Int, goal: Int): Float =
        if (goal <= 0) 0f else (minutes.toFloat() / goal).coerceIn(0f, 1f)

    fun goalMet(minutes: Int, goal: Int): Boolean = goal > 0 && minutes >= goal

    /** "3 blocks waiting for phone"; null when none wait. */
    fun unsyncedLine(blocks: Int): String? =
        if (blocks <= 0) null else "$blocks block${if (blocks > 1) "s" else ""} waiting for phone"

    /**
     * The cloud line: the last sign-in outcome while the screen is open, else
     * who the watch uploads as; null in a build with no cloud at all.
     */
    fun cloudLine(message: String?, configured: Boolean, account: String?, waiting: Int): String? {
        message?.let { return it }
        if (!configured) return null
        account ?: return "Cloud: not signed in"
        return "☁ $account" + if (waiting > 0) "\n$waiting waiting for network" else ""
    }

    /** Why the agenda list is empty, or null when it has events to show. */
    fun agendaEmptyText(agenda: WatchAgendaList, today: Long): String? = when {
        !agenda.present -> "Nothing from the phone yet. Open Euler's Lab on the phone."
        !agenda.allowed -> "Calendar access is off on the phone."
        agenda.epochDay != today -> "Not synced today yet. Open Euler's Lab on the phone."
        agenda.forDay(today).isEmpty() -> "Nothing timed on the calendar today."
        else -> null
    }

    /**
     * The progress tile's arc: the current phase while the watch's timer runs
     * (moving on by itself from [TileRing.phaseEndsAt] where the tile renderer
     * can), else today's focus against the goal.
     */
    fun tileRing(goalMinutes: Int, todayMinutes: Int, timer: WatchTimerState, now: Long): TileRing {
        if (timer.active) {
            val total = phaseMillis(timer)
            return TileRing(
                progress = elapsedShare(timer.remainingMillisAt(now), total) ?: 0f,
                accent = timer.phase == WatchPhase.FOCUS,
                phaseEndsAt = timer.phaseEndsAt.takeIf { timer.running },
                phaseSeconds = total / 1_000L
            )
        }
        return TileRing(
            progress = goalShare(todayMinutes, goalMinutes),
            accent = !goalMet(todayMinutes, goalMinutes),
            phaseEndsAt = null,
            phaseSeconds = 0L
        )
    }

    /** At most this many block dots; a longer session (a long event) shows only its block line. */
    const val MAX_DOTS = 8
}
