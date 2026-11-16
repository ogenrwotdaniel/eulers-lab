package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context

/**
 * The calendar event a session was started for (2026-11-01): its agenda key,
 * so the end-of-event check-in can tick it off, and its title for the words.
 */
data class SessionEvent(val key: String, val title: String)

/** Longest event title kept on a block; calendar titles can be paragraphs. COPIED by the watch's `WatchTimer.EVENT_TITLE_MAX_CHARS`. */
const val EVENT_TITLE_MAX_CHARS = 80

/** Pure: [title] as a block keeps it, trimmed and cut to [EVENT_TITLE_MAX_CHARS]; null when blank. */
fun eventTitleOf(title: String?): String? =
    title?.trim()?.take(EVENT_TITLE_MAX_CHARS)?.trim()?.takeIf { it.isNotEmpty() }

/** What a focus block is recorded under: its course, and the calendar event it ran for, if any. */
data class BlockLabels(val subject: String, val eventTitle: String?)

/**
 * Pure: the labels of a focus block (2026-11-15, the user: a session started
 * from a calendar event should be "recorded by the real event name", stored
 * separately so a week's time can be exported by event).
 *
 * [BlockLabels.subject] is always a course: the origin's ([followedSubject])
 * when this device follows another's session, so both copies of a shared
 * block agree, else this device's [activeSubject]. Course totals, flashcard
 * decks, course credits and per-course summary pages are keyed by it, so an
 * event session still counts under its course.
 *
 * [BlockLabels.eventTitle] is this device's own event ([ownEventTitle]), else
 * the event the followed origin runs ([followedEventTitle]), else null.
 */
fun blockLabels(
    ownEventTitle: String?,
    followedSubject: String?,
    followedEventTitle: String?,
    activeSubject: String
): BlockLabels = BlockLabels(
    subject = followedSubject?.trim()?.takeIf { it.isNotEmpty() } ?: activeSubject,
    eventTitle = eventTitleOf(ownEventTitle) ?: eventTitleOf(followedEventTitle)
)

/**
 * [SessionEvent] beside [SessionStore], kept apart from the session record so
 * that record's format did not have to change for it. Survives a reboot like
 * the session; cleared when the session ends.
 */
object SessionEventStore {
    private const val PREFS = "pomodoro_session_event"
    private const val KEY = "key"
    private const val TITLE = "title"
    private const val FOLLOWED = "followed_subject"
    private const val FOLLOWED_EVENT = "followed_event_title"

    fun save(context: Context, event: SessionEvent?) {
        val edit = prefs(context).edit()
        if (event == null) edit.clear() else edit.putString(KEY, event.key).putString(TITLE, event.title).remove(FOLLOWED).remove(FOLLOWED_EVENT)
        edit.commit()
    }

    /**
     * The course and event title a joined session's origin named; cleared with
     * [save] (null) when the session ends. `apply`, not `commit`: it is called
     * from the realign on the main thread under the phase lock, and a lost write
     * only costs the labels until the origin's next copy supplies them again.
     */
    fun saveFollowed(context: Context, subject: String?, eventTitle: String?) {
        prefs(context).edit().apply {
            if (subject.isNullOrBlank()) remove(FOLLOWED) else putString(FOLLOWED, subject)
            if (eventTitle.isNullOrBlank()) remove(FOLLOWED_EVENT) else putString(FOLLOWED_EVENT, eventTitle)
        }.apply()
    }

    fun loadFollowed(context: Context): String? = prefs(context).getString(FOLLOWED, null)

    fun loadFollowedEventTitle(context: Context): String? = prefs(context).getString(FOLLOWED_EVENT, null)

    fun load(context: Context): SessionEvent? {
        val p = prefs(context)
        val key = p.getString(KEY, null) ?: return null
        return SessionEvent(key, p.getString(TITLE, null).orEmpty())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
