package com.paperview.mobile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperview.mobile.store.Shelf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The shelf has to hold on to documents that arrive by intent, because the read
 * grant those come with dies with the task. Needs a real ContentResolver and a
 * real FileProvider, so it runs on the device.
 */
@RunWith(AndroidJUnit4::class)
class ShelfInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun emptyTheShelf() {
        Shelf.clear(context)
    }

    /**
     * A content URI standing in for one handed over by another app. Each call
     * produces a *different* URI, exactly as a chat app does when the same
     * document is shared twice.
     */
    private fun incoming(bytes: ByteArray): Pair<Uri, Long> {
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val file = File(dir, "share-${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to bytes.size.toLong()
    }

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) % 251).toByte() }

    @Test
    fun aCopiedDocumentKeepsItsBytes() {
        val content = bytes(2048)
        val (uri, size) = incoming(content)

        val copy = Shelf.store(context, uri, size)

        assertNotNull("the copy should have been made", copy)
        assertArrayEquals(content, copy!!.readBytes())
        assertTrue(Shelf.holds(context, copy))
    }

    @Test
    fun theSameDocumentFromDifferentUrisIsOneCopy() {
        // The reported bug. Sharing a PDF from WhatsApp three times produces
        // three different URIs; naming copies after the source filed the same
        // bytes three times and gave each its own reading position, so the
        // document never reopened where you left it.
        val content = bytes(4096, seed = 3)

        val copies = (1..3).map { Shelf.store(context, incoming(content).first, content.size.toLong()) }

        assertTrue("every store should have succeeded", copies.all { it != null })
        assertEquals("all three should resolve to one copy", 1, copies.map { it!!.absolutePath }.toSet().size)
        assertEquals(1, Shelf.stats(context).first)
    }

    @Test
    fun differentDocumentsAreNeverMerged() {
        // Same length, different contents: a digest of the bytes has to tell
        // them apart where a size check could not.
        val a = Shelf.store(context, incoming(bytes(4096, seed = 1)).first, 4096)
        val b = Shelf.store(context, incoming(bytes(4096, seed = 2)).first, 4096)

        assertNotNull(a)
        assertNotNull(b)
        assertFalse("different bytes must not share a copy", a!!.absolutePath == b!!.absolutePath)
        assertEquals(2, Shelf.stats(context).first)
    }

    @Test
    fun anEditedDocumentIsCopiedSeparately() {
        val first = Shelf.store(context, incoming(bytes(256)).first, 256)
        val second = Shelf.store(context, incoming(bytes(1024)).first, 1024)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1024, second!!.length())
        assertEquals(2, Shelf.stats(context).first)
    }

    @Test
    fun anOversizedDocumentIsLeftWhereItIs() {
        // Copying it would cost more than the convenience is worth and crowd
        // out the rest of the shelf. It still opens; it just isn't kept.
        val (uri, _) = incoming(bytes(64))

        val copy = Shelf.store(context, uri, Shelf.MAX_FILE_BYTES + 1)

        assertNull(copy)
        assertEquals(0, Shelf.stats(context).first)
    }

    @Test
    fun aLyingSizeDoesNotGetPastTheCap() {
        // Providers are not obliged to report a size and many report zero, so
        // the cap has to hold against the bytes actually arriving.
        val huge = ByteArray((Shelf.MAX_FILE_BYTES + 1024).toInt())
        val (uri, _) = incoming(huge)

        val copy = Shelf.store(context, uri, 0)

        assertNull("an unreported size must not become an unbounded copy", copy)
        assertEquals("the half-written copy should have been cleaned up", 0, Shelf.stats(context).first)
    }

    @Test
    fun aFailedReadLeavesNothingBehind() {
        val missing = Uri.parse("content://${context.packageName}.fileprovider/cache/nope.pdf")

        val copy = Shelf.store(context, missing, 100)

        assertNull(copy)
        assertEquals(0, Shelf.stats(context).first)
    }

    @Test
    fun rekeyingCollapsesCopiesMadeBeforeContentNaming() {
        // Shelves written by the previous version hold the same document under
        // several URI-derived names. Renaming by content makes them collide.
        val dir = Shelf.dir(context)
        val content = bytes(900, seed = 5)
        val legacy = listOf("aaa.pdf", "bbb.pdf", "ccc.pdf").map { name ->
            File(dir, name).apply { writeBytes(content) }
        }
        val other = File(dir, "ddd.pdf").apply { writeBytes(bytes(900, seed = 6)) }
        legacy[0].setLastModified(3_000_000)
        legacy[1].setLastModified(2_000_000)
        legacy[2].setLastModified(1_000_000)

        val moved = Shelf.rekeyByContent(context)

        assertEquals("three duplicates plus one distinct file should all move", 4, moved.size)
        assertEquals("the duplicates should have become one copy", 2, Shelf.stats(context).first)
        // Every old path must be accounted for, or the Recent list is orphaned.
        legacy.forEach { assertTrue("no mapping for ${it.name}", moved.containsKey(it.absolutePath)) }
        assertTrue(moved.containsKey(other.absolutePath))
        // All three duplicates point at the same surviving copy.
        assertEquals(1, legacy.map { moved[it.absolutePath] }.toSet().size)
        assertTrue(File(moved[legacy[0].absolutePath]!!).isFile)
    }

    @Test
    fun rekeyingAnAlreadyContentNamedShelfChangesNothing() {
        // The migration runs once, but it must be harmless if it runs again.
        Shelf.store(context, incoming(bytes(512)).first, 512)
        val before = Shelf.dir(context).listFiles()!!.map { it.name }.sorted()

        val moved = Shelf.rekeyByContent(context)

        assertTrue("nothing should have moved", moved.isEmpty())
        assertEquals(before, Shelf.dir(context).listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun theLeastRecentlyReadCopiesGoFirst() {
        val dir = Shelf.dir(context)
        val old = File(dir, "old.pdf").apply { writeBytes(ByteArray(400)) }
        val newer = File(dir, "newer.pdf").apply { writeBytes(ByteArray(400)) }
        val keep = File(dir, "keep.pdf").apply { writeBytes(ByteArray(400)) }
        old.setLastModified(1_000_000)
        newer.setLastModified(2_000_000)
        keep.setLastModified(1_500_000)

        // Room for two of the three.
        Shelf.trim(context, keep = keep, maxBytes = 900)

        assertFalse("the oldest should have gone", old.exists())
        assertTrue("the newest should have stayed", newer.exists())
        assertTrue("the open document must never be evicted", keep.exists())
    }

    @Test
    fun readingACopyProtectsItFromEviction() {
        val dir = Shelf.dir(context)
        val a = File(dir, "a.pdf").apply { writeBytes(ByteArray(400)) }
        val b = File(dir, "b.pdf").apply { writeBytes(ByteArray(400)) }
        a.setLastModified(1_000_000)
        b.setLastModified(2_000_000)

        // Coming back to the older document makes it the recently read one.
        Shelf.touch(a)
        Shelf.trim(context, maxBytes = 500)

        assertTrue("the document just read should have stayed", a.exists())
        assertFalse("the one left alone should have gone", b.exists())
    }

    @Test
    fun reopeningADocumentMakesItRecentAgain() {
        // Otherwise the document you keep coming back to ages out while a
        // one-off you opened later survives.
        val content = bytes(300, seed = 9)
        val first = Shelf.store(context, incoming(content).first, 300)!!
        first.setLastModified(1_000_000)

        Shelf.store(context, incoming(content).first, 300)

        assertTrue("reopening should have refreshed it", first.lastModified() > 1_000_000)
    }

    @Test
    fun aDocumentElsewhereIsNotOurs() {
        val outside = File(context.cacheDir, "elsewhere.pdf").apply { writeBytes(ByteArray(8)) }
        assertFalse(Shelf.holds(context, outside))
    }
}
