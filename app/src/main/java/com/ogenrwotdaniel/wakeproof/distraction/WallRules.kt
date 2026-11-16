package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
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
     * Gated apps stay completely shut from Monday 00:00 through Friday 24:00,
     * opening only on Saturday and Sunday during the open window (2026-11-14,
     * user's choice).
     */
    WEEKEND_SHUT("weekend_shut", RULE_ON),

    /**
     * Minute of the day the bedtime wall starts. It lasts until
     * [BEDTIME_END_MINUTES] (16:00 to 08:20 since 2026-11-14, starting as the
     * 14:00–16:00 weekend window closes).
     */
    BEDTIME_START_MINUTES("bedtime_start_minutes", 16 * 60),

    /** Do Not Disturb for the whole of a focus session. */
    SESSION_DND("session_dnd", RULE_ON),

    /**
     * Minute of the day the gated apps' open window starts, Saturday and Sunday
     * ([WEEKEND_SHUT] keeps Monday–Friday shut all day). Covers social, video,
     * messaging, the Google app, Play Store and browsers. 14:00–16:00 (2pm–4pm)
     * since 2026-11-14 (user's choice).
     */
    OPEN_WINDOW_START_MINUTES("open_window_start_minutes", 14 * 60),

    /** Minute of the day the open window closes (exclusive). */
    OPEN_WINDOW_END_MINUTES("open_window_end_minutes", 16 * 60);

    /**
     * Pure: what this rule is in force at, given what a device [stored] while
     * the walls were editable (null = nothing): [default], or the stored value
     * when it is stricter. A tightening made back then applied at once and was
     * the user's own, so the lock must not undo it; anything looser, or off, is
     * dropped. Switches can be no stricter than on, so they are always on.
     */
    fun fixedOrStricter(stored: Int?): Int = when {
        stored == null || stored < 0 -> default
        this == BEDTIME_START_MINUTES -> minOf(default, stored.coerceAtLeast(12 * 60))
        this == OPEN_WINDOW_START_MINUTES -> maxOf(default, stored)
        this == OPEN_WINDOW_END_MINUTES -> minOf(default, stored)
        else -> default
    }
}

const val RULE_ON = 1
const val RULE_OFF = 0
const val RULE_OFF_MINUTES = -1
const val MINUTES_PER_DAY = 24 * 60

/** Days of the week on which [WallRule.WEEKEND_SHUT] keeps gated apps closed all day (Monday–Friday). */
val WEEKEND_SHUT_DAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY
)

/**
 * The rules, read as what they mean. [values] exists for tests of the gate's
 * other branches; the app itself only ever sees the defaults
 * ([WallRulesRepository]).
 */
data class WallRules(val values: Map<WallRule, Int> = emptyMap()) {
    fun value(rule: WallRule): Int = values[rule] ?: rule.default
    val gateBrowsers: Boolean get() = value(WallRule.GATE_BROWSERS) != RULE_OFF
    val weekendShut: Boolean get() = value(WallRule.WEEKEND_SHUT) != RULE_OFF
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
 * weekend 14:00–16:00 move, or an old bedtime at or before 14:00) fall back to
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

/** Pure: whether gated apps are shut all day on [dayOfWeek] under [WallRule.WEEKEND_SHUT] (Monday–Friday). */
fun weekendShutActive(dayOfWeek: DayOfWeek, weekendShut: Boolean): Boolean =
    weekendShut && dayOfWeek in WEEKEND_SHUT_DAYS

// Every day name in the wall copy comes from [WEEKEND_SHUT_DAYS] through these,
// so changing the set cannot leave one line of a wall contradicting another.

private fun dayName(day: DayOfWeek): String =
    day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)

private fun listed(days: List<String>): String =
    if (days.size <= 1) days.joinToString() else days.dropLast(1).joinToString(", ") + " and " + days.last()

/**
 * Pure: the first day after [today] that [WEEKEND_SHUT_DAYS] leaves open, or null
 * when every day is shut (nothing ever opens, so no day may be promised).
 */
fun firstOpenDayAfter(today: DayOfWeek): String? =
    (1..7).map { today.plus(it.toLong()) }.firstOrNull { it !in WEEKEND_SHUT_DAYS }?.let(::dayName)

/** Pure: the days [WEEKEND_SHUT_DAYS] leaves open, in week order: "Saturday and Sunday". */
fun openDaysLabel(): String = listed(DayOfWeek.entries.filter { it !in WEEKEND_SHUT_DAYS }.map(::dayName))

/**
 * Pure: the shut days, in week order. One unbroken run reads as a span joined by
 * [through] ("Monday to Friday", "Monday through Friday"); anything else is listed.
 */
fun shutDaysLabel(through: String): String {
    val days = DayOfWeek.entries.filter { it in WEEKEND_SHUT_DAYS }
    val run = days.size >= 3 && days.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
    return if (run) "${dayName(days.first())} $through ${dayName(days.last())}" else listed(days.map(::dayName))
}

/**
 * Pure: whether [minuteOfDay] falls outside the gated apps' open window
 * `[startMinutes, endMinutes)`. A negative start means no window — open all
 * day. A window that ends at or before it starts is open at no time at all:
 * the strict reading of a setting that makes no sense.
 */
fun outsideOpenWindow(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean =
    startMinutes >= 0 && minuteOfDay !in startMinutes until endMinutes

/**
 * Pure: whether a gated app is shut by the schedule on [dayOfWeek] at
 * [minuteOfDay] — either all day Monday–Friday ([weekendShutActive]) or
 * outside `[startMinutes, endMinutes)` ([outsideOpenWindow]).
 */
fun windowShutActive(
    dayOfWeek: DayOfWeek,
    minuteOfDay: Int,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int
): Boolean =
    weekendShutActive(dayOfWeek, weekendShut) ||
        outsideOpenWindow(minuteOfDay, startMinutes, endMinutes)

/**
 * Pure: when an app whose daily cap is spent on [today] can actually be used
 * again after today. Null means midnight tonight (ordinary countdown); otherwise
 * a concrete phrase such as `"tomorrow at 14:00"`, `"Saturday at 14:00"`, or
 * `"Saturday"`. An empty window (`endMinutes <= startMinutes` with a non-negative
 * start) returns null rather than promising an opening that will not happen.
 */
fun nextOpenAfterToday(
    gated: Boolean,
    today: DayOfWeek,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): String? {
    if (!gated) return null
    val hasWindow = startMinutes != RULE_OFF_MINUTES
    if (hasWindow && endMinutes <= startMinutes) return null
    val shutTomorrow = weekendShutActive(today.plus(1), weekendShut)
    val nextDay = firstOpenDayAfter(today)
    return when {
        shutTomorrow && nextDay == null -> null
        shutTomorrow && hasWindow -> "$nextDay at ${formatClock(startMinutes)}"
        shutTomorrow -> nextDay
        hasWindow -> "tomorrow at ${formatClock(startMinutes)}"
        else -> null
    }
}

/**
 * Pure: headline and body for [GateVerdict.WINDOW_WALL] on [today] at [minuteOfDay].
 */
fun windowWallCopy(
    label: String,
    today: DayOfWeek,
    minuteOfDay: Int,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): Pair<String, String> {
    val validWindow = startMinutes != RULE_OFF_MINUTES && endMinutes > startMinutes
    if (weekendShutActive(today, weekendShut)) {
        val nextDay = firstOpenDayAfter(today)
        val reopens = when {
            nextDay == null -> "They do not open on any day."
            validWindow -> "They open again ${openDaysLabel()} between ${formatClock(startMinutes)} and ${formatClock(endMinutes)}."
            else -> "They open again on $nextDay."
        }
        return "Shut ${shutDaysLabel("to")}" to
            "$label and every other gated app stay shut from ${shutDaysLabel("through")}. " +
            "$reopens The phone and SMS always work."
    }
    if (!validWindow) {
        return "Not open now" to
            "$label and every other gated app are shut right now. The phone and SMS always work."
    }
    val span = "${formatClock(startMinutes)} and ${formatClock(endMinutes)}"
    // The last open day before a shut run, after its window: say when it comes back.
    val nextDay = firstOpenDayAfter(today)
    val body = if (weekendShut && weekendShutActive(today.plus(1), true) && minuteOfDay >= endMinutes && nextDay != null) {
        "$label and every other gated app open ${openDaysLabel()} between $span, " +
            "and stay shut ${shutDaysLabel("through")} — next open $nextDay at ${formatClock(startMinutes)}. " +
            "The phone and SMS always work."
    } else if (weekendShut) {
        "$label and every other gated app open ${openDaysLabel()} between $span " +
            "(shut ${shutDaysLabel("through")}). The phone and SMS always work."
    } else {
        "$label and every other gated app open only between $span. " +
            "The phone and SMS always work."
    }
    return "Not open now" to body
}
