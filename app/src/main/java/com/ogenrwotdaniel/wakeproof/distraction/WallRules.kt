package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where the walls were stored while they could be edited; now only read, for stricter values. */
private val Context.wallRulesDataStore: DataStore<Preferences> by preferencesDataStore("wall_rules")

/**
 * The walls over gated apps. Every one is fixed in code (2026-11-08, the
 * user's choice: "totally fixed so I don't change my bedtimes or usage"),
 * like the night wall and the locked alarms: no switch, no Edit, no queued
 * loosening, and a reinstall comes back the same. Changing one takes a new
 * build. Until then they could be loosened after a week, and a week turned out
 * to be short enough to wait out on a bad one.
 */
enum class WallRule(val key: String, val default: Int) {
    /** Browsers follow the same walls as social apps; instagram.com otherwise skips every wall. */
    GATE_BROWSERS("gate_browsers", RULE_ON),

    /**
     * Minute of the day the bedtime wall starts. It lasts until
     * [BEDTIME_END_MINUTES] (16:00 to 08:20 since 2026-11-14; the daily
     * 12:25–12:50 window lies wholly outside it).
     */
    BEDTIME_START_MINUTES("bedtime_start_minutes", 16 * 60),

    /** Do Not Disturb for the whole of a focus session. */
    SESSION_DND("session_dnd", RULE_ON),

    /**
     * Minute of the day the gated apps' open window starts, every day. Covers
     * social, video, messaging, the Google app, Play Store and browsers.
     * 12:25–12:50 daily since 2026-11-16 (user's choice; Saturday and Sunday
     * 14:00–16:00 before).
     */
    OPEN_WINDOW_START_MINUTES("open_window_start_minutes", 12 * 60 + 25),

    /** Minute of the day the open window closes (exclusive). */
    OPEN_WINDOW_END_MINUTES("open_window_end_minutes", 12 * 60 + 50);

    /**
     * Pure: what this rule is in force at, given what a device [stored] while
     * the walls were editable (null = nothing): [default], or the stored value
     * when it is stricter. A tightening made back then applied at once and was
     * the user's own, so the lock must not undo it; anything looser, or off, is
     * dropped. A switch always takes its default. The old Monday–Friday shutdown
     * (`weekend_shut`) is no rule at all since 2026-11-16, when the user chose a
     * daily window: a value stored for it is never read, on purpose.
     */
    fun fixedOrStricter(stored: Int?): Int = when {
        stored == null || stored < 0 -> default
        // A stored bedtime before the window opens would leave no minute open, so it is
        // read as the window's start: [fixedRules] then resets it to [default].
        this == BEDTIME_START_MINUTES -> minOf(default, stored.coerceAtLeast(OPEN_WINDOW_START_MINUTES.default))
        this == OPEN_WINDOW_START_MINUTES -> maxOf(default, stored)
        this == OPEN_WINDOW_END_MINUTES -> minOf(default, stored)
        else -> default
    }
}

const val RULE_ON = 1
const val RULE_OFF = 0
const val RULE_OFF_MINUTES = -1
const val MINUTES_PER_DAY = 24 * 60

/**
 * The rules, read as what they mean. [values] exists for tests of the gate's
 * other branches; the app itself only ever sees the defaults
 * ([WallRulesRepository]).
 */
data class WallRules(val values: Map<WallRule, Int> = emptyMap()) {
    fun value(rule: WallRule): Int = values[rule] ?: rule.default
    val gateBrowsers: Boolean get() = value(WallRule.GATE_BROWSERS) != RULE_OFF
    val bedtimeStartMinutes: Int get() = value(WallRule.BEDTIME_START_MINUTES)
    val sessionDnd: Boolean get() = value(WallRule.SESSION_DND) != RULE_OFF
    val openWindowStartMinutes: Int get() = value(WallRule.OPEN_WINDOW_START_MINUTES)
    val openWindowEndMinutes: Int get() = value(WallRule.OPEN_WINDOW_END_MINUTES)
}

/**
 * Hands out the fixed [WallRules]. What a device stored while the walls were
 * editable is read only through [WallRule.fixedOrStricter]: a stricter bedtime
 * or narrower window stays, a later bedtime, a switch turned off or a loosening
 * queued behind the week never comes back. Nothing writes here any more.
 * Kept (2026-11-09): the phone and tablet were updated in place from the
 * editable build, so a stricter value saved then may still be there.
 */
@Singleton
class WallRulesRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val rules: Flow<WallRules> = context.wallRulesDataStore.data.map { prefs ->
        fixedRules { prefs[intPreferencesKey(it.key)] }
    }

    suspend fun current(): WallRules = rules.first()
}

/**
 * Pure: the rules in force given what was [stored] per rule. Stored values
 * that no longer leave any minute open (a morning window from before the
 * daily 12:25–12:50 window, or an old bedtime at or before 12:25) fall back to
 * fixed values, resetting as little as reopens a minute (bedtime, then window,
 * then both): with nothing left to edit, they would otherwise shut gated apps
 * for good.
 */
fun fixedRules(stored: (WallRule) -> Int?): WallRules {
    val rules = WallRules(WallRule.entries.associateWith { it.fixedOrStricter(stored(it)) })
    val open = gatedOpenWindow(rules) ?: return rules
    if (open.first < open.second) return rules
    // Loosen as little as possible (tighten-only): the bedtime alone, then the
    // window alone, then both, keeping the first that opens any minute.
    val bedtime = listOf(WallRule.BEDTIME_START_MINUTES)
    val window = listOf(WallRule.OPEN_WINDOW_START_MINUTES, WallRule.OPEN_WINDOW_END_MINUTES)
    for (reset in listOf(bedtime, window, bedtime + window)) {
        val candidate = WallRules(rules.values + reset.associateWith { it.default })
        val reopened = gatedOpenWindow(candidate) ?: return candidate
        if (reopened.first < reopened.second) return candidate
    }
    return WallRules(rules.values + (bedtime + window).associateWith { it.default })
}

/**
 * Pure: the minutes gated apps are really open on a window day, `[start, end)`:
 * the open window less what the bedtime wall covers (until 08:20, and from its
 * start). Null when the window is off; start >= end when nothing is left. Wall
 * copy uses it, so no screen promises an hour the bedtime wall still covers.
 */
fun gatedOpenWindow(rules: WallRules): Pair<Int, Int>? {
    val start = rules.openWindowStartMinutes
    val end = rules.openWindowEndMinutes
    if (start < 0) return null
    val bedtime = rules.bedtimeStartMinutes
    if (bedtime < 0) return start to end
    return maxOf(start, BEDTIME_END_MINUTES) to minOf(end, bedtime)
}

/**
 * The bedtime wall lifts at this minute of the day, alarm or not (user's choice,
 * 2026-11-08).
 */
const val BEDTIME_END_MINUTES = 8 * 60 + 20

/**
 * Pure: whether the bedtime wall is up at [minuteOfDay]. It starts at
 * [startMinutes] and runs past midnight until [BEDTIME_END_MINUTES]; passing a
 * morning alarm no longer lifts it early. The fixed start is in the afternoon,
 * so "before the start" and "the morning after" never overlap.
 */
fun bedtimeActive(minuteOfDay: Int, startMinutes: Int): Boolean =
    startMinutes >= 0 && (minuteOfDay >= startMinutes || minuteOfDay < BEDTIME_END_MINUTES)

/**
 * Pure: whether [minuteOfDay] falls outside the gated apps' open window
 * `[startMinutes, endMinutes)`. A negative start means no window — open all
 * day. A window that ends at or before it starts is open at no time at all:
 * the strict reading of a setting that makes no sense.
 */
fun outsideOpenWindow(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean =
    startMinutes >= 0 && minuteOfDay !in startMinutes until endMinutes

/**
 * Pure: when an app whose daily cap is spent can actually be used again after
 * today. Null means midnight tonight (ordinary countdown); otherwise
 * `"tomorrow at 12:25"`. An empty window (`endMinutes <= startMinutes` with a
 * non-negative start) returns null rather than promising an opening that will
 * not happen.
 */
fun nextOpenAfterToday(
    gated: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): String? {
    if (!gated || startMinutes == RULE_OFF_MINUTES || endMinutes <= startMinutes) return null
    return "tomorrow at ${formatClock(startMinutes)}"
}

/**
 * Pure: headline and body for [GateVerdict.WINDOW_WALL] at [minuteOfDay]. Only
 * a later opening the same day is named: tomorrow's could still be taken by an
 * exam week that starts at midnight, and the wall must not promise it.
 */
fun windowWallCopy(
    label: String,
    minuteOfDay: Int,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): Pair<String, String> {
    if (startMinutes == RULE_OFF_MINUTES || endMinutes <= startMinutes) {
        return "Not open now" to
            "$label and every other gated app are shut right now. The phone and SMS always work."
    }
    val span = "${formatClock(startMinutes)} and ${formatClock(endMinutes)}"
    val later = if (minuteOfDay < startMinutes) " Next open today at ${formatClock(startMinutes)}." else ""
    return "Not open now" to
        "$label and every other gated app open only between $span each day.$later " +
        "The phone and SMS always work."
}
