package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The confirm area over the agenda list: a session offer, or why there is none. */
sealed interface AgendaDialog {
    val event: WatchAgendaEvent

    data class Confirm(override val event: WatchAgendaEvent, val minutes: Int) : AgendaDialog
    data class Refused(override val event: WatchAgendaEvent, val reason: AgendaRefusal) : AgendaDialog
}

/**
 * "Today" (2026-11-04; Compose since 2026-11-15): the phone's timed calendar
 * events for today, pushed on `/wakeproof/agenda`, and what a tap on one does.
 * Shared by [MainActivity]'s Today page and [AgendaActivity] (the tile's and
 * complication's way in), so both behave the same.
 *
 * * The checkbox ticks the event — on the PHONE ([TimerCommands.agendaDone]); the
 *   row changes only when the phone pushes the list back, never optimistically.
 * * Tapping a title asks "Focus until 15:00 (1 h 25 m)?" and starts the
 *   WATCH's own timer for exactly what is left of the event
 *   ([WatchTimerEngine.startForEvent]) — the same nuclear session as any
 *   other. What cannot be focused for says WHY ([AgendaOffers.offer]), with
 *   "Tick it off" alongside — never a silent tick. A phone session counts:
 *   one session at a time across devices.
 *
 * Compose state, read by [AgendaPage]; [onStarted] runs after a session started.
 */
class AgendaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStarted: () -> Unit
) {
    private val app = context.applicationContext

    var agenda by mutableStateOf(WatchAgendaList())
        private set
    var today by mutableLongStateOf(LocalDate.now(ZoneId.systemDefault()).toEpochDay())
        private set

    /** A line above the list: "Phone unreachable", a refusal. Cleared by the next push. */
    var status by mutableStateOf<String?>(null)

    /** The confirm or refusal on screen; while set, a push does not yank it away. */
    var dialog by mutableStateOf<AgendaDialog?>(null)
        private set

    /** The tile row's event, opened once on the next list read and then forgotten. */
    var pendingEventKey: String? = null

    /** Today's events, or none when the push is from another day. */
    val events: List<WatchAgendaEvent> get() = agenda.forDay(today)

    /** The watch's own 12/24-hour setting ([DateFormat.is24HourFormat]). */
    fun time(millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    /** A push from the phone: re-read the list unless a confirm is open. */
    fun onAgendaPushed() {
        if (dialog != null) return
        status = null
        reload()
    }

    /** Reloads when no confirm is open, or when a tile row asks for one. */
    fun onResume() {
        if (dialog == null || pendingEventKey != null) reload()
    }

    /** Closes any confirm and reads the list again; opens the pending tile event, if any. */
    fun reload() {
        dialog = null
        scope.launch {
            val read = WatchAgenda.read(app)
            val day = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
            agenda = read
            today = day
            pendingEventKey?.let { key ->
                pendingEventKey = null
                val event = read.forDay(day).firstOrNull { it.key == key }
                if (event != null) {
                    onTitle(event)
                    return@launch
                }
                status = "That event is no longer on today's list."
            }
        }
    }

    fun onTitle(event: WatchAgendaEvent) {
        scope.launch {
            dialog = when (val offer = currentOffer(event)) {
                is AgendaOffer.Offer -> AgendaDialog.Confirm(event, offer.minutes)
                is AgendaOffer.Refused -> AgendaDialog.Refused(event, offer.reason)
            }
        }
    }

    /** Asks the phone to tick [event]; the list changes when its push lands. */
    fun tick(event: WatchAgendaEvent) {
        if (event.done) return
        scope.launch {
            val delivered = TimerCommands.send(app, TimerCommands.agendaDone(event.key))
            if (!delivered) {
                status = "Phone unreachable. Couldn't tick it."
                reload()
            }
        }
    }

    /** "Tick it off" on a refusal: tick, then back to the list. */
    fun tickFromDialog(event: WatchAgendaEvent) {
        tick(event)
        reload()
    }

    /** "Back" / "Not now". */
    fun dismiss() = reload()

    /** "Start" on a confirm. Re-checked at the tap: the confirm may have sat open. */
    fun start(event: WatchAgendaEvent) {
        scope.launch {
            val again = currentOffer(event)
            if (again is AgendaOffer.Refused) {
                status = refusalText(again.reason)
                dialog = AgendaDialog.Refused(event, again.reason)
                return@launch
            }
            if (WatchTimerEngine.startForEvent(app, event.beginMillis, event.endMillis, event.title)) {
                dialog = null
                onStarted()
            } else {
                val reason = (currentOffer(event) as? AgendaOffer.Refused)?.reason
                status = reason?.let(::refusalText)
                    ?: WatchTimerEngine.startRefusal(app) ?: "Couldn't start."
                if (reason != null) dialog = AgendaDialog.Refused(event, reason) else reload()
            }
        }
    }

    fun refusalText(reason: AgendaRefusal): String = AgendaOffers.refusalText(reason, ::time)

    /** The offer as of now: the watch's timer and the phone's mirrored one. */
    private suspend fun currentOffer(event: WatchAgendaEvent): AgendaOffer = AgendaOffers.offer(
        event,
        WatchTimerEngine.state(app),
        TimerSnapshot.read(app),
        System.currentTimeMillis()
    )
}
