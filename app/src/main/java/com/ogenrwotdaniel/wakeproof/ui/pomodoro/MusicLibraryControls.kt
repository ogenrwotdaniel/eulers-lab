package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.music.MusicGroup
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.music.NowPlaying

/**
 * The user's own music as the study sound: which songs play, shuffle, and
 * previous/next for the song in progress.
 *
 * The songs play inside Euler's Lab's timer service, so no music app is opened
 * and none needs to be let through the focus wall. Back from 1.5.0 after
 * Spotify was removed for good (2026-11-14).
 */
@Composable
fun MusicLibraryControls(
    library: PomodoroViewModel.LibraryState,
    selection: MusicSelection,
    shuffle: Boolean,
    nowPlaying: NowPlaying?,
    sessionActive: Boolean,
    message: String?,
    onLoad: () -> Unit,
    /** Reads the whole library for the album/artist picker (skipped while a folder plays). */
    onOpenPicker: () -> Unit,
    onSelect: (MusicSelection) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    // Once the media permission has been refused, Android stops showing its
    // dialog and the request answers "no" at once; without this note the
    // album/artist button looked dead.
    var refused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onLoad()
        refused = !granted
        if (granted) {
            onOpenPicker()
            picking = true
        }
    }
    // The system's own folder browser: tap a folder, done. No permission
    // dialog, and Euler's Lab can read that folder and nothing else.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onFolderPicked) }

    // On every return to the screen (App info included); the ViewModel reads the
    // library again only if the selection or the permission has changed.
    LifecycleResumeEffect(Unit) {
        onLoad()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val picked = selection as? MusicSelection.PickedFolder
        when {
            picked != null -> {
                Text(
                    text = "Folder: ${library.pickedFolderLabel ?: "Picked folder"}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val count = library.pickedFolderCount
                Text(
                    text = when (count) {
                        null -> "Counting songs…"
                        0 -> "No songs found in this folder, or it can no longer be read. " +
                            "The ambient loop plays instead."
                        else -> "$count songs, subfolders included"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            library.hasPermission && library.tracks.isNotEmpty() -> {
                val count = library.countFor(selection)
                Text(
                    text = selectionLabel(selection),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (count == 0) "No songs left here. The ambient loop plays instead."
                    else "$count songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Text(
                text = "Point Euler's Lab at the folder your songs are in. They play from " +
                    "this phone, offline, and nothing is uploaded. Until then the " +
                    "ambient loop plays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The folder browser is another app, and the focus wall shuts it for the
        // whole session; offering it then would only open onto a wall.
        Button(
            onClick = { folderPicker.launch(null) },
            enabled = !sessionActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (picked != null) "Choose a different folder" else "Choose a music folder")
        }
        if (sessionActive) {
            Text(
                text = "Folders can be chosen before a session starts. Albums and artists " +
                    "can be switched now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = {
                if (library.hasPermission) {
                    onOpenPicker()
                    picking = true
                } else {
                    permissionLauncher.launch(MusicLibrary.PERMISSION)
                }
            }
        ) { Text("Or pick an album or artist from all my music") }
        if (refused && !library.hasPermission) {
            Text(
                text = "Music access is off. To pick by album or artist, allow \"Music and " +
                    "audio\" in App info → Permissions, between sessions (Settings is shut " +
                    "during a focus session). A picked folder needs no permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            ) { Text("Open App info") }
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Shuffle", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = shuffle, onCheckedChange = onShuffleChange)
        }

        if (sessionActive && nowPlaying != null) {
            NowPlayingRow(nowPlaying = nowPlaying, onPrevious = onPrevious, onNext = onNext)
        } else {
            Text(
                text = "Plays and pauses with the timer, fading in and out like the loops.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (picking) {
        MusicPickerDialog(
            library = library,
            selected = selection,
            onPick = {
                onSelect(it)
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

/** Song, artist, and previous/next. Moves the song only, never the session. */
@Composable
private fun NowPlayingRow(
    nowPlaying: NowPlaying,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = nowPlaying.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (nowPlaying.artist.isNotBlank()) {
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onPrevious) { Text("Previous") }
        TextButton(onClick = onNext) { Text("Next") }
    }
}

private fun selectionLabel(selection: MusicSelection): String = when (selection) {
    MusicSelection.All -> "All my music"
    is MusicSelection.Folder -> "Folder: ${selection.path.trimEnd('/').substringAfterLast('/')}"
    is MusicSelection.Album -> "Album: ${selection.name}"
    is MusicSelection.Artist -> "Artist: ${selection.name}"
    is MusicSelection.PickedFolder -> "Picked folder"
}

@Composable
private fun MusicPickerDialog(
    library: PomodoroViewModel.LibraryState,
    selected: MusicSelection,
    onPick: (MusicSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Folders", "Albums", "Artists")
    val groups: List<MusicGroup> = when (tab) {
        0 -> library.folders
        1 -> library.albums
        else -> library.artists
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should play?") },
        text = {
            if (!library.allLoaded) {
                Text(text = "Reading your music…", style = MaterialTheme.typography.bodyMedium)
            } else if (library.tracks.isEmpty()) {
                Text(text = "No music found on this phone.", style = MaterialTheme.typography.bodyMedium)
            } else Column {
                OutlinedButton(
                    onClick = { onPick(MusicSelection.All) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (if (selected == MusicSelection.All) "✓ " else "") +
                            "All my music (${library.tracks.size})"
                    )
                }
                PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .fillMaxWidth()
                ) {
                    items(groups, key = { it.selection.encode() }) { group ->
                        TextButton(onClick = { onPick(group.selection) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = (if (group.selection == selected) "✓ " else "") +
                                    "${group.label} (${group.count})",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
