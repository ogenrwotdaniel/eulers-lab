package com.ogenrwotdaniel.wakeproof.wear

/**
 * The calendar event a focus session runs for (2026-11-15), on the three Data
 * Layer paths that carry a session or its blocks. Part of [WearContract], kept
 * in a file of its own only because `WearStatsSync.kt` was being changed by a
 * separate piece of work at the time; fold them into [WearContract] when it is
 * next touched. Absent on an item from an older build, which reads as "no event".
 *
 * MUST match wear `TimerContract.KEY_EVENT_TITLE`, `WatchSessionContract.KEY_EVENT_TITLE`
 * and `SessionContract.KEY_EVENT_TITLE`.
 */
object WearEventKeys {
    /** Phone -> watch, on [WearContract.TIMER_PATH]: the event of the phone's session. */
    const val KEY_TIMER_EVENT_TITLE = "t_event_title"

    /** Watch -> phone, on [WearContract.WATCH_SESSION_PATH]: the event of the watch's session. */
    const val KEY_WS_EVENT_TITLE = "ws_event_title"

    /** Watch -> phone, on each `SESSION_PATH_PREFIX` block item: the event the block ran for. */
    const val KEY_SESSION_EVENT_TITLE = "s_event_title"
}
