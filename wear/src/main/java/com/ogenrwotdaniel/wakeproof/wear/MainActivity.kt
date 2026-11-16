package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import com.ogenrwotdaniel.wakeproof.wear.walls.WatchWallGrants
import com.ogenrwotdaniel.wakeproof.wear.walls.wallsStatusLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The watch app (Compose for Wear OS since 2026-11-15): three swipe pages.
 *
 * * **Focus** — the session that runs, as a ring for the current phase with the
 *   countdown, subject, block dots and the block's tree; idle, today's numbers
 *   and the start options.
 * * **Today** — the phone's calendar ([AgendaController], shared with [AgendaActivity]).
 * * **Progress** — the streak, and today's focus against the goal as a ring.
 *
 * The watch RUNS its own pomodoro ([WatchTimerEngine]): start and skip a break
 * work with the phone in another room, flat, or never paired. There is no
 * pause and no stop (nuclear focus, as on the phone). Each finished focus block
 * waits on the Data Layer and lands in the phone's history — and from there in
 * cloud sync — whenever the two next meet.
 *
 * The Focus page has three states, in this order of precedence:
 * 1. the watch's own timer, if one is running;
 * 2. the phone's timer, mirrored (no pause/stop: the phone refuses them);
 * 3. idle: today's progress, plus Start (on the watch) and Start on phone —
 *    and cloud sign-in when the watch is not signed in.
 * Today is a swipe away in all three. The controls are exactly
 * [FocusFaces.watchActions] / [FocusFaces.phoneActions] / [FocusFaces.idleActions].
 *
 * The alarm is deliberately not controllable from here. See the note on
 * `WearContract.COMMAND_PATH`: an alarm you can silence from your wrist is one
 * you can silence without getting up, which is the whole thing the app exists
 * to prevent.
 *
 * ### Staying on screen during focus
 *
 * Ambient-enabled: when the wrist drops mid-session the watch keeps THIS
 * screen up, dimmed, rather than returning to the watch face — so a glance
 * shows the block, not the time and a row of notifications. In ambient the
 * controls go, the colours go to grey on black (OLED burn-in), nothing
 * animates, and the countdown drops to whole minutes, updated by the system's
 * once-a-minute ambient tick instead of a per-second loop. With nothing running
 * here there is nothing to keep up, so ambient entry finishes the activity and
 * the watch face returns as usual.
 *
 * This keeps Euler's Lab on the screen; it cannot keep you IN it. Wear OS has no
 * screen pinning for ordinary apps, so pressing a side button still leaves.
 */
class MainActivity : ComponentActivity() {

    private val scope = MainScope()
    private var renderJob: Job? = null

    /** The last read of everything the Focus and Progress pages show. */
    private var home by mutableStateOf(HomeState())
    /** The wall clock the countdown is drawn at; ticked by [render]'s loop. */
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var ambient by mutableStateOf(false)
    /** "Phone unreachable. Couldn't …": shown until the next render. */
    private var notice by mutableStateOf<String?>(null)
    /** A phone command in flight: every control is disabled until it lands or fails. */
    private var busy by mutableStateOf(false)
    /** Bumped to bring the Focus page forward (a session just started from Today). */
    private var focusRequests by mutableIntStateOf(0)
    /** The last sign-in outcome, shown in place of the cloud line until the screen closes. */
    private var cloudMessage: String? = null

    private lateinit var agenda: AgendaController

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            val local = WatchTimerEngine.state(applicationContext)
            if (!local.active) {
                // Nothing running here: let the watch face have the screen back.
                finish()
                return
            }
            ambient = true
            render()
        }

        override fun onUpdateAmbient() = render()

        override fun onExitAmbient() {
            ambient = false
            render()
        }
    }

    /**
     * Re-renders when the phone pushes new timer, stats or agenda state, so a
     * command sent from here visibly lands rather than needing the screen
     * reopened. Registered only while resumed — a watch screen that listens in
     * the background is a battery cost with nothing to show for it.
     */
    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        val paths = events.mapNotNull { it.dataItem.uri.path }
        events.release()
        if (paths.any { it == AgendaContract.PATH }) agenda.onAgendaPushed()
        if (paths.any { it.startsWith("/wakeproof") }) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The backup for the phone's locked alarms, in case boot never re-armed it.
        BackupAlarm.arm(this)
        scope.launch { BackupAlarm.pull(applicationContext) }

        agenda = AgendaController(this, scope) {
            // An event session started from the Today page: show it running.
            focusRequests++
            render()
        }
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        setContent { HomeScreen() }

        // Needed so the timer Ongoing Activity can be posted on the watch face.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Back here from [AgendaActivity] after it started a session: the Focus page shows it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focusRequests++
    }

    override fun onResume() {
        super.onResume()
        // Opening the app — from the alert or the launcher — is the acknowledgement.
        Buzzer.stop()
        Wearable.getDataClient(this).addListener(dataListener)
        render()
        agenda.onResume()
        scope.launch { FocusSessionOutbox.flush(applicationContext) }
        CloudOutbox.kick(applicationContext)
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(dataListener)
        renderJob?.cancel()
        super.onPause()
    }

    @androidx.compose.runtime.Composable
    private fun HomeScreen() {
        EulerTheme {
            AppScaffold(timeText = { TimeText() }) {
                val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
                LaunchedEffect(focusRequests) {
                    if (focusRequests > 0) pagerState.animateScrollToPage(PAGE_FOCUS)
                }
                val state = home
                val face = state.face(now, ambient)
                if (ambient && face != null) {
                    ScreenScaffold { _ -> AmbientFocus(face, ::time) }
                    return@AppScaffold
                }
                HorizontalPagerScaffold(pagerState = pagerState) {
                    HorizontalPager(state = pagerState) { page ->
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            when (page) {
                                PAGE_FOCUS -> if (face != null) {
                                    ScreenScaffold { _ ->
                                        RunningFocus(face, notice, busy, ::time, ::onFocusAction)
                                    }
                                } else if (state.loaded) {
                                    IdleFocus(state.numbers, state.idleActions, notice, busy, ::onIdleAction)
                                } else {
                                    ScreenScaffold { _ -> }
                                }
                                PAGE_TODAY -> AgendaPage(agenda)
                                else -> ProgressPage(state.numbers)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads everything again and, while a countdown runs on screen, ticks [now]
     * on the countdown's own second boundaries. Also where a phase end reached
     * on screen is handed to the engine (the alarm may get there first; the
     * engine credits the block once either way).
     */
    private fun render() {
        renderJob?.cancel()
        notice = null
        renderJob = scope.launch {
            val app = applicationContext
            val local = WatchTimerEngine.state(app)
            // A dead phone session (process killed, no final push) reads as idle.
            val phone = if (local.active) null
            else TimerSnapshot.read(app)?.takeIf { it.liveAt(System.currentTimeMillis()) }
            val stats = WearStatsReader.read(app)
            val pending = FocusSessionOutbox.pending(app)
            val zone = ZoneId.systemDefault()
            val today = focusToday(stats, pending, LocalDate.now(zone).toEpochDay(), zone)
            home = HomeState(
                loaded = true,
                local = local.takeIf { it.active },
                phone = phone,
                numbers = TodayNumbers(
                    statsPresent = stats.present,
                    streak = stats.streak,
                    asterisk = stats.asterisk,
                    focusMinutes = today.minutes,
                    goal = stats.focusGoal,
                    unsyncedBlocks = today.unsyncedBlocks,
                    cloudLine = FocusFaces.cloudLine(
                        cloudMessage,
                        WatchCloud.configured,
                        if (WatchCloud.configured) WatchCloud.accountLabel(app) else null,
                        if (WatchCloud.configured) CloudOutbox.waiting(app) else 0
                    ),
                    wallsLine = wallsStatusLine(WatchWallGrants.missing(app))
                ),
                idleActions = FocusFaces.idleActions(WatchCloud.canSignInHere, WatchCloud.signedIn(app))
            )
            now = System.currentTimeMillis()
            when {
                local.active -> tickLocal(local)
                phone != null && phone.running -> tickPhone(phone)
            }
        }
    }

    private suspend fun tickLocal(state: WatchTimerState) {
        if (ambient) {
            // The system's minute tick calls render() again; nothing loops here.
            if (state.isDue(System.currentTimeMillis())) {
                WatchTimerEngine.advanceIfDue(applicationContext)
                render()
            }
            return
        }
        if (!state.running) return
        while (coroutineContext.isActive) {
            val current = System.currentTimeMillis()
            now = current
            if (state.remainingSecondsAt(current) <= 0L) {
                // The alarm will get there too; whichever is first credits the
                // block, the other finds nothing due.
                WatchTimerEngine.advanceIfDue(applicationContext)
                render()
                return
            }
            val msLeft = state.phaseEndsAt - current
            delay((msLeft - 1) % 1000L + 1)
        }
    }

    /** Self-ticks from the phone's absolute finish time: the phone only pushes on transitions. */
    private suspend fun tickPhone(timer: TimerSnapshot) {
        while (coroutineContext.isActive) {
            val current = System.currentTimeMillis()
            now = current
            val msLeft = (timer.finishAtMillis - current).coerceAtLeast(0L)
            if (msLeft <= 0L) {
                // No push for the next phase within the grace = the phone died
                // mid-session ([TimerSnapshot.liveAt]); fall back to idle then.
                val untilStale = timer.finishAtMillis + TimerSnapshot.RUNNING_STALE_MILLIS -
                    System.currentTimeMillis()
                delay(untilStale.coerceAtLeast(0L) + 1_000L)
                render()
                return
            }
            delay((msLeft - 1) % 1000L + 1)
        }
    }

    private fun onFocusAction(action: FocusAction) {
        when (action) {
            FocusAction.RESUME -> runThenRender { WatchTimerEngine.resume(applicationContext) }
            FocusAction.SKIP_BREAK -> runThenRender { WatchTimerEngine.skipBreak(applicationContext) }
            FocusAction.CONTINUE_HERE -> runThenRender {
                // The same session on the wrist: it buzzes here at every phase even
                // with the phone left behind, and a block finished on both is recorded once.
                val session = home.phone?.session ?: return@runThenRender
                val refusal = WatchTimerEngine.startRefusal(applicationContext)
                if (refusal != null) toast(refusal) else WatchTimerEngine.follow(applicationContext, session)
            }
            FocusAction.PHONE_RESUME -> sendToPhone("Resume", TimerCommands.RESUME)
        }
    }

    private fun onIdleAction(action: IdleAction) {
        when (action) {
            is IdleAction.Start -> runThenRender {
                val refusal = WatchTimerEngine.startRefusal(applicationContext)
                if (refusal != null) toast(refusal) else WatchTimerEngine.start(applicationContext, action.session)
            }
            IdleAction.StartOnPhone -> sendToPhone("Start on phone", TimerCommands.START)
            IdleAction.SignIn -> runThenRender { signInHere() }
        }
    }

    private fun runThenRender(action: suspend () -> Unit) {
        scope.launch {
            action()
            render()
        }
    }

    /**
     * Fires a command and reports honestly if it did not land.
     *
     * There is no optimistic local state: the phone owns ITS timer, and the
     * screen changes only when the phone pushes back. A watch that showed
     * "paused" while the phone kept counting would be worse than one that
     * showed nothing.
     */
    private fun sendToPhone(label: String, command: String) {
        scope.launch {
            // The phone would refuse it with a message nobody at the watch sees.
            val refusal = if (command == TimerCommands.START) WatchTimerEngine.startRefusal(applicationContext) else null
            if (refusal != null) {
                toast(refusal)
                return@launch
            }
            busy = true
            val delivered = TimerCommands.send(applicationContext, command)
            if (!delivered) notice = "Phone unreachable.\nCouldn't ${label.lowercase()}."
            busy = false
        }
    }

    /**
     * Google sign-in on the watch itself, for the direct cloud upload. The
     * phone's Settings → Sync → "Sign in the watch" does the same without the
     * small-screen picker; this is for when the phone is not around.
     */
    private suspend fun signInHere() {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val token = try {
            val response = CredentialManager.create(this).getCredential(this, request)
            GoogleIdTokenCredential.createFrom(response.credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            return
        } catch (e: GetCredentialException) {
            cloudMessage = "Couldn't sign in here.\nOn the phone: Settings → Sync → Sign in the watch."
            return
        }
        cloudMessage = WatchCloud.signIn(applicationContext, token).fold(
            onSuccess = { "Signed in as $it" },
            onFailure = { "Sign-in failed: ${it.message}" }
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private val timeFormat by lazy { DateFormat.getTimeFormat(this) }

    private fun time(millis: Long): String = timeFormat.format(Date(millis))

    /**
     * Any touch silences a phase-change buzz: the app may already be on screen
     * (ambient) when a phase ends, so no onResume follows to do it.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Buzzer.stop()
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PAGE_COUNT = 3
        const val PAGE_FOCUS = 0
        const val PAGE_TODAY = 1
    }
}

/** One read of the watch's and the phone's timers and today's numbers. */
data class HomeState(
    val loaded: Boolean = false,
    /** The watch's own run; null when none is active. */
    val local: WatchTimerState? = null,
    /** The phone's live run, mirrored; only read when the watch runs nothing. */
    val phone: TimerSnapshot? = null,
    val numbers: TodayNumbers = TodayNumbers(false, 0, false, 0, 0, 0, null),
    val idleActions: List<IdleAction> = emptyList()
) {
    /** The running session's face at [now], the watch's own first; null when idle. */
    fun face(now: Long, ambient: Boolean): RunningFace? =
        local?.let { FocusFaces.watchFace(it, now, ambient) }
            ?: phone?.let { FocusFaces.phoneFace(it, now) }
}
