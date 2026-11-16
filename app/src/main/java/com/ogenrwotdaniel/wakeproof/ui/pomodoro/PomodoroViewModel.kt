package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import com.ogenrwotdaniel.wakeproof.study.RecallState
import com.ogenrwotdaniel.wakeproof.study.RecallStore
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import com.ogenrwotdaniel.wakeproof.study.SummaryState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.GeminiRepository
import com.ogenrwotdaniel.wakeproof.data.GeminiResult
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.WakeProofSettings
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThought
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import com.ogenrwotdaniel.wakeproof.music.LibraryScanKey
import com.ogenrwotdaniel.wakeproof.music.MusicGroup
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.music.NowPlaying
import com.ogenrwotdaniel.wakeproof.music.Track
import com.ogenrwotdaniel.wakeproof.music.groupsOf
import com.ogenrwotdaniel.wakeproof.music.needsMediaStoreScan
import com.ogenrwotdaniel.wakeproof.music.needsRescan
import com.ogenrwotdaniel.wakeproof.music.select
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroState
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One study-chat message: role ("user"/"assistant") + text. */
data class ChatMessage(val role: String, val text: String)

/** Prefix marking a `ParkedThought` entry as an Euler *Adversaria* open research conjecture/problem. */
const val ADVERSARIA_PREFIX = "[Q:"

/** Formats an open conjecture or problem for Euler's *Adversaria* ledger. */
fun formatAdversaria(subject: String, question: String): String =
    "$ADVERSARIA_PREFIX ${subject.trim().ifBlank { "General" }}] ${question.trim()}"

/** True when [thought] is an open problem/conjecture in the *Adversaria* ledger rather than a fleeting distraction. */
fun ParkedThought.isAdversaria(): Boolean = text.startsWith(ADVERSARIA_PREFIX)

/** Pomodoro screen state: the shared timer flow plus the Google Gemini study chat. */
@HiltViewModel
class PomodoroViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val settingsRepository: SettingsRepository,
    private val parkedThoughtDao: ParkedThoughtDao,
    private val recallStore: RecallStore,
    private val summaryStore: SessionSummaryStore,
    private val musicLibrary: MusicLibrary,
    keyStore: ApiKeyStore
) : ViewModel() {

    /** What the user's own music holds, grouped for the picker. Loaded on demand. */
    data class LibraryState(
        val hasPermission: Boolean = false,
        val loaded: Boolean = false,
        val tracks: List<Track> = emptyList(),
        val folders: List<MusicGroup> = emptyList(),
        val albums: List<MusicGroup> = emptyList(),
        val artists: List<MusicGroup> = emptyList(),
        /** "Music/Study" and its song count, when the selection is a picked folder. */
        val pickedFolderLabel: String? = null,
        val pickedFolderCount: Int? = null,
        /** Whether [tracks] holds the whole list; false while only a picked folder was read. */
        val allLoaded: Boolean = false
    ) {
        fun countFor(selection: MusicSelection): Int = tracks.select(selection).size
    }

    private val _library = MutableStateFlow(LibraryState(hasPermission = musicLibrary.hasPermission()))
    val library: StateFlow<LibraryState> = _library

    /** The song in progress when the study sound is the user's own music. */
    val nowPlaying: StateFlow<NowPlaying?> = PomodoroTimerService.nowPlaying

    private var libraryJob: kotlinx.coroutines.Job? = null
    private var pickerScanRunning = false
    /** What the last finished scan was for; a rescan happens only when it changes. */
    private var lastScan: LibraryScanKey? = null

    private val _libraryMessage = MutableStateFlow<String?>(null)
    /** A problem with the user's last music choice, shown under the controls. */
    val libraryMessage: StateFlow<String?> = _libraryMessage

    /**
     * Reads the library when what it depends on (the selection, the media
     * permission) changed since the last scan, or when [force]d. The previous
     * read is cancelled first, so an older, slower scan can never land on top of
     * a newer one.
     */
    fun loadLibrary(force: Boolean = false, pickerOpen: Boolean = false) {
        // The screen resuming right after the permission dialog asks for a plain
        // load; cancelling the picker's whole-library read for it would leave
        // the picker reading forever.
        if (!force && !pickerOpen && pickerScanRunning && libraryJob?.isActive == true) return
        libraryJob?.cancel()
        pickerScanRunning = pickerOpen
        libraryJob = viewModelScope.launch {
            // The media permission came or went (the dialog, or App info): a
            // running session retries its music instead of keeping the fallback
            // loop. Decided in the library singleton, once, whichever screen asks.
            musicLibrary.noticeAccessChange()
            val selection = settingsRepository.settings.first().musicSelection
            val decoded = MusicSelection.decode(selection)
            val granted = musicLibrary.hasPermission()
            val scanAll = needsMediaStoreScan(decoded, pickerOpen)
            val key = LibraryScanKey(selection, granted, includesAll = scanAll)
            if (!needsRescan(lastScan, key, force)) return@launch
            val tracks = if (scanAll) musicLibrary.tracks() else emptyList()
            val (folders, albums, artists) = groupsOf(tracks)
            val picked = decoded as? MusicSelection.PickedFolder
            _library.value = LibraryState(
                hasPermission = granted,
                loaded = true,
                allLoaded = scanAll,
                tracks = tracks,
                folders = folders,
                albums = albums,
                artists = artists,
                pickedFolderLabel = picked?.let { musicLibrary.folderLabel(it.treeUri) },
                pickedFolderCount = picked?.let { musicLibrary.folderTracks(it.treeUri).size }
            )
            lastScan = key
        }
    }

    /** A folder came back from the system folder browser: keep access to it and play it. */
    fun onFolderPicked(treeUri: android.net.Uri) {
        viewModelScope.launch {
            val previousEncoded = settingsRepository.settings.first().musicSelection
            val previous = MusicSelection.decode(previousEncoded)
            if (!musicLibrary.keepFolder(treeUri)) {
                _libraryMessage.value = "Euler's Lab could not keep access to that folder, so " +
                    "nothing changed. Try picking it again, or a folder inside it."
                return@launch
            }
            _libraryMessage.value = null
            val picked = MusicSelection.PickedFolder(treeUri.toString()).encode()
            settingsRepository.setMusicSelection(picked)
            // Only now, with the new folder saved, let the old one go.
            if (previous is MusicSelection.PickedFolder && previous.encode() != picked) {
                musicLibrary.releaseFolder(previous)
            }
            // The same folder picked again (its access was lost): the selection
            // is unchanged, so only this tells a running session to retry it.
            if (picked == previousEncoded) musicLibrary.invalidate()
            loadLibrary(force = true)
        }
    }

    fun setMusicSelection(selection: MusicSelection) {
        viewModelScope.launch {
            val previous = MusicSelection.decode(settingsRepository.settings.first().musicSelection)
            _libraryMessage.value = null
            // Saved first: if the save fails the old folder keeps its grant, and
            // a running session keeps reading it until the new choice reaches it.
            settingsRepository.setMusicSelection(selection.encode())
            // Leaving a picked folder for an album or artist lets its grant go too.
            if (previous is MusicSelection.PickedFolder && selection != previous) {
                musicLibrary.releaseFolder(previous)
            }
            loadLibrary()
        }
    }

    fun setMusicShuffle(value: Boolean) {
        viewModelScope.launch { settingsRepository.setMusicShuffle(value) }
    }

    /** Euler's Adversaria (open conjectures) and distracting thoughts captured mid-focus, oldest done last. */
    val thoughts: StateFlow<List<ParkedThought>> = parkedThoughtDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Optional open problem from the Adversaria ledger pinned as the focus target for the current session. */
    private val _activeConjecture = MutableStateFlow<String?>(null)
    val activeConjecture: StateFlow<String?> = _activeConjecture

    fun selectActiveConjecture(text: String?) {
        _activeConjecture.value = text
    }

    /** The recall notes and the block still waiting for one; the screen decides if it is still due. */
    val recall: StateFlow<RecallState> = recallStore.state

    /** The session summary still owed its photo; the screen decides if it is still due. */
    val summary: StateFlow<SummaryState> = summaryStore.state

    init {
        viewModelScope.launch { recallStore.load() }
        viewModelScope.launch { summaryStore.load() }
    }

    fun saveRecall(text: String) {
        viewModelScope.launch { recallStore.answer(text) }
    }

    fun skipRecall() {
        viewModelScope.launch { recallStore.skip() }
    }

    fun parkThought(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            parkedThoughtDao.insert(
                ParkedThought(text = text.trim(), createdAtMillis = System.currentTimeMillis())
            )
        }
    }

    fun logConjecture(subject: String, question: String) {
        if (question.isBlank()) return
        parkThought(formatAdversaria(subject, question))
    }

    fun setThoughtDone(thought: ParkedThought, done: Boolean) {
        viewModelScope.launch {
            parkedThoughtDao.updateContent(thought.id, thought.text, done)
            if (done && _activeConjecture.value == thought.text) {
                _activeConjecture.value = null
            }
        }
    }

    fun clearDoneThoughts() {
        viewModelScope.launch { parkedThoughtDao.clearDone() }
    }

    val timer: StateFlow<PomodoroState> = PomodoroTimerService.state
    val hasGeminiKey: Boolean = keyStore.hasKey()

    val settings: StateFlow<WakeProofSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeProofSettings())

    fun setMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFocusMusicEnabled(enabled) }
    }

    fun setBreakMusicEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBreakMusicEnabled(enabled) }
    }

    fun setFocusSound(id: String) {
        viewModelScope.launch {
            settingsRepository.setFocusSoundId(id)
        }
    }

    fun setActiveSubject(name: String) {
        viewModelScope.launch { settingsRepository.setActiveSubject(name) }
    }

    fun addSubject(name: String) {
        viewModelScope.launch { settingsRepository.addStudySubject(name) }
    }

    fun removeSubject(name: String) {
        viewModelScope.launch { settingsRepository.removeStudySubject(name) }
    }

    data class ChatState(
        val messages: List<ChatMessage> = emptyList(),
        val sending: Boolean = false,
        val socratic: Boolean = false,
        val error: String? = null
    )

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat

    fun setSocraticMode(enabled: Boolean) {
        _chat.update { it.copy(socratic = enabled) }
    }

    fun sendChat(text: String) {
        if (text.isBlank() || _chat.value.sending) return
        val history = _chat.value.messages + ChatMessage("user", text.trim())
        val useSocratic = _chat.value.socratic
        _chat.update { it.copy(messages = history, sending = true, error = null) }
        viewModelScope.launch {
            when (val result = geminiRepository.studyChat(history.map { it.role to it.text }, socratic = useSocratic)) {
                is GeminiResult.Success -> _chat.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", result.value),
                        sending = false
                    )
                }
                is GeminiResult.ApiError -> _chat.update {
                    it.copy(sending = false, error = "API error: ${result.message}")
                }
                GeminiResult.NetworkError -> _chat.update {
                    it.copy(sending = false, error = "Network error.")
                }
                GeminiResult.NoKey -> _chat.update {
                    it.copy(sending = false, error = "No API key configured.")
                }
            }
        }
    }
}
