package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.READING_SESSION_MINUTES
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import kotlinx.coroutines.delay

/**
 * The 12:00 reading alarm's mission (2026-11-20, the user: "create a new alarm
 * of habit to force me to read 30 minutes a day"): it is silenced only by a
 * focus session running on this device, normally the 30-minute reading session
 * started here. A session already running (or a peer's, which a start joins)
 * counts too: the half hour is spent focused either way.
 *
 * A start can be refused (a session summary still owed, or the storage still
 * locked); then the screen says so and, besides the ordinary snooze, opens the
 * emergency dismiss, because nothing else could silence the alarm.
 */
@Composable
fun ReadingVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    emergencyHoldSeconds: Int = 60
) {
    val context = LocalContext.current
    val timer by PomodoroTimerService.state.collectAsStateWithLifecycle()
    var attempt by rememberSaveable { mutableStateOf(0) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var verified by rememberSaveable { mutableStateOf(false) }
    val verify by rememberUpdatedState(onVerified)

    LaunchedEffect(timer.active) {
        if (timer.active && !verified) {
            verified = true
            verify()
        }
    }
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        // The service reads settings and the summary store before it starts.
        delay(START_GRACE_MILLIS)
        if (!PomodoroTimerService.state.value.active) refused = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (verified) {
                Text(
                    text = "Reading time. $READING_SESSION_MINUTES minutes, phone down.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                AutoReturnFooter(onFinish = onFinish)
                return@Column
            }
            Text(
                text = "Lunch reading",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Open your book or paper. The alarm stops when the $READING_SESSION_MINUTES-minute " +
                    "reading session starts, and the session cannot be stopped.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = {
                    refused = false
                    PomodoroTimerService.start(context, READING_SESSION_MINUTES)
                    attempt += 1
                },
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) { Text("Start $READING_SESSION_MINUTES minutes of reading") }
            if (refused) {
                Text(
                    text = "The session did not start. If a session summary is owed, finish it on the " +
                        "timer screen first, then come back and start reading.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            if (snoozesLeft > 0) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                ) { Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)") }
            }
            if (refused) {
                HoldToDismissButton(
                    label = "Emergency dismiss — hold ${emergencyHoldSeconds}s",
                    holdSeconds = emergencyHoldSeconds,
                    onCompleted = onEmergencyDismiss
                )
            }
        }
    }
}

/** How long a start may take before the screen calls it refused. */
private const val START_GRACE_MILLIS = 5_000L
