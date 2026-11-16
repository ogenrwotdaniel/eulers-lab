package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.SummaryCaptureRoute
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Photographs the page a focus session's summary was written on: take it, look
 * at it once, keep it. Kept in [SessionSummaryStore]; nothing reads or judges
 * the page.
 *
 * The summary holds the focus wall until a photo is kept, so a camera that
 * cannot take one (broken, permission refused) or a photo that cannot be saved
 * opens [finishWithoutPhoto]: never a wall with no way out. A retake
 * ([replacing]) settles nothing and holds no wall, so it offers only "Not yet".
 */
@HiltViewModel
class SummaryCaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: SessionSummaryStore,
    private val wearStatsSync: com.ogenrwotdaniel.wakeproof.wear.WearStatsSync,
    private val liveSummaryHold: com.ogenrwotdaniel.wakeproof.data.sync.LiveSummaryHold
) : ViewModel() {

    enum class Step { CAPTURE, CONFIRM, SAVING, DONE }

    data class UiState(
        val step: Step = Step.CAPTURE,
        val photo: ByteArray? = null,
        /** The camera failed to start or to take a photo; offered a retry. */
        val cameraFailed: Boolean = false,
        /** The last photo could not be written to storage. */
        val saveFailed: Boolean = false,
        /** A photo was kept, or finishing without one asked for, before the writing time was up. */
        val tooEarly: Boolean = false,
        /** The photo was kept, yet a summary is owed afterwards: a new session's ask landed during the save. */
        val stillOwed: Boolean = false,
        /** The retake came after its window closed; the earlier photo is unchanged. */
        val retakeTooLate: Boolean = false
    )

    /**
     * The kept photo this capture retakes, or null for a new summary. A retake
     * only swaps the picture: it settles nothing that is owed.
     */
    val replacing: String? = savedStateHandle.toRoute<SummaryCaptureRoute>().replacing

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** A release under way: Keep and a second tap are ignored until it ends, or both could settle. */
    private var releasing = false

    /** The way out when no photo can be taken or kept: the summary is let go without one. */
    fun finishWithoutPhoto() {
        val step = _uiState.value.step
        if (releasing || step == Step.SAVING || step == Step.DONE) return
        releasing = true
        _uiState.update { it.copy(tooEarly = false) }
        viewModelScope.launch {
            try {
                // Refused inside the ten minutes of writing; the screen says so.
                val owedHere = store.load().pending != null
                if (store.release()) {
                    if (owedHere) tellPeers()
                    _uiState.update { it.copy(step = Step.DONE, photo = null) }
                } else {
                    _uiState.update { it.copy(tooEarly = true) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreadable or unwritable storage: the button stays for another
                // try, and the wall lifts by itself after the hold at most.
                Log.w(TAG, "session summary not released", e)
            } finally {
                releasing = false
            }
        }
    }

    fun onCameraUnavailable() {
        _uiState.update { it.copy(cameraFailed = true) }
    }

    fun retryCamera() {
        _uiState.update { it.copy(cameraFailed = false) }
    }

    fun onCaptured(bytes: ByteArray) {
        if (_uiState.value.step != Step.CAPTURE) return
        // A new photo: an earlier "not yet" was about the one thrown away.
        _uiState.update { it.copy(step = Step.CONFIRM, photo = bytes, cameraFailed = false, tooEarly = false) }
    }

    fun onRetake() {
        if (_uiState.value.step != Step.CONFIRM) return
        // A new photo starts clean. Not after "too late": another photo would be refused the same way.
        if (_uiState.value.retakeTooLate) return
        _uiState.update { it.copy(step = Step.CAPTURE, photo = null, saveFailed = false, tooEarly = false) }
    }

    fun onKeep() {
        val state = _uiState.value
        val photo = state.photo
        // The step check is what makes a double tap save one photo, not two.
        if (state.step != Step.CONFIRM || photo == null || releasing) return
        // Errors belong to the last attempt only.
        _uiState.update { it.copy(step = Step.SAVING, saveFailed = false, tooEarly = false, retakeTooLate = false) }
        viewModelScope.launch {
            // A retake settles nothing, so it neither tells peers nor reports what is owed.
            val retake = replacing != null
            val owedHere = !retake && runCatching { store.load().pending != null }.getOrDefault(false)
            val saved = try {
                // A retake settles nothing ([SessionSummaryStore.replace]); only a new photo does.
                replacing?.let { store.replace(it, photo) } ?: store.submit(photo)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.ogenrwotdaniel.wakeproof.study.SummaryTooEarlyException) {
                // The page photographed too early goes: the finished one is what is kept.
                _uiState.update { it.copy(step = Step.CAPTURE, photo = null, tooEarly = true) }
                return@launch
            } catch (e: com.ogenrwotdaniel.wakeproof.study.SummaryRetakeTooLateException) {
                _uiState.update { it.copy(step = Step.CONFIRM, retakeTooLate = true) }
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "session summary not saved", e)
                false
            }
            // Only a new ask landing between the check and the save leaves one owed now.
            val stillOwed = !retake && saved && runCatching {
                store.owed(store.load()) != null
            }.getOrDefault(false)
            // Only once it is really settled: a new session's ask landing during
            // the save must not free the tablet while the phone owes that one.
            if (saved && !stillOwed && owedHere) tellPeers()
            _uiState.update {
                if (saved) it.copy(step = Step.DONE, photo = null, saveFailed = false, stillOwed = stillOwed)
                else it.copy(step = Step.CONFIRM, saveFailed = true)
            }
        }
    }

    /**
     * Settled: the tablet and the watch may start sessions again at once. Only
     * by the device that owed it: a photo kept on the tablet, where nothing is
     * owed, must not clear the phone's hold.
     * Before the screen is told it is done, and not cancellable: done closes
     * the screen, which ends this scope, and a push cut short would leave the
     * watch refusing starts for the rest of the half hour.
     */
    private suspend fun tellPeers() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        liveSummaryHold.publish(0L)
        runCatching { wearStatsSync.push() }
    }

    private companion object {
        const val TAG = "SummaryCapture"
    }
}
