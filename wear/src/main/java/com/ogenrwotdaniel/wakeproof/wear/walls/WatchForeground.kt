package com.ogenrwotdaniel.wakeproof.wear.walls

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Which app is on the watch screen, folded from activity events as a set of
 * resumed activities per package. COPY of the phone's `ResumedActivities`
 * (`distraction/ForegroundTracker.kt`), without its Google-app attribution.
 *
 * Per activity, never one timestamp per package: apps trampoline between
 * activities (WhatsApp Business broke the per-package version on the phone),
 * and an incoming call can drop the user back into an app without a fresh
 * resume. The app in front is the most recently resumed package that still has
 * something resumed.
 */
class WatchResumed {
    private val resumed = mutableMapOf<String, MutableSet<String>>()
    private val lastResumedAt = mutableMapOf<String, Long>()

    fun onResumed(packageName: String, className: String, at: Long) {
        resumed.getOrPut(packageName) { mutableSetOf() } += className
        lastResumedAt[packageName] = at
    }

    /** PAUSED, STOPPED and DESTROYED all mean the same here: no longer in front. */
    fun onLeft(packageName: String, className: String) {
        val set = resumed[packageName] ?: return
        set -= className
        if (set.isEmpty()) resumed -= packageName
    }

    /** The package in front, or null when nothing is known to be resumed. */
    fun foreground(): String? = resumed.keys.maxByOrNull { lastResumedAt[it] ?: 0L }

    fun isResumed(packageName: String): Boolean = packageName in resumed

    /** Forgets everything: a shutdown or a screen-off records no PAUSED for what was showing. */
    fun clear() {
        resumed.clear()
        lastResumedAt.clear()
    }
}

/**
 * Feeds [WatchResumed] from [UsageStatsManager.queryEvents] incrementally, reading
 * only the events since the last call, so a poll every second costs a few events.
 * Never `queryAndAggregateUsageStats`: its buckets bleed across midnight and miss
 * the app open right now.
 */
class WatchForegroundTracker(private val usm: UsageStatsManager) {
    private val state = WatchResumed()
    private var readUpTo = 0L
    private var reseed = false

    /** The package on screen now; null when unknown. Throws only what the binder throws. */
    fun current(now: Long = System.currentTimeMillis()): String? {
        val from = when {
            reseed -> now - RESEED_LOOKBACK_MILLIS
            readUpTo == 0L -> now - SEED_LOOKBACK_MILLIS
            else -> readUpTo - OVERLAP_MILLIS
        }
        val events = usm.queryEvents(from, now) ?: return state.foreground()
        val event = UsageEvents.Event()
        // Replaying the overlap is harmless: set membership after a replayed run is the same.
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val className = event.className ?: ""
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> state.onResumed(event.packageName, className, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                ACTIVITY_DESTROYED -> state.onLeft(event.packageName, className)
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP -> state.clear()
            }
        }
        readUpTo = now
        reseed = false
        return state.foreground()
    }

    /**
     * At screen-off: a lost PAUSED must not last for good. The set is rebuilt from
     * the last [RESEED_LOOKBACK_MILLIS] of history at the next read, not left empty:
     * an app the watch returns to on wrist-raise gets no new RESUMED event, and an
     * empty set would let it run unwalled.
     */
    fun forgetResumed() {
        state.clear()
        readUpTo = 0L
        reseed = true
    }

    private companion object {
        /** `UsageEvents.Event.ACTIVITY_DESTROYED` is a hidden constant (API 29+). */
        const val ACTIVITY_DESTROYED = 24
        const val SEED_LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L

        /** A watch screen rarely stays off longer than this with an app still up. */
        const val RESEED_LOOKBACK_MILLIS = 30 * 60 * 1000L

        /** Events can land in the store a moment after their stamp; a short overlap catches them. */
        const val OVERLAP_MILLIS = 2_000L
    }
}
