package com.ogenrwotdaniel.wakeproof.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Tomorrow's top three: three lines and a save (see [TopThreeViewModel]).
 *
 * [ringing] true is the top-three alarm's screen (`TOP_THREE_MINUTES`): saving silences it
 * ([onVerified]), and it has the ordinary snooze and emergency dismiss, since
 * the slot is fixed but not hard. False is the same screen opened from Home.
 */
@Composable
fun TopThreeScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    ringing: Boolean = false,
    onVerified: () -> Unit = {},
    onEmergencyDismiss: () -> Unit = {},
    snoozesLeft: Int = 0,
    onSnooze: () -> Unit = {},
    viewModel: TopThreeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.saved) {
        if (!state.saved) return@LaunchedEffect
        if (ringing) onVerified() else onFinish()
    }
    // Quiet enough to think and type over, never silent.
    LaunchedEffect(ringing) {
        if (ringing) com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService.setVolume(context, 0.3f)
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ringing && state.saved) {
                Spacer(Modifier.height(48.dp))
                Text(
                    text = "Saved. They wait on Home in the morning.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Now to bed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.ogenrwotdaniel.wakeproof.ui.verify.AutoReturnFooter(onFinish = onFinish)
                return@Column
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (ringing) "BEFORE BED" else "TOMORROW",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = "Tomorrow's top three", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "The three things that matter most. One line is enough.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            val hints = listOf(
                "Research, a proof or an experiment",
                "A coursework or engineering deliverable",
                "A paper to read closely"
            )
            state.items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    OutlinedTextField(
                        value = item,
                        onValueChange = { viewModel.setItem(index, it) },
                        enabled = state.loaded,
                        placeholder = { Text(hints.getOrElse(index) { "" }) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (index == state.items.lastIndex) ImeAction.Done else ImeAction.Next
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { viewModel.save(ringing) },
                enabled = state.canSave,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) { Text(if (ringing) "Save and go to bed" else "Save") }
            if (!ringing) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                ) { Text("Not now") }
            }
            if (ringing && snoozesLeft > 0) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                ) {
                    Text(
                        "Snooze ${com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore.SNOOZE_MINUTES} min " +
                            "($snoozesLeft left today)"
                    )
                }
            }
            if (ringing && state.emergencyAvailable) {
                com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton(
                    label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s",
                    holdSeconds = state.emergencyHoldSeconds,
                    onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                )
            }
        }
    }
}
