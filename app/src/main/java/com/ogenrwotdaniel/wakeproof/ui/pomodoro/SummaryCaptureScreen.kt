package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.ogenrwotdaniel.wakeproof.plan.decodeUprightJpeg
import com.ogenrwotdaniel.wakeproof.ui.verify.PhotoCaptureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The session summary: everything the session taught, written down and
 * photographed. Closes itself once the photo is kept, or let go without one
 * when the camera cannot take it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SummaryCaptureScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SummaryCaptureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val done = state.step == SummaryCaptureViewModel.Step.DONE
    val retake = viewModel.replacing != null

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(done) {
        if (done) {
            // Said here, or the wall would stay up after "saved" with no reason given.
            if (state.stillOwed) {
                android.widget.Toast.makeText(
                    toastContext,
                    // Rare: a new session's ask landed during the save.
                    "Photo kept. A new session summary is owed: the focus timer shows when it can be photographed.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            onFinish()
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (state.step) {
                    SummaryCaptureViewModel.Step.CAPTURE ->
                        if (retake) "Photograph the corrected page: it replaces the earlier photo"
                        else "Write down everything you learnt this session, then photograph the page"
                    SummaryCaptureViewModel.Step.CONFIRM -> "Can you read it? Keep this photo or take it again"
                    SummaryCaptureViewModel.Step.SAVING -> "Saving…"
                    SummaryCaptureViewModel.Step.DONE -> "Summary saved"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (state.tooEarly && !done) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Not yet: the ten minutes of writing come first. Try again once they are over.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            if (state.retakeTooLate && !done) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This photo can no longer be retaken: its time for corrections is over. " +
                        "The earlier photo is kept as it was.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            if (state.saveFailed && !done) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (retake) "The photo could not be saved; the earlier one is unchanged. Try again."
                    else "The photo could not be saved. Try again.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    SummaryCaptureViewModel.Step.CAPTURE ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(
                                onCaptured = viewModel::onCaptured,
                                onCameraFailed = viewModel::onCameraUnavailable,
                                cameraFailed = state.cameraFailed,
                                onRetry = viewModel::retryCamera,
                                failedHint = if (retake) "Try it again; the earlier photo is kept as it is. " +
                                    "A summary still owed is finished from the focus timer."
                                else "Try it again, or finish without a photo below."
                            )
                        } else {
                            Centered {
                                Text(
                                    text = "Camera permission is needed to photograph your summary.",
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                    SummaryCaptureViewModel.Step.CONFIRM -> Column {
                        state.photo?.let { photo ->
                            // Off the main thread: decoding and turning a full
                            // page would stall the screen.
                            val bitmap by produceState<Bitmap?>(initialValue = null, photo) {
                                value = withContext(Dispatchers.Default) { decodeUprightJpeg(photo) }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "The photo of your summary",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::onKeep,
                            enabled = !state.retakeTooLate,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Keep this photo")
                        }
                        if (!state.retakeTooLate) {
                            OutlinedButton(onClick = viewModel::onRetake, modifier = Modifier.fillMaxWidth()) {
                                Text("Retake")
                            }
                        }
                    }

                    SummaryCaptureViewModel.Step.SAVING,
                    SummaryCaptureViewModel.Step.DONE -> Centered { CircularProgressIndicator() }
                }
            }

            // Nothing is owed by a retake, so no way out is needed but "Not yet".
            if (!done && !retake && (state.cameraFailed || state.saveFailed || !cameraPermission.status.isGranted)) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::finishWithoutPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text("The camera is not working: finish without a photo")
                }
            }
            if (!done) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text("Not yet")
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}
