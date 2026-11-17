package com.ogenrwotdaniel.wakeproof.launcher

import kotlinx.coroutines.flow.map
import android.app.AlarmManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AgendaRepository
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.StreakInfo
import com.ogenrwotdaniel.wakeproof.data.TodayStatus
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.distraction.AppGate
import com.ogenrwotdaniel.wakeproof.distraction.WallRulesRepository
import com.ogenrwotdaniel.wakeproof.agenda.EventReminders
import com.ogenrwotdaniel.wakeproof.agenda.startEventFocus
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.wear.WearAgendaSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the minimalist home screen. */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsRepository: UsageStatsRepository,
    private val settingsRepository: SettingsRepository,
    private val agendaRepository: AgendaRepository,
    private val wallRulesRepository: WallRulesRepository,
    private val examDao: com.ogenrwotdaniel.wakeproof.data.db.ExamDao,
    limitRepository: DistractionLimitRepository,
    statsRepository: StatsRepository,
    private val topThreeStore: com.ogenrwotdaniel.wakeproof.plan.TopThreeStore
) : ViewModel() {

    /** Every evening's top three; the screen picks what today shows. */
    val topThrees: StateFlow<com.ogenrwotdaniel.wakeproof.plan.TopThrees> = topThreeStore.evenings

    /** Reads the stored lists; called on each resume, as the file loads lazily. */
    fun loadTopThree() {
        viewModelScope.launch { runCatching { topThreeStore.load() } }
    }

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** Every launchable app. Order carries no meaning — the screen shuffles it. */
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _pinned = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** The permanent tools at the foot of the home screen; see [permanentPackages]. */
    val pinned: StateFlow<List<InstalledApp>> = _pinned.asStateFlow()

    private val _gated = MutableStateFlow<Set<String>>(emptySet())

    /** Gated social, video, messaging, portal and browser apps ([AppGate]). */
    val gated: StateFlow<Set<String>> = _gated.asStateFlow()

    private val _agenda = MutableStateFlow<List<AgendaEvent>>(emptyList())

    /** Today's calendar events ([onAgenda]), for the home-screen agenda. */
    val agenda: StateFlow<List<AgendaEvent>> = _agenda.asStateFlow()

    val doneEventKeys: StateFlow<Set<String>> = agendaRepository.doneKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun hasCalendarPermission(): Boolean = agendaRepository.hasPermission()

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, WakeProofSettings())

    val limits: StateFlow<Map<String, AppLimit>> = limitRepository.limits
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val today: StateFlow<TodayStatus> = statsRepository.observeToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayStatus())

    /** Today's focus goal as scored: the user's own, raised before an exam. */
    val focusGoalMinutes: StateFlow<Int> = statsRepository.observeFocusGoal().map { it.minutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 120)

    val streak: StateFlow<StreakInfo> = statsRepository.observeStreak()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreakInfo())

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state

    /**
     * Re-read on every resume rather than via a package broadcast receiver: the
     * home screen resumes every time it is looked at, which is exactly when a
     * freshly installed app has to be there.
     */
    fun refresh() {
        viewModelScope.launch {
            val apps = usageStatsRepository.installedApps()
            val gateBrowsers = wallRulesRepository.current().gateBrowsers
            _gated.value = withContext(Dispatchers.IO) {
                AppGate.gatedPackages(context, apps.map { it.packageName }, gateBrowsers)
            }
            _pinned.value = pinnedApps(context, apps)
            _apps.value = apps
            val read = withContext(Dispatchers.IO) { agendaRepository.readCalendar() }
            val today = LocalDate.now()
            if (read == null) {
                // Calendar access gone: the agenda and the watch empty, as they always did.
                _agenda.value = emptyList()
                WearAgendaSync.push(context, emptyList())
            } else {
                // The agenda shows every row that could be read. The watch gets only a whole
                // read, keeping its last one otherwise.
                _agenda.value = read.events.filter { onAgenda(it, today) }
                if (read.whole) WearAgendaSync.push(context, read.events)
            }
        }
    }

    /** Minutes spent in [packageName] today, for the mindful pause. */
    suspend fun usedMinutesToday(packageName: String): Long =
        (usageStatsRepository.todayForegroundMillisFor(setOf(packageName))[packageName] ?: 0L) / 60_000

    /** The next alarm the system knows of — WakeProof's or any other clock app's. */
    fun nextAlarmMillis(): Long? =
        context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

    /**
     * Whether [packageName] may be pinned. Social, video and messaging apps and
     * capped apps never can: a pin is a shortcut, and those are exactly the apps
     * that should take the long way round. Nor can the permanent tools, which
     * are already there.
     */
    fun canPin(packageName: String): Boolean =
        packageName !in _gated.value && packageName !in limits.value &&
            _pinned.value.none { it.packageName == packageName }

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().launcherFavorites
            settingsRepository.setLauncherFavorites(
                when {
                    packageName in current -> current - packageName
                    canPin(packageName) -> (current + packageName).takeLast(MAX_FAVORITES)
                    else -> current
                }
            )
        }
    }

    fun setEventDone(event: AgendaEvent, done: Boolean) {
        viewModelScope.launch {
            agendaRepository.setDone(event, done, _agenda.value.map { it.key }.toSet())
            // A ticked event needs no reminder; an unticked one gets it back.
            runCatching { EventReminders.reschedule(context) }
        }
    }

    /**
     * Fallback wall check for [LauncherActivity] when [AppBlockService] is not
     * running (for instance before usage access is granted): the night, exam-week,
     * daily-window and bedtime walls over gated apps, so tapping one on Euler's Lab
     * Home still hits [BlockedActivity]. The order is [gateVerdict]'s, not a copy
     * of it. Exam week here is read-only (the service alone records holds), so an
     * exam added and deleted while the service is down is not held.
     */
    suspend fun fallbackWallBefore(packageName: String): android.content.Intent? {
        if (packageName !in _gated.value) return null
        val rules = wallRulesRepository.current()
        val now = java.time.LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        val phone = com.ogenrwotdaniel.wakeproof.distraction.longNightDevice(context)
        val window = com.ogenrwotdaniel.wakeproof.distraction.gatedOpenWindow(rules)
            ?: (com.ogenrwotdaniel.wakeproof.distraction.RULE_OFF_MINUTES to com.ogenrwotdaniel.wakeproof.distraction.RULE_OFF_MINUTES)
        val exam = fallbackExam()
        val verdict = com.ogenrwotdaniel.wakeproof.distraction.gateVerdict(
            essential = false,
            focusSessionActive = false,
            onFocusList = false,
            capSpent = false,
            gated = true,
            outsideWindow = com.ogenrwotdaniel.wakeproof.distraction.outsideOpenWindow(minuteOfDay, window.first, window.second),
            bedtime = com.ogenrwotdaniel.wakeproof.distraction.bedtimeActive(minuteOfDay, rules.bedtimeStartMinutes),
            examWeek = exam != null,
            sleeping = com.ogenrwotdaniel.wakeproof.distraction.nightWallActive(minuteOfDay, phone)
        )
        val label = usageStatsRepository.appLabel(packageName)
        fun clock(m: Int): String = com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime(context, m / 60, m % 60)
        val (headline, body) = when (verdict) {
            com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.SLEEP_WALL ->
                com.ogenrwotdaniel.wakeproof.distraction.nightWallCopy(minuteOfDay, phone, ::clock)
            com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.EXAM_WALL ->
                "Exam week" to com.ogenrwotdaniel.wakeproof.distraction.AppBlockService.examWallBody(label, exam)
            com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.WINDOW_WALL ->
                com.ogenrwotdaniel.wakeproof.distraction.windowWallCopy(label, minuteOfDay, window.first, window.second, ::clock)
            com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.BEDTIME_WALL ->
                "Bedtime" to "$label and every other gated app are behind the bedtime wall until " +
                    "${clock(com.ogenrwotdaniel.wakeproof.distraction.BEDTIME_END_MINUTES)}."
            else -> return null
        }
        return com.ogenrwotdaniel.wakeproof.distraction.BlockedActivity.ruleIntent(context, headline, body)
    }

    private var fallbackExamAt = 0L
    private var fallbackExamDay = -1L
    private var fallbackExamValue: com.ogenrwotdaniel.wakeproof.distraction.ExamWeek? = null

    /** Exam week for the fallback, read at most once a minute (and again on a new day), not on every tap. */
    private suspend fun fallbackExam(): com.ogenrwotdaniel.wakeproof.distraction.ExamWeek? {
        val now = android.os.SystemClock.elapsedRealtime()
        val today = java.time.LocalDate.now().toEpochDay()
        if (today == fallbackExamDay && now - fallbackExamAt < 60_000L) return fallbackExamValue
        fallbackExamValue = com.ogenrwotdaniel.wakeproof.distraction.AppBlockService.fallbackExamWeek(context, examDao)
        fallbackExamAt = now
        fallbackExamDay = today
        return fallbackExamValue
    }

    /** Starts a session for what is left of [event]; see [startEventFocus]. */
    fun focusForEvent(event: AgendaEvent): Boolean = startEventFocus(context, event)

    companion object {
        /** A home screen of twenty favourites is an app drawer with extra steps. */
        const val MAX_FAVORITES = 8

        /**
         * Whether [event] belongs on the home screen's agenda: today only. Was
         * today plus the next two days, which on a real calendar ran to "+ 18
         * more" — a home screen showing the week is a planner, not a glance at
         * what today asks of you.
         *
         * All-day events must fall exactly on today. The provider stores them
         * as UTC midnights, so east of Greenwich yesterday's all-day event
         * still overlaps the start of the local day and comes back from the
         * query; timed events that began before today and run into it stay.
         */
        fun onAgenda(event: AgendaEvent, today: LocalDate): Boolean =
            if (event.allDay) eventDay(event) == today else eventDay(event) <= today
    }
}
