package com.ogenrwotdaniel.wakeproof.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.plan.PagePhoto
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The saved session-summary photos, newest first; null until the first read. */
@HiltViewModel
class SessionSummariesViewModel @Inject constructor(
    private val store: SessionSummaryStore
) : ViewModel() {

    val summaries: StateFlow<List<PagePhoto>?> = store.revision
        // A folder that cannot be read (storage still locked) shows as empty.
        .map { runCatching { store.list() }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Read once as the viewer opens ([SessionSummaryStore.canRetake]). */
    fun canRetake(photo: PagePhoto): Boolean = store.canRetake(photo)

    fun delete(photo: PagePhoto) {
        viewModelScope.launch { runCatching { store.delete(photo) } }
    }
}

/**
 * The library of session summaries: the page written at the end of each focus
 * session, newest first, each opened full screen to read. On this device only.
 */
@Composable
fun SessionSummariesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRetake: (PagePhoto) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionSummariesViewModel = hiltViewModel()
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf<PagePhoto?>(null) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Session summaries & derivations", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "The closed-book Euler Derivation Protocol page you write in the ten " +
                            "minutes after each focus session (1. Theorems & definitions, 2. Crux " +
                            "derivation or counterexample, 3. Next open question). Kept on this " +
                            "device for the latest 90 days. A photo can be retaken within " +
                            "ten minutes of taking it, as long as Android has not closed the app in the meantime: open it and tap Retake.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                        Text("Photograph a summary now")
                    }
                    if (summaries?.isEmpty() == true) {
                        Text(
                            text = "No summaries yet. The first one is asked for after your next " +
                                "focus session.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            items(summaries.orEmpty(), key = { it.file.name }) { photo ->
                PhotoThumbnail(
                    photo = photo,
                    note = photo.subject,
                    openLabel = "Open this summary",
                    onOpen = { open = photo }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }

    open?.let { photo ->
        // Read as the viewer opens: a page read back and found wrong is
        // retaken at once, not left open across the ten minutes.
        val canRetake = remember(photo) { viewModel.canRetake(photo) }
        PhotoViewer(
            photo = photo,
            what = "Session summary",
            onClose = { open = null },
            onDelete = {
                viewModel.delete(photo)
                open = null
            },
            onRetake = if (canRetake) {
                {
                    open = null
                    // Asked again at the tap: a viewer left open must not offer
                    // a retake whose keep would then be refused.
                    if (viewModel.canRetake(photo)) onRetake(photo)
                    else android.widget.Toast.makeText(
                        context, "Its time for corrections is over: the photo stays as it is.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else null
        )
    }
}
