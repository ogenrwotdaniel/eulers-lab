package com.ogenrwotdaniel.wakeproof.wear.walls

import android.content.pm.ApplicationInfo
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Which watch apps are gated, and when they are shut (2026-11-15, the user: "I can
 * access SNS/Gated apps from watch"). Pure, so every rule is unit-tested without a
 * watch; [WatchWallService] and [WatchWallNotificationListener] feed it.
 *
 * Hand-kept COPY of the phone's rules: there is no shared module between `:app` and
 * `:wear`, so a change to `AppGate.KNOWN_GATED_PACKAGES` or `WallRules.kt` on the
 * phone has to be made here too.
 */
object WatchGate {

    /** Matches `AppGate.GOOGLE_APP` in `:app`. */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Matches `AppGate.PLAY_STORE` in `:app`. */
    const val PLAY_STORE = "com.android.vending"

    /**
     * COPY of `AppGate.KNOWN_GATED_PACKAGES` in `:app`. Most Wear OS builds of
     * these apps (WhatsApp, Messenger, Telegram, KakaoTalk, LINE, YouTube Music)
     * ship under the phone app's own package name, so the copy already covers
     * them; [WEAR_ONLY_GATED] adds the ones that do not.
     */
    val PHONE_KNOWN_GATED = setOf(
        // Social
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana",
        "com.facebook.lite", "com.twitter.android", "com.snapchat.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage",
        "com.pinterest", "com.linkedin.android", "com.tumblr", "com.bereal.ft",
        // Video
        "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
        // Messaging
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
        "org.telegram.messenger.web", "org.thoughtcrime.securesms", "com.discord",
        "com.kakao.talk", "jp.naver.line.android", "com.tencent.mm", "com.viber.voip",
        // Feeds & portals
        GOOGLE_APP,
        "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.kids",
        "com.samsung.android.app.spage", "com.samsung.android.tvplus",
        "net.daum.android.daum", "com.nhn.android.search",
        // The store itself: a new feed is one install away.
        PLAY_STORE,
        // Alternative clients
        "org.schabi.newpipe", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "xyz.blueskyweb.app", "org.joinmastodon.android", "ml.docilealligator.infinityforreddit"
    )

    /**
     * Watch-only packages of gated kinds. Wear Internet Browser (`com.appfour.wearbrowser`)
     * is the common third-party watch browser; it is named because a watch browser often
     * answers no ordinary web-link intent, so [isGated]'s browser test would miss it.
     */
    val WEAR_ONLY_GATED = setOf("com.appfour.wearbrowser")

    /** COPY of `AppGate.KNOWN_BROWSERS` in `:app` (Chrome included). */
    val PHONE_KNOWN_BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser",
        "org.mozilla.firefox", "com.brave.browser", "com.opera.browser",
        "com.opera.mini.native", "com.microsoft.emmx", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.naver.whale",
        "com.UCMobile.intl", "org.mozilla.focus", "org.mozilla.fenix"
    )

    /** Every package named gated on the watch. */
    val KNOWN_GATED: Set<String> = PHONE_KNOWN_GATED + WEAR_ONLY_GATED + PHONE_KNOWN_BROWSERS

    /**
     * Never walled, whatever they declare: a wall in front of any of these could cost
     * a call, an SOS, the way home or the way to fix a grant. Read with the dynamic
     * essentials ([essentialsWith]): the current home, keyboard, dialer and watch face.
     * Named outright too, because those lookups can fail and this list must not.
     */
    val NEVER_WALLED = setOf(
        // This app (both modules share the applicationId).
        "com.ogenrwotdaniel.wakeproof",
        // System UI, settings, the Wear OS home and the tile / watch-face hosts.
        "android", "com.android.systemui", "com.android.settings",
        "com.google.android.apps.wearable.settings", "com.google.android.wearable.app",
        "com.google.android.apps.wearable.systemui", "com.samsung.android.wearable.sysui",
        "com.samsung.android.app.launcher", "com.samsung.android.watch.watchface",
        "com.google.android.clockwork.home", "com.samsung.android.wearable.setupwizard",
        // Calls, the in-call screen and emergency.
        "com.android.server.telecom", "com.android.phone", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.google.android.apps.wearable.phone", "com.samsung.android.emergency",
        "com.google.android.apps.safetyhub", "com.android.emergency",
        // Health: falls, heart alerts and workouts.
        "com.samsung.android.wear.shealth", "com.samsung.android.app.shealth",
        "com.google.android.apps.fitness",
        // Permission prompts and installers, so a grant can always be given.
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        // SMS: one-time codes expire in minutes (the phone spares its default SMS app too).
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        // Keyboards.
        "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
        "com.google.android.wearable.input"
    )

    /** [NEVER_WALLED] plus whatever the watch reports as its home, keyboard, dialer, SMS and watch face. */
    fun essentialsWith(dynamic: Collection<String>): Set<String> = NEVER_WALLED + dynamic

    /**
     * Pure: whether [packageName] is gated on the watch. Mirrors `AppGate.isGated`
     * with browsers always gated (`WallRule.GATE_BROWSERS` is fixed on). An
     * [essentials] package is never gated, so a misdeclared category cannot wall
     * the dialer. Anything not recognised here is allowed: the watch fails open on
     * what it cannot classify, because a wrongly walled app could be the one that
     * matters (a bank code, a hospital's app).
     */
    fun isGated(
        packageName: String,
        category: Int,
        isBrowser: Boolean,
        essentials: Set<String>
    ): Boolean =
        packageName !in essentials && packageName !in NEVER_WALLED && (
            packageName in KNOWN_GATED ||
                category == ApplicationInfo.CATEGORY_SOCIAL ||
                category == ApplicationInfo.CATEGORY_VIDEO ||
                isBrowser ||
                packageName.startsWith(WEBAPK_PREFIX)
            )

    /** COPY of `AppGate.WEBAPK_PREFIX`: Chrome's saved-site shells are browsers too. */
    const val WEBAPK_PREFIX = "org.chromium.webapk."
}

/**
 * COPY of the phone's fixed schedule (`WallRules.kt` in `:app`): gated apps open only
 * Saturday and Sunday 14:00–16:00. The phone's bedtime wall (16:00–08:20) and night
 * wall (18:00–08:00 on the phone) lie wholly outside that window, so on the watch
 * they need no rule of their own.
 */
object WatchSchedule {
    /** COPY of `WEEKEND_SHUT_DAYS`: shut all day. */
    val SHUT_DAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    /** COPY of `WallRule.OPEN_WINDOW_START_MINUTES.default`. */
    const val OPEN_WINDOW_START_MINUTES = 14 * 60

    /** COPY of `WallRule.OPEN_WINDOW_END_MINUTES.default`. */
    const val OPEN_WINDOW_END_MINUTES = 16 * 60

    /** COPY of `WallRule.BEDTIME_START_MINUTES.default`; kept to show it lies past the window. */
    const val BEDTIME_START_MINUTES = 16 * 60

    /** Pure: whether the fixed schedule shuts gated apps at [at] (watch-local time). */
    fun shut(at: LocalDateTime): Boolean {
        val minute = at.hour * 60 + at.minute
        return at.dayOfWeek in SHUT_DAYS ||
            minute !in OPEN_WINDOW_START_MINUTES until minOf(OPEN_WINDOW_END_MINUTES, BEDTIME_START_MINUTES)
    }

    /** Pure: the start of the next open window at or after [at]; [at] itself while it is open. */
    fun nextOpening(at: LocalDateTime): LocalDateTime {
        if (!shut(at)) return at
        val today = at.toLocalDate()
        for (offset in 0L..7L) {
            val day = today.plusDays(offset)
            if (day.dayOfWeek in SHUT_DAYS) continue
            val start = day.atTime(OPEN_WINDOW_START_MINUTES / 60, OPEN_WINDOW_START_MINUTES % 60)
            if (start.isAfter(at)) return start
        }
        // Unreachable while SHUT_DAYS leaves a day open; a week ahead is the honest worst case.
        return today.plusDays(7).atTime(OPEN_WINDOW_START_MINUTES / 60, OPEN_WINDOW_START_MINUTES % 60)
    }
}

/** Why gated apps are shut on the watch right now; the order is [watchWallVerdict]'s. */
enum class WatchWall(val title: String) {
    /** The watch's automatic time is off: the schedule could be dodged by setting the clock. */
    CLOCK("Clock wall"),
    /** A focus session runs on the watch or the phone (breaks included, like the phone). */
    FOCUS("Focus wall"),
    /** The phone's last session still owes its summary photo. */
    SUMMARY("Session summary"),
    /** An exam week the phone holds. */
    EXAM("Exam week"),
    /** Outside Saturday–Sunday 14:00–16:00. */
    SCHEDULE("Gated apps are shut")
}

/**
 * Everything [watchWallVerdict] reads. [examShutUntilMillis] and [summaryUntilMillis]
 * come from the phone's `/wakeproof/stats` and are 0 when the phone never said;
 * the fixed schedule still applies then (fail closed on the schedule).
 */
data class WallInputs(
    val nowMillis: Long,
    val localNow: LocalDateTime,
    val autoTimeOn: Boolean,
    val watchSessionActive: Boolean,
    val phoneSessionLive: Boolean,
    val summaryUntilMillis: Long = 0L,
    val examShutUntilMillis: Long = 0L
)

/** COPY of the watch's `SUMMARY_HOLD_MAX_MILLIS` (= `:app` `SUMMARY_HOLD_MILLIS`). */
private const val SUMMARY_HOLD_MAX = 30 * 60_000L

/**
 * The longest an exam hold is believed. A run of exam weeks can chain, but an end
 * further off than this is a wrong clock on one side or a corrupt item, and must
 * not shut the watch's gated apps for good.
 */
const val EXAM_HOLD_MAX_MILLIS = 60L * 24 * 60 * 60_000L

/** Pure: an exam week the phone holds is still running at [now]. */
fun examHolds(untilMillis: Long, now: Long): Boolean = untilMillis - now in 1..EXAM_HOLD_MAX_MILLIS

/**
 * Pure: the wall that shuts gated apps now, or null when they are open. In the
 * phone's order (`gateVerdict`): clock, focus, summary, exam, then the schedule.
 */
fun watchWallVerdict(inputs: WallInputs): WatchWall? = when {
    !inputs.autoTimeOn -> WatchWall.CLOCK
    inputs.watchSessionActive || inputs.phoneSessionLive -> WatchWall.FOCUS
    inputs.summaryUntilMillis - inputs.nowMillis in 1..SUMMARY_HOLD_MAX -> WatchWall.SUMMARY
    examHolds(inputs.examShutUntilMillis, inputs.nowMillis) -> WatchWall.EXAM
    WatchSchedule.shut(inputs.localNow) -> WatchWall.SCHEDULE
    else -> null
}

/**
 * Pure: whether a gated app's notification is removed under [wall]. The summary
 * hold removes nothing, as on the phone (`walledNow` ignores it): a removed
 * notification is lost for good, and the hold lasts half an hour at most.
 */
fun wallRemovesNotifications(wall: WatchWall?): Boolean = wall != null && wall != WatchWall.SUMMARY

/**
 * Wear OS's own time source (`Settings.Global` "clockwork_auto_time"): 0 = from the
 * paired phone, 1 = from the network, 2 = off. A watch that takes its time from the
 * phone can leave the stock `AUTO_TIME` at 0, so reading that alone walled every
 * gated app all week with a "turn automatic time on" the user could not act on.
 */
const val CLOCKWORK_AUTO_TIME = "clockwork_auto_time"
const val CLOCKWORK_TIME_UNKNOWN = -1
private const val CLOCKWORK_TIME_OFF = 2

/** Pure: whether the watch's clock is set automatically, by either setting. */
fun clockIsAutomatic(autoTime: Int, clockworkAutoTime: Int): Boolean = when (clockworkAutoTime) {
    // Wear's own switch decides when it is set: an explicit off wins over a stale
    // AUTO_TIME = 1, or setting the watch to Saturday 15:00 would open every gated app.
    CLOCKWORK_TIME_OFF -> false
    CLOCKWORK_TIME_UNKNOWN -> autoTime != 0
    else -> true
}

/**
 * Pure: a wall-clock time in the watch's own 12/24-hour style ("14:00" or "2:00 PM"),
 * so the wall reads like the watch face (AGENTS.md §3: never a hard-coded "%02d:%02d").
 */
fun clockText(hour: Int, minute: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale).format(LocalTime.of(hour, minute))

/** Pure: "Saturday and Sunday 14:00–16:00" in the watch's clock style. */
fun openWindowText(is24Hour: Boolean): String {
    val start = WatchSchedule.OPEN_WINDOW_START_MINUTES
    val end = WatchSchedule.OPEN_WINDOW_END_MINUTES
    return "Saturday and Sunday ${clockText(start / 60, start % 60, is24Hour)}–${clockText(end / 60, end % 60, is24Hour)}"
}

/** Pure: the wall screen's lines, naming when gated apps next open. */
fun wallDetail(
    wall: WatchWall,
    localNow: LocalDateTime,
    is24Hour: Boolean = true,
    examEnd: LocalDateTime? = null
): String {
    // An exam week outlasts the weekend window: the next opening is the first one
    // after the exam, never "after this" inside a window the exam keeps shut.
    val from = if (wall == WatchWall.EXAM && examEnd != null && examEnd.isAfter(localNow)) examEnd else localNow
    val opening = WatchSchedule.nextOpening(from)
    val next = if (opening == localNow) {
        "Gated apps open again after this."
    } else {
        "Gated apps next open ${openingText(opening, localNow, is24Hour)}."
    }
    val why = when (wall) {
        WatchWall.CLOCK -> "Automatic time is off on this watch. Turn it back on in Settings."
        WatchWall.FOCUS -> "A focus session is running, breaks included."
        WatchWall.SUMMARY -> "Photograph your session summary on the phone first."
        WatchWall.EXAM -> "Exam week: gated apps stay shut all day until the exam."
        WatchWall.SCHEDULE -> "Gated apps open ${openWindowText(is24Hour)} only."
    }
    return "$why\n$next"
}

/** Pure: "today at 14:00", "tomorrow at 14:00" or "Saturday at 14:00". */
fun openingText(opening: LocalDateTime, localNow: LocalDateTime, is24Hour: Boolean = true): String {
    val days = opening.toLocalDate().toEpochDay() - localNow.toLocalDate().toEpochDay()
    val time = clockText(opening.hour, opening.minute, is24Hour)
    val day = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> opening.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }
    return "$day at $time"
}

/** A grant the walls need; none of them has a settings screen on Wear OS. */
enum class WatchWallGrant(val label: String) {
    USAGE_ACCESS("Usage access"),
    OVERLAY("Display over other apps"),
    NOTIFICATION_LISTENER("Notification access"),
    POST_NOTIFICATIONS("Notifications")
}

/**
 * Pure: which grants are missing, most important first. Without usage access no
 * app in front is ever seen, so nothing is walled; without the overlay grant the
 * wall screen cannot open from the background; without notification access gated
 * apps' notifications stay. Posting notifications only hides the service's own.
 */
fun missingWallGrants(
    usageAccess: Boolean,
    overlay: Boolean,
    notificationListener: Boolean,
    postNotifications: Boolean
): List<WatchWallGrant> = buildList {
    if (!usageAccess) add(WatchWallGrant.USAGE_ACCESS)
    if (!overlay) add(WatchWallGrant.OVERLAY)
    if (!notificationListener) add(WatchWallGrant.NOTIFICATION_LISTENER)
    if (!postNotifications) add(WatchWallGrant.POST_NOTIFICATIONS)
}

/**
 * Pure: the Progress page's walls line, or null when every grant the walls need is
 * in place. Wear OS has no screen that lists these grants, so without this line a
 * watch with walls switched off looks exactly like one with walls up. The app's own
 * notification permission is left out: the walls work without it.
 */
fun wallsStatusLine(missing: List<WatchWallGrant>): String? {
    val needed = missing.filter { it != WatchWallGrant.POST_NOTIFICATIONS }
    if (needed.isEmpty()) return null
    val list = needed.joinToString(", ") { it.label.lowercase() }
    // Notification access only clears gated apps' notifications; the walls stand without it.
    return if (needed == listOf(WatchWallGrant.NOTIFICATION_LISTENER)) "Walls up; allow $list to clear their alerts"
    else "Walls off: allow $list"
}
