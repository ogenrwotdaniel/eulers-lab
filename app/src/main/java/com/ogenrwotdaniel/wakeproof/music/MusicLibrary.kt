package com.ogenrwotdaniel.wakeproof.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One song on the phone, as MediaStore indexes it or as a picked-folder walk found it. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    /** Folder relative to the storage root, e.g. "Music/Study/". */
    val folder: String,
    val durationMs: Long,
    /**
     * Set for a song found by walking a folder the user picked; null for one
     * MediaStore indexed. Kept as a string so the class stays testable on the JVM.
     */
    val documentUri: String? = null
) {
    /** Where the file is read from. */
    val uri: Uri
        get() = documentUri?.toUri() ?: ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id
        )
}

/** What the timer's music player shows for the song in progress. */
data class NowPlaying(val title: String, val artist: String)

/**
 * Which part of the library plays during a session. Stored as a string in
 * `WakeProofSettings.musicSelection` via [encode] / [decode]; the encoding is the
 * one 1.5.0 wrote, so a choice saved then comes back unchanged.
 */
sealed interface MusicSelection {
    /** Every song MediaStore lists (needs READ_MEDIA_AUDIO). */
    data object All : MusicSelection
    data class Folder(val path: String) : MusicSelection
    data class Album(val albumId: Long, val name: String) : MusicSelection
    data class Artist(val name: String) : MusicSelection

    /**
     * A folder picked with the system's own folder browser, subfolders included.
     * [treeUri] is the `content://` tree URI that browser hands back.
     */
    data class PickedFolder(val treeUri: String) : MusicSelection

    fun encode(): String = when (this) {
        All -> "all"
        is Folder -> "folder:$path"
        is Album -> "album:$albumId:$name"
        is Artist -> "artist:$name"
        is PickedFolder -> "tree:$treeUri"
    }

    companion object {
        /** Anything unreadable plays everything: the user asked for music, not silence. */
        fun decode(value: String?): MusicSelection {
            if (value == null) return All
            val kind = value.substringBefore(':')
            val rest = value.substringAfter(':', "")
            return when {
                kind == "folder" && rest.isNotEmpty() -> Folder(rest)
                kind == "artist" && rest.isNotEmpty() -> Artist(rest)
                kind == "tree" && rest.isNotEmpty() -> PickedFolder(rest)
                kind == "album" -> {
                    val id = rest.substringBefore(':').toLongOrNull() ?: return All
                    Album(id, rest.substringAfter(':', ""))
                }
                else -> All
            }
        }
    }
}

/** The tracks [selection] picks out of [tracks], in library order. */
fun List<Track>.select(selection: MusicSelection): List<Track> = when (selection) {
    MusicSelection.All -> this
    is MusicSelection.Folder -> filter { it.folder == selection.path }
    is MusicSelection.Album -> filter { it.albumId == selection.albumId }
    is MusicSelection.Artist -> filter { it.artist == selection.name }
    // Walked separately (MusicLibrary.folderTracks); the MediaStore list cannot answer it.
    is MusicSelection.PickedFolder -> emptyList()
}

/**
 * The order a session plays [tracks] in: shuffled once when the queue is built,
 * not per song, so skipping back returns to the song just heard instead of a
 * fresh random one.
 */
fun playOrder(tracks: List<Track>, shuffle: Boolean, random: Random = Random.Default): List<Track> =
    if (shuffle) tracks.shuffled(random) else tracks

/** The queue position [delta] songs from [index], wrapping at both ends. */
fun queueIndexAfter(index: Int, delta: Int, size: Int): Int =
    if (size <= 0) 0 else Math.floorMod(index + delta, size)

/**
 * Whether "previous" restarts the song in progress rather than going back one:
 * past the first few seconds, the song you are in is the one you meant.
 */
fun previousRestartsSong(positionMs: Int): Boolean = positionMs > RESTART_THRESHOLD_MS

/** See [previousRestartsSong]. */
const val RESTART_THRESHOLD_MS = 3_000

/** Extensions played from a picked folder when the provider reports no audio MIME type. */
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr")

/** True for a file the picked-folder walk should queue. */
fun isAudioFile(displayName: String, mimeType: String?): Boolean =
    mimeType?.startsWith("audio/") == true ||
        displayName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** "05 - Nuvole Bianche.mp3" → "05 - Nuvole Bianche": the title a picked file shows until it plays. */
fun titleFromFileName(displayName: String): String =
    displayName.substringBeforeLast('.').ifBlank { displayName }

/**
 * "primary:Music/Study" (a tree's document id) → "Music/Study"; a folder on an SD
 * card has its volume prefix dropped the same way. Empty means the storage root.
 */
fun folderLabelFromDocumentId(documentId: String): String =
    documentId.substringAfter(':', documentId).trim('/').ifEmpty { "Internal storage" }

/**
 * What a library scan depends on. The picker reuses its last scan while this
 * is unchanged, instead of walking the whole library or folder each time the
 * controls come into view.
 */
data class LibraryScanKey(
    val selection: String,
    val hasPermission: Boolean,
    /** Whether the whole MediaStore list was read, not only the picked folder. */
    val includesAll: Boolean = true
)

/**
 * Whether the library must be read again: never read, what it depends on
 * changed, or the whole list is needed now and the last read skipped it.
 */
fun needsRescan(last: LibraryScanKey?, now: LibraryScanKey, force: Boolean = false): Boolean =
    force || last == null ||
        last.selection != now.selection ||
        last.hasPermission != now.hasPermission ||
        (now.includesAll && !last.includesAll)

/**
 * Whether the whole MediaStore list has to be read. A picked folder plays its
 * own walk, so reading every song on the phone only to show the folder's count
 * was wasted work; the list is read only when the album/artist picker opens.
 */
fun needsMediaStoreScan(selection: MusicSelection, pickerOpen: Boolean): Boolean =
    pickerOpen || selection !is MusicSelection.PickedFolder

/** A group the picker can offer, with how many songs it holds. */
data class MusicGroup(val selection: MusicSelection, val label: String, val count: Int)

/** Folders, albums and artists present in [tracks], each sorted by name. */
fun groupsOf(tracks: List<Track>): Triple<List<MusicGroup>, List<MusicGroup>, List<MusicGroup>> {
    val folders = tracks.groupBy { it.folder }.map { (folder, list) ->
        MusicGroup(MusicSelection.Folder(folder), folder.trimEnd('/').ifEmpty { "Storage root" }, list.size)
    }.sortedBy { it.label.lowercase() }
    val albums = tracks.groupBy { it.albumId }.map { (id, list) ->
        val name = list.first().album
        MusicGroup(MusicSelection.Album(id, name), name, list.size)
    }.sortedBy { it.label.lowercase() }
    val artists = tracks.groupBy { it.artist }.map { (artist, list) ->
        MusicGroup(MusicSelection.Artist(artist), artist, list.size)
    }.sortedBy { it.label.lowercase() }
    return Triple(folders, albums, artists)
}

/**
 * The songs stored on this phone, played by Euler's Lab itself as the study
 * sound. Two ways in: a folder picked in the system folder browser
 * ([folderTracks], no permission needed, the simplest), or the whole MediaStore
 * index by folder, album or artist ([tracks], needs READ_MEDIA_AUDIO).
 *
 * Nothing here opens another app: the songs play inside the timer's own
 * service, so no music app has to be exempted from the focus wall.
 *
 * Every read returns an empty list rather than throwing when access is gone (a
 * revoked permission, a released folder grant, a removed SD card); the timer
 * then falls back to the ambient loop.
 */
@Singleton
class MusicLibrary @Inject constructor(@ApplicationContext private val context: Context) {

    private val _generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Bumped by [invalidate] when access to the music changes. The timer folds
     * it into its sound source, so a session already playing the fallback loop
     * tries the songs again once they can be read.
     */
    val generation: kotlinx.coroutines.flow.StateFlow<Int> = _generation

    /** Access changed (permission granted, the same folder picked again): retry the music. */
    fun invalidate() {
        _generation.update { it + 1 }
    }

    /**
     * The media permission as last seen, kept in this process-wide singleton
     * (the timer's service lives in the same process), not in a screen's
     * ViewModel: a fresh screen would otherwise never notice a grant made while
     * the session played the fallback loop.
     */
    private var knownPermission: Boolean? = null

    init {
        // After every field above is set (init blocks run in declaration
        // order). The service injects this at onCreate, so a grant made later
        // in the session is a change from this reading.
        noticeAccessChange()
    }

    /**
     * Records the media permission and, when it differs from the last time it
     * was seen, bumps [generation] once so a running session retries its music.
     * Synchronized so two screens checking at once cannot both bump it.
     */
    @Synchronized
    fun noticeAccessChange() {
        val now = hasPermission()
        val known = knownPermission
        knownPermission = now
        if (known != null && known != now) invalidate()
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Every music file on the device, sorted by title. Empty without the permission. */
    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DURATION
        )
        // IS_MUSIC leaves out ringtones, notification sounds and voice notes,
        // which also live in MediaStore.Audio and are not a study playlist.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    result += Track(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" }
                            ?: "Unknown artist",
                        album = c.getString(albumCol) ?: "Unknown album",
                        albumId = c.getLong(albumIdCol),
                        folder = c.getString(pathCol) ?: "",
                        durationMs = c.getLong(durationCol)
                    )
                }
            }
        }
        result
    }

    /** The songs [selection] plays: MediaStore for most, a folder walk for [MusicSelection.PickedFolder]. */
    suspend fun tracksFor(selection: MusicSelection): List<Track> = when (selection) {
        is MusicSelection.PickedFolder -> folderTracks(selection.treeUri)
        else -> tracks().select(selection)
    }

    /**
     * Keeps read access to a folder picked in the system browser across reboots.
     * False when the grant could not be kept. The previous folder's grant is
     * let go only after the new choice is saved ([releaseFolder]), so a running
     * session never loses the folder it is still playing.
     */
    fun keepFolder(treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    /** Gives back read access to a folder no longer played. */
    fun releaseFolder(folder: MusicSelection.PickedFolder) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                folder.treeUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** "Music/Study" for a picked folder's tree URI. */
    fun folderLabel(treeUri: String): String = runCatching {
        folderLabelFromDocumentId(DocumentsContract.getTreeDocumentId(treeUri.toUri()))
    }.getOrDefault("Picked folder")

    /**
     * Every audio file under a folder the user picked, subfolders included,
     * sorted by name.
     *
     * Needs no media permission at all: the system folder browser granted read
     * access to exactly this folder and nothing else. It also finds songs
     * MediaStore never indexed (a folder with a `.nomedia` file in it, or
     * downloads still waiting for the media scanner).
     *
     * Titles come from file names here (reading tags from 200 files up front
     * would take seconds); the real title and artist are read when a song
     * starts playing. Empty if the grant has been revoked or the folder is gone.
     */
    suspend fun folderTracks(treeUri: String): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        runCatching {
            val tree = treeUri.toUri()
            val pending = ArrayDeque<String>()
            pending += DocumentsContract.getTreeDocumentId(tree)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            var visited = 0
            // Breadth-first with a cap, so a folder pointed at the whole of
            // internal storage cannot spin through every file on the phone.
            while (pending.isNotEmpty() && visited < MAX_FOLDERS_WALKED) {
                val parent = pending.removeFirst()
                visited++
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending += docId
                            isAudioFile(name, mime) -> result += Track(
                                id = docId.hashCode().toLong(),
                                title = titleFromFileName(name),
                                artist = "",
                                album = "",
                                albumId = 0,
                                folder = "",
                                durationMs = 0,
                                documentUri = DocumentsContract
                                    .buildDocumentUriUsingTree(tree, docId).toString()
                            )
                        }
                    }
                }
            }
        }
        result.sortedBy { it.title.lowercase() }
    }

    companion object {
        private const val MAX_FOLDERS_WALKED = 500
        const val PERMISSION = Manifest.permission.READ_MEDIA_AUDIO
    }
}
