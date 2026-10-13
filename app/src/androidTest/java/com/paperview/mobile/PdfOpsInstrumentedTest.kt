package com.paperview.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperview.mobile.tools.ImageExport
import com.paperview.mobile.tools.PdfOps
import com.paperview.mobile.tools.parseRanges
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises every PDFBox-backed operation against a real document on the
 * device. These need actual native rendering and PDFBox's asset-loaded fonts,
 * so they can't run on the JVM — but they're the only way to know the tools
 * work before shipping them.
 */
@RunWith(AndroidJUnit4::class)
class PdfOpsInstrumentedTest {

    private lateinit var source: File
    private lateinit var workDir: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun initPdfBox() {
            // The Application class normally does this; instrumented tests
            // don't necessarily go through it.
            PDFBoxResourceLoader.init(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "optest").apply { deleteRecursively(); mkdirs() }
        source = File(workDir, "sample.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sample.pdf").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        assertTrue("fixture copied", source.length() > 0)
    }

    private fun out(name: String) = File(workDir, name)

    @Test
    fun fixtureHasThreePages() = runBlocking {
        assertEquals(3, PdfOps.pageCount(source))
    }

    @Test
    fun mergeDoublesThePageCount() = runBlocking {
        val merged = PdfOps.merge(listOf(source, source), out("merged.pdf"))
        assertTrue(merged.length() > 0)
        assertEquals(6, PdfOps.pageCount(merged))
    }

    @Test
    fun deletingAPageLeavesTheRest() = runBlocking {
        val result = PdfOps.deletePages(source, setOf(1), out("deleted.pdf"))
        assertEquals(2, PdfOps.pageCount(result))
    }

    @Test
    fun deletingEveryPageIsRefused() = runBlocking {
        runCatching { PdfOps.deletePages(source, setOf(0, 1, 2), out("empty.pdf")) }
            .onSuccess { throw AssertionError("should have refused to empty the document") }
            .onFailure { assertTrue(it is PdfOps.OpException) }
        Unit
    }

    @Test
    fun extractingRangesWritesOneFileEach() = runBlocking {
        val files = PdfOps.splitRanges(source, listOf(0..0, 1..2), workDir, "split")
        assertEquals(2, files.size)
        assertEquals(1, PdfOps.pageCount(files[0]))
        assertEquals(2, PdfOps.pageCount(files[1]))
    }

    @Test
    fun reorderPreservesEveryPage() = runBlocking {
        val result = PdfOps.writePages(source, listOf(2, 0, 1), out("reordered.pdf"))
        assertEquals(3, PdfOps.pageCount(result))
    }

    @Test
    fun rotationIsRecordedOnThePage() = runBlocking {
        val result = PdfOps.rotate(source, 90, null, out("rotated.pdf"))
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(result).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                assertEquals("page $i rotation", 90, doc.getPage(i).rotation)
            }
        }
    }

    @Test
    fun watermarkProducesADifferentFile() = runBlocking {
        // Text drawing is the operation that fails when PDFBox can't reach its
        // font assets, so this doubles as a resource-loader check.
        val result = PdfOps.watermarkText(source, "CONFIDENTIAL", out("watermarked.pdf"))
        assertEquals(3, PdfOps.pageCount(result))
        assertNotEquals(source.length(), result.length())
    }

    @Test
    fun pageNumbersAreAdded() = runBlocking {
        val result = PdfOps.numberPages(
            source, out("numbered.pdf"), format = "{n} of {total}", startAt = 1,
        )
        assertEquals(3, PdfOps.pageCount(result))
        assertTrue(result.length() > 0)
    }

    @Test
    fun lockThenUnlockRoundTrips() = runBlocking {
        val locked = PdfOps.lock(source, "hunter2", "hunter2", out("locked.pdf"))
        assertTrue("locked file should be encrypted", PdfOps.isEncrypted(locked))

        // Opening without the password must fail…
        runCatching { PdfOps.pageCount(locked) }
            .onSuccess { throw AssertionError("encrypted file opened without a password") }
            .onFailure { assertTrue(it is PdfOps.NeedsPassword) }

        // …and succeed with it.
        assertEquals(3, PdfOps.pageCount(locked, "hunter2"))

        val unlocked = PdfOps.unlock(locked, "hunter2", out("unlocked.pdf"))
        assertEquals(3, PdfOps.pageCount(unlocked))
    }

    @Test
    fun changingThePasswordInvalidatesTheOldOne() = runBlocking {
        val locked = PdfOps.lock(source, "first", "first", out("pw1.pdf"))
        val changed = PdfOps.changePassword(locked, "first", "second", out("pw2.pdf"))
        assertEquals(3, PdfOps.pageCount(changed, "second"))
        runCatching { PdfOps.pageCount(changed, "first") }
            .onSuccess { throw AssertionError("old password still works") }
            .onFailure { assertTrue(it is PdfOps.NeedsPassword) }
        Unit
    }

    @Test
    fun imagesBecomePages() = runBlocking {
        val images = (1..2).map { n ->
            val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(if (n == 1) Color.RED else Color.BLUE)
            val file = out("img$n.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            file
        }
        val pdf = PdfOps.imagesToPdf(images, out("fromimages.pdf"))
        assertEquals(2, PdfOps.pageCount(pdf))
    }

    @Test
    fun compressReportsBeforeAndAfter() = runBlocking {
        val result = PdfOps.compress(source, out("compressed.pdf"), jpegQuality = 0.4f)
        assertEquals(3, PdfOps.pageCount(result.out))
        assertTrue("before size recorded", result.beforeBytes > 0)
        assertTrue("after size recorded", result.afterBytes > 0)
    }

    @Test
    fun signatureStampLandsOnThePage() = runBlocking {
        val bmp = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.TRANSPARENT)
            drawLine(10f, 60f, 190f, 20f, Paint().apply { color = Color.BLACK; strokeWidth = 5f })
        }
        val result = PdfOps.stampImage(
            source, bmp, pageIndex = 0,
            xFraction = 0.5f, yFraction = 0.8f, widthFraction = 0.3f,
            out = out("signed.pdf"),
        )
        bmp.recycle()
        assertEquals(3, PdfOps.pageCount(result))
    }

    @Test
    fun pagesExportAsImages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = ImageExport.export(
            context = context,
            input = source,
            password = null,
            pages = listOf(0, 2),
            dpi = 96,
            png = false,
            outDir = workDir,
            baseName = "page",
        )
        assertEquals(2, files.size)
        files.forEach { assertTrue("${it.name} has bytes", it.length() > 0) }
    }

    /**
     * Every directory the app shares from must be a configured FileProvider
     * root. When one isn't, getUriForFile throws and sharing fails with a
     * useless message — which is exactly how sharing to WhatsApp broke: files
     * were written to cache/share and cache/tools while only cache/cloud was
     * declared.
     */
    @Test
    fun everyShareableDirectoryHasAFileProviderRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.fileprovider"

        val candidates = listOf(
            File(context.cacheDir, "share/doc.pdf"),      // documents being shared
            File(context.cacheDir, "tools/result.pdf"),   // tool output
            File(context.cacheDir, "tools/page_1.jpg"),   // exported images
            File(context.cacheDir, "covers/cover.jpg"),   // library covers
            File(context.filesDir, "cloud/download.pdf"), // Backblaze downloads
            File(context.filesDir, "shelf/copy.pdf"),     // shelf copies
            File(context.filesDir, "scans/Scan 1.pdf"),   // finished scans
        )

        for (file in candidates) {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeBytes(ByteArray(4))
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            } catch (t: Throwable) {
                throw AssertionError("no FileProvider root covers $file", t)
            }
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
        }
    }

    @Test
    fun shareNamesAreSafeAndKeepTheExtension() {
        val f = com.paperview.mobile.files.FileAccess
        // A title taken from PDF metadata may have no extension; receivers
        // reject a file that doesn't look like a PDF.
        assertEquals("Lecture Notes.pdf", f.shareName("Lecture Notes"))
        assertEquals("already.pdf", f.shareName("already.pdf"))
        assertEquals("already.PDF", f.shareName("already.PDF"))
        // Path separators would break the write entirely.
        assertEquals("a_b_c.pdf", f.shareName("a/b\\c"))
        assertEquals("document.pdf", f.shareName(""))
        assertEquals("document.pdf", f.shareName("   "))
        assertTrue(f.shareName("x".repeat(400)).length <= 124)
    }

    @Test
    fun rangeParsingHandlesRealInput() {
        assertEquals(listOf(0..2, 4..4, 7..9), parseRanges("1-3, 5, 8-10", 20))
        // Reversed input is normalised rather than dropped.
        assertEquals(listOf(2..5), parseRanges("6-3", 20))
        // Out-of-range values clamp to the document.
        assertEquals(listOf(0..4), parseRanges("1-99", 5))
        // Garbage is skipped, not fatal.
        assertEquals(listOf(0..0), parseRanges("1, banana", 5))
    }
}
