package com.ogenrwotdaniel.wakeproof.pomodoro

/**
 * How many focus blocks a timed session of [sessionMinutes] should run.
 *
 * A timed session is a request for a *length*, but the timer can only run whole
 * pomodoros: asking for 90 minutes at 25+5 means three focus blocks with two
 * breaks between them, which is 85 minutes. The job here is to pick the whole
 * number of blocks whose real length lands closest to what was asked for.
 *
 * It used to be `sessionMinutes / cycleMinutes`, which is two mistakes at once:
 *
 * * It counts a trailing break that is never run. A session ends on a focus
 *   block, so *k* blocks take `k * focus + (k - 1) * break`, not `k * cycle`.
 * * It floors. At the default 25+5 that is invisible, because 60, 90 and 120
 *   all divide exactly — but at 50+10 a request for 90 minutes floored to one
 *   50-minute block, missing by 40 minutes, and the user was given barely half
 *   the session they picked.
 *
 * Solving `k * focus + (k - 1) * break = sessionMinutes` for k and rounding
 * gives the nearest whole session. Returns 0 for a free-running session
 * ([sessionMinutes] <= 0); [sessionPlan] never asks for one.
 */
fun focusBlocksForSession(
    sessionMinutes: Int,
    focusMinutes: Int,
    breakMinutes: Int
): Int {
    if (sessionMinutes <= 0) return 0
    // A zero-length cycle would divide by zero. It should be impossible —
    // the focus-length setting has a floor — but this runs on whatever is in
    // DataStore, and a timed session must not be what crashes the service.
    val cycle = focusMinutes + breakMinutes
    if (cycle <= 0) return 1
    val blocks = (sessionMinutes + breakMinutes + cycle / 2) / cycle
    return blocks.coerceAtLeast(1)
}

/**
 * The 30-minute reading preset (2026-11-20, the user: "remove 3 hours focus
 * session and replace it with 30 minutes version mostly targeting reading at
 * lunch time"): one unbroken 30-minute focus block, whatever the length
 * settings say. The 12:00 reading alarm starts it. The 3-hour preset is gone.
 */
const val READING_SESSION_MINUTES = 30

/**
 * The length a start with no length asks for. Free-running sessions are gone:
 * a session cannot be stopped or paused once started (nuclear focus), so one
 * that never ends on its own would be a phone locked until a reboot. The
 * watch's "Start on phone" sends no length and lands here.
 */
const val DEFAULT_SESSION_MINUTES = 60

/** Everything the timer needs to run one session start to finish. */
data class SessionPlan(
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val focusBlocks: Int,
    /** A long break follows every this-many focus blocks. */
    val longBreakEvery: Int,
    /**
     * The LAST focus block's length; the others run [focusMinutes]. Differs
     * only in an [exactSessionPlan], which trims it (never past 25) so the session ends on the
     * minute asked for.
     */
    val lastFocusMinutes: Int = focusMinutes
)

/**
 * Turns a chosen session length into a plan.
 *
 * The 30-minute reading preset is a fixed recipe that ignores the length
 * settings: one 30-minute block and nothing after it (at 25+5 the nearest
 * whole session would have been a single 25-minute block). Every other length
 * runs the user's own blocks with the usual long break every fourth, sized by
 * [focusBlocksForSession].
 */
fun sessionPlan(
    sessionMinutes: Int,
    focusMinutes: Int,
    shortBreakMinutes: Int,
    longBreakMinutes: Int
): SessionPlan {
    if (sessionMinutes == READING_SESSION_MINUTES) {
        // Break lengths are never run (one block), but stay sane for a follower's checks.
        return SessionPlan(
            focusMinutes = READING_SESSION_MINUTES,
            shortBreakMinutes = 5,
            longBreakMinutes = 15,
            focusBlocks = 1,
            longBreakEvery = 4
        )
    }
    val minutes = if (sessionMinutes > 0) sessionMinutes else DEFAULT_SESSION_MINUTES
    return SessionPlan(
        focusMinutes = focusMinutes,
        shortBreakMinutes = shortBreakMinutes,
        longBreakMinutes = longBreakMinutes,
        focusBlocks = focusBlocksForSession(minutes, focusMinutes, shortBreakMinutes),
        longBreakEvery = 4
    )
}

/** Focus and break lengths of an [exactSessionPlan]: the classic 25 + 5. */
const val EXACT_FOCUS_MINUTES = 25
const val EXACT_BREAK_MINUTES = 5

/**
 * The shortest exact session offered — under it, an event is ticked off
 * rather than focused for — and the shortest last block it will trim to.
 */
const val MIN_EXACT_SESSION_MINUTES = 10

/** The shortest last block an event session keeps; a shorter remainder is dropped. */
const val MIN_EXACT_LAST_BLOCK_MINUTES = 5

/**
 * Pure: a session for focusing through a calendar event of [totalMinutes]
 * (2026-11-01): 25-minute blocks with 5-minute breaks and no long break.
 *
 * No block ever runs over 25 minutes (2026-11-20, the user: "max focus time is
 * 25 minutes followed by 5 minutes break; when I open focus from calendar
 * events, some of them end up running over 30 minutes"). Until then the last
 * block was stretched rather than leave a stub, so a 35-minute event was one
 * 35-minute block and 1 h 02 m ended on a 32. Now the last block takes what is
 * left, at most 25: 50 minutes is 25 + 5 + 20, 35 is 25 + 5 + 5. When what is
 * left is under [MIN_EXACT_LAST_BLOCK_MINUTES], or falls in a break, the session
 * ends a few minutes before the event (at most 9) rather than run a stub or a
 * long block: 1 h 02 m is 25 + 5 + 25. Never shorter than the floor, so a
 * caller below it still gets a sane plan.
 */
fun exactSessionPlan(totalMinutes: Int): SessionPlan {
    val (blocks, last) = exactBlocks(totalMinutes)
    return SessionPlan(
        focusMinutes = EXACT_FOCUS_MINUTES,
        shortBreakMinutes = EXACT_BREAK_MINUTES,
        longBreakMinutes = EXACT_BREAK_MINUTES,
        focusBlocks = blocks,
        longBreakEvery = Int.MAX_VALUE,
        lastFocusMinutes = last
    )
}

/** Pure: (focus blocks, last block minutes) of [exactSessionPlan]. */
internal fun exactBlocks(totalMinutes: Int): Pair<Int, Int> {
    val total = totalMinutes.coerceAtLeast(MIN_EXACT_SESSION_MINUTES)
    val cycle = EXACT_FOCUS_MINUTES + EXACT_BREAK_MINUTES
    val full = total / cycle
    val rest = total % cycle
    return when {
        // Ends on a whole block; its break is never run.
        rest == 0 -> full to EXACT_FOCUS_MINUTES
        // A stub after whole blocks is dropped.
        rest < MIN_EXACT_LAST_BLOCK_MINUTES && full > 0 -> full to EXACT_FOCUS_MINUTES
        // Capped: the event's end falls in the break after it.
        else -> (full + 1) to minOf(rest, EXACT_FOCUS_MINUTES)
    }
}

/**
 * Pure: how long focus block number [block] (1-based) runs in a session of
 * [targetFocusBlocks] — the last one [lastFocusSeconds], the rest
 * [focusSeconds]. A session with no target never reaches a "last".
 */
fun focusSecondsForBlock(block: Int, targetFocusBlocks: Int, focusSeconds: Int, lastFocusSeconds: Int): Int =
    if (targetFocusBlocks > 0 && block >= targetFocusBlocks) lastFocusSeconds else focusSeconds

/**
 * Pure: whether a session of this shape owes the written summary. Only the
 * 30 min option is spared (2026-11-20, the user: "for 30 minutes focus, skip
 * the ten minutes summary ... put exception only for the 30 minutes option but
 * anything >30 minutes, no exception"): ten minutes of writing after half an
 * hour is a third as long again, and the lunch read has to leave time to eat.
 * It is told by its shape, one unbroken 30-minute block, which only that preset
 * makes (an event session's blocks stop at 25), so a joined copy of it, from
 * the watch or the tablet, is spared too. Calendar-event sessions always owe it.
 */
fun sessionOwesSummary(targetFocusBlocks: Int, focusSeconds: Int, lastFocusSeconds: Int): Boolean {
    val readingPreset = READING_SESSION_MINUTES * 60
    return !(targetFocusBlocks == 1 && focusSeconds == readingPreset && lastFocusSeconds == readingPreset)
}
