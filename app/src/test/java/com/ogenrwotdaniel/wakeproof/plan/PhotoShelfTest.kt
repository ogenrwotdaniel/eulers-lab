package com.ogenrwotdaniel.wakeproof.plan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoShelfTest {

    @get:org.junit.Rule
    val folder = org.junit.rules.TemporaryFolder()

    @Test
    fun `a retake is written over the photo, which keeps its name, day, subject and first moment`() =
        kotlinx.coroutines.runBlocking {
            val shelf = PhotoShelf({ folder.root }, "summary")
            val first = shelf.save(byteArrayOf(1), epochDay = 20_773L, now = 1_000L, subject = "Real Analysis")
            val other = shelf.save(byteArrayOf(2), epochDay = 20_773L, now = 2_000L)

            val retaken = shelf.replace(first.file.name, byteArrayOf(3))

            assertEquals(first.file.name, retaken.file.name)
            assertEquals(Triple(20_773L, 1_000L, "Real Analysis"), Triple(retaken.epochDay, retaken.takenAtMillis, retaken.subject))
            assertEquals(listOf(other.file.name, first.file.name), shelf.list().map { it.file.name })
            assertTrue(retaken.file.readBytes().contentEquals(byteArrayOf(3)))
            // A new version each time, so a view keyed on it reads the picture again.
            assertTrue(retaken.version > first.version)
            val again = shelf.replace(first.file.name, byteArrayOf(4))
            assertTrue(again.version > retaken.version)
            assertEquals(again.version, shelf.list().first { it.file.name == first.file.name }.version)
        }

    @Test
    fun `a retake of a photo deleted meanwhile is written anew, never lost`() = kotlinx.coroutines.runBlocking {
        val shelf = PhotoShelf({ folder.root }, "summary")
        val gone = shelf.save(byteArrayOf(1), epochDay = 1L, now = 1_000L, subject = "Optics")
        shelf.delete(gone)

        val retaken = shelf.replace(gone.file.name, byteArrayOf(3))

        assertEquals(listOf(gone.file.name), shelf.list().map { it.file.name })
        assertTrue(retaken.file.readBytes().contentEquals(byteArrayOf(3)))
    }

    @Test
    fun `a retake names only a photo of its own shelf`() = kotlinx.coroutines.runBlocking {
        val shelf = PhotoShelf({ folder.root }, "summary")
        assertTrue(runCatching { shelf.replace("../notes.jpg", byteArrayOf(3)) }.isFailure)
        assertTrue(shelf.list().isEmpty())
    }

    @Test
    fun `a page photo's file name carries its day, moment, and optional subject and reads back`() {
        val name = photoFileName("summary", epochDay = 20_769L, takenAtMillis = 1_794_471_660_000L)
        assertEquals("summary_20769_1794471660000.jpg", name)
        assertEquals(20_769L to 1_794_471_660_000L, parsePhotoFileName("summary", name))

        val withSubject = photoFileName("summary", epochDay = 20_769L, takenAtMillis = 1_794_471_660_000L, subject = "Real Analysis")
        assertEquals("summary_20769_1794471660000_Real-Analysis.jpg", withSubject)
        assertEquals(
            Triple(20_769L, 1_794_471_660_000L, "Real Analysis"),
            parsePhotoFileMeta("summary", withSubject)
        )
    }

    @Test
    fun `a day before 1970 is still listed`() {
        // A clock set wrong must not hide the photo it took.
        assertEquals(-3L to 5L, parsePhotoFileName("summary", photoFileName("summary", -3L, 5L)))
    }

    @Test
    fun `other files in the folder are not pages`() {
        assertNull(parsePhotoFileName("summary", "summary_20769_1794471660000.jpg.part"))
        assertNull(parsePhotoFileName("summary", "summary_20769.jpg"))
        assertNull(parsePhotoFileName("summary", "summary_x_1.jpg"))
        assertNull(parsePhotoFileName("summary", "notes.jpg"))
        assertNull(parsePhotoFileName("summary", "summary_99999999999999999999_1.jpg"))
    }

    @Test
    fun `the library is newest day first, latest photo first within a day`() {
        fun photo(day: Long, at: Long) = PagePhoto(File(photoFileName("summary", day, at)), day, at)
        val sorted = sortedPages(listOf(photo(10, 5), photo(12, 1), photo(10, 9), photo(11, 3)))
        assertEquals(listOf(12L to 1L, 11L to 3L, 10L to 9L, 10L to 5L), sorted.map { it.epochDay to it.takenAtMillis })
    }

    @Test
    fun `only the newest days of pages are kept, whatever today's date is`() {
        fun photo(day: Long, at: Long) = PagePhoto(File(photoFileName("summary", day, at)), day, at)
        val photos = listOf(photo(10, 1), photo(11, 1), photo(11, 2), photo(12, 1), photo(400, 1))
        // Two photos of one day count as one day, and both stay.
        assertEquals(listOf(10L), pagesToPrune(photos, keepDays = 3).map { it.epochDay })
        assertTrue(pagesToPrune(photos, keepDays = 4).isEmpty())
        // Fewer days than the limit: nothing goes, however old they are.
        assertTrue(pagesToPrune(listOf(photo(-3, 1), photo(10, 1))).isEmpty())
    }

    @Test
    fun `a zoomed page can be dragged only until its edge reaches the screen's`() {
        assertEquals(0f, maxPan(zoom = 1f, size = 1000), 0f)
        assertEquals(500f, maxPan(zoom = 2f, size = 1000), 0f)
        assertEquals(2000f, maxPan(zoom = 5f, size = 1000), 0f)
        // Before the viewer is measured nothing moves.
        assertEquals(0f, maxPan(zoom = 3f, size = 0), 0f)
    }

    @Test
    fun `a photo is subsampled by powers of two down to about the wanted side`() {
        assertEquals(1, sampleSizeFor(1920, 1440, maxSide = 2400))
        assertEquals(1, sampleSizeFor(1920, 1440, maxSide = 1000))
        assertEquals(2, sampleSizeFor(1920, 1440, maxSide = 960))
        assertEquals(4, sampleSizeFor(1920, 1440, maxSide = 480))
        assertEquals(8, sampleSizeFor(4000, 3000, maxSide = 480))
        // A degenerate image must not loop or divide by zero.
        assertEquals(1, sampleSizeFor(0, 0, maxSide = 480))
    }
}
