package com.ogenrwotdaniel.wakeproof.music

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSelectionTest {

    private fun track(id: Long, artist: String, albumId: Long, folder: String) = Track(
        id = id, title = "t$id", artist = artist, album = "a$albumId", albumId = albumId,
        folder = folder, durationMs = 1_000
    )

    private val library = listOf(
        track(1, "Ludovico Einaudi", 10, "Music/Study/"),
        track(2, "Ludovico Einaudi", 11, "Music/Study/"),
        track(3, "Hans Zimmer", 12, "Download/")
    )

    @Test
    fun `every selection survives an encode and decode round trip`() {
        listOf(
            MusicSelection.All,
            MusicSelection.Folder("Music/Study/"),
            MusicSelection.Album(42, "Elements: Part 2"),
            MusicSelection.Artist("Hans Zimmer"),
            MusicSelection.PickedFolder(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FStudy"
            )
        ).forEach { assertEquals(it, MusicSelection.decode(it.encode())) }
    }

    @Test
    fun `selections stored by 1_5 still decode`() {
        assertEquals(MusicSelection.All, MusicSelection.decode("all"))
        assertEquals(MusicSelection.Album(7, "Divenire"), MusicSelection.decode("album:7:Divenire"))
        assertEquals(
            MusicSelection.PickedFolder("content://x/tree/primary%3AMusic"),
            MusicSelection.decode("tree:content://x/tree/primary%3AMusic")
        )
    }

    @Test
    fun `unreadable selections play everything rather than nothing`() {
        assertEquals(MusicSelection.All, MusicSelection.decode(null))
        assertEquals(MusicSelection.All, MusicSelection.decode("folder:"))
        assertEquals(MusicSelection.All, MusicSelection.decode("tree:"))
        assertEquals(MusicSelection.All, MusicSelection.decode("album:notanumber:x"))
        assertEquals(MusicSelection.All, MusicSelection.decode("playlist:7"))
    }

    @Test
    fun `selections pick the right tracks`() {
        assertEquals(3, library.select(MusicSelection.All).size)
        assertEquals(listOf(1L, 2L), library.select(MusicSelection.Folder("Music/Study/")).map { it.id })
        assertEquals(listOf(3L), library.select(MusicSelection.Artist("Hans Zimmer")).map { it.id })
        assertEquals(listOf(2L), library.select(MusicSelection.Album(11, "a11")).map { it.id })
        assertTrue(library.select(MusicSelection.PickedFolder("content://x")).isEmpty())
    }

    @Test
    fun `a selection whose songs are gone picks nothing`() {
        assertTrue(library.select(MusicSelection.Artist("Nobody")).isEmpty())
        assertTrue(emptyList<Track>().select(MusicSelection.All).isEmpty())
    }

    @Test
    fun `groups count their songs`() {
        val (folders, albums, artists) = groupsOf(library)
        assertEquals(listOf("Download" to 1, "Music/Study" to 2), folders.map { it.label to it.count })
        assertEquals(3, albums.size)
        assertEquals(listOf("Hans Zimmer" to 1, "Ludovico Einaudi" to 2), artists.map { it.label to it.count })
    }

    @Test
    fun `play order keeps library order unless shuffled, and a shuffle loses no song`() {
        assertEquals(library, playOrder(library, shuffle = false))
        val many = (1L..40L).map { track(it, "a", it, "f/") }
        val shuffled = playOrder(many, shuffle = true, random = Random(7))
        assertEquals(many.toSet(), shuffled.toSet())
        assertEquals(many.size, shuffled.size)
        assertTrue(shuffled != many)
        assertEquals(shuffled, playOrder(many, shuffle = true, random = Random(7)))
    }

    @Test
    fun `skipping wraps at both ends of the queue`() {
        assertEquals(1, queueIndexAfter(0, 1, 3))
        assertEquals(0, queueIndexAfter(2, 1, 3))
        assertEquals(2, queueIndexAfter(0, -1, 3))
        assertEquals(0, queueIndexAfter(5, 1, 0))
    }

    @Test
    fun `previous restarts the song only past the first seconds`() {
        assertFalse(previousRestartsSong(0))
        assertFalse(previousRestartsSong(RESTART_THRESHOLD_MS))
        assertTrue(previousRestartsSong(RESTART_THRESHOLD_MS + 1))
    }

    @Test
    fun `picked folders recognise audio by type or by extension`() {
        assertEquals(true, isAudioFile("track.bin", "audio/mpeg"))
        assertEquals(true, isAudioFile("Nuvole Bianche.FLAC", "application/octet-stream"))
        assertEquals(true, isAudioFile("song.m4a", null))
        assertEquals(false, isAudioFile("cover.jpg", "image/jpeg"))
        assertEquals(false, isAudioFile("notes", null))
    }

    @Test
    fun `file names and folder ids read as people expect`() {
        assertEquals("05 - Nuvole Bianche", titleFromFileName("05 - Nuvole Bianche.mp3"))
        assertEquals("v1.2 mix", titleFromFileName("v1.2 mix.ogg"))
        assertEquals("Music/Study", folderLabelFromDocumentId("primary:Music/Study"))
        assertEquals("Internal storage", folderLabelFromDocumentId("primary:"))
        assertEquals("Songs", folderLabelFromDocumentId("1A2B-3C4D:Songs"))
    }

    @Test
    fun `the library is read again only when the selection or permission changes`() {
        val key = LibraryScanKey("all", hasPermission = false)
        assertTrue(needsRescan(null, key))
        assertFalse(needsRescan(key, key.copy()))
        assertTrue(needsRescan(key, key.copy(hasPermission = true)))
        assertTrue(needsRescan(key, key.copy(selection = "artist:Hans Zimmer")))
        assertTrue(needsRescan(key, key, force = true))
    }

    @Test
    fun `a folder-only read is enough until the picker wants everything`() {
        val folderOnly = LibraryScanKey("tree:x", hasPermission = true, includesAll = false)
        val everything = folderOnly.copy(includesAll = true)
        assertTrue(needsRescan(folderOnly, everything))
        assertFalse(needsRescan(everything, folderOnly))
        assertFalse(needsRescan(folderOnly, folderOnly.copy()))
    }

    @Test
    fun `only a picked folder skips the whole-library read, and only until the picker opens`() {
        val folder = MusicSelection.PickedFolder("content://x")
        assertFalse(needsMediaStoreScan(folder, pickerOpen = false))
        assertTrue(needsMediaStoreScan(folder, pickerOpen = true))
        assertTrue(needsMediaStoreScan(MusicSelection.All, pickerOpen = false))
        assertTrue(needsMediaStoreScan(MusicSelection.Artist("X"), pickerOpen = false))
    }
}
