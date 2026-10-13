package com.paperview.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperview.mobile.scan.Enhance
import com.paperview.mobile.scan.ScanFilter
import com.paperview.mobile.scan.ScanPipeline
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * The scan pipeline end to end: a photographed page in, a cleaned PDF out.
 *
 * Runs on the device because it needs real JPEG decoding, a real
 * ContentResolver and PDFBox's asset-loaded fonts. It also writes the before
 * and after out as PNGs, because a number can tell you the lighting flattened
 * and still not tell you the page looks right.
 */
@RunWith(AndroidJUnit4::class)
class ScanPipelineInstrumentedTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initPdfBox() {
            PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A photograph of a page: real glyphs, lit from one side, with a hard
     * shadow across a corner the way a hand or a phone casts one.
     */
    private fun photographOfAPage(width: Int = 1200, height: Int = 1600): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val text = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(25, 25, 30)
            textSize = width / 26f
            typeface = Typeface.SERIF
        }
        val lines = listOf(
            "The tympanic membrane is examined with", "an otoscope, noting colour, contour and",
            "mobility. A dull grey drum with reduced", "mobility suggests effusion in the middle",
            "ear. Compare both sides before deciding.", "",
            "Conductive loss localises to the affected", "ear on Weber; Rinne is negative there.",
            "Sensorineural loss localises away from it.", "",
            "Record the findings for each ear separately", "and date every entry in the notes.",
        )
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, width * 0.09f, height * 0.18f + i * width / 19f, text)
        }

        // Light falling off across the page, then a hard-edged corner shadow.
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)
        canvas.drawRect(
            width * 0.62f, height * 0.70f, width.toFloat(), height.toFloat(),
            Paint().apply { color = Color.argb(70, 0, 0, 0) },
        )
        return bitmap
    }

    private fun asJpegUri(bitmap: Bitmap, name: String): Uri {
        val dir = File(context.cacheDir, "scantest").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Spread of the paper's brightness, sampled where there is no text. */
    private fun paperSpread(bitmap: Bitmap): Double {
        val values = ArrayList<Int>()
        for (y in 0 until bitmap.height step 7) {
            // Below the text block: paper only.
            if (y < bitmap.height * 0.80) continue
            for (x in 0 until bitmap.width step 7) values.add(Enhance.luma(bitmap.getPixel(x, y)))
        }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) }.toDouble() / values.size)
    }

    private fun paperMean(bitmap: Bitmap): Double {
        val values = ArrayList<Int>()
        for (y in (bitmap.height * 0.80).toInt() until bitmap.height step 7) {
            for (x in 0 until bitmap.width step 7) values.add(Enhance.luma(bitmap.getPixel(x, y)))
        }
        return values.average()
    }

    @Test
    fun aPhotographedPageComesOutFlatAndWhite() {
        val photo = photographOfAPage()
        val before = paperSpread(photo)
        val beforeMean = paperMean(photo)

        val cleaned = ScanPipeline.enhanced(photo, ScanFilter.Document)

        val after = paperSpread(cleaned)
        val afterMean = paperMean(cleaned)
        assertTrue(
            "paper varied by %.1f before, %.1f after".format(before, after),
            after < before / 2,
        )
        assertTrue(
            "paper was %.0f before and %.0f after; it should be near white"
                .format(beforeMean, afterMean),
            afterMean > 242,
        )
    }

    @Test
    fun theTextSurvivesTheCleanup() {
        // The pipeline is worthless if it flattens the words along with the light.
        val photo = photographOfAPage()
        val cleaned = ScanPipeline.enhanced(photo, ScanFilter.Document)
        var dark = 0
        for (y in 0 until cleaned.height step 3) {
            for (x in 0 until cleaned.width step 3) {
                if (Enhance.luma(cleaned.getPixel(x, y)) < 100) dark++
            }
        }
        assertTrue("no dark pixels left — the text was washed away", dark > 500)
    }

    @Test
    fun everyPageEndsUpInThePdf() {
        runBlocking {
            val pages = listOf(
                asJpegUri(photographOfAPage(), "p1.jpg"),
                asJpegUri(photographOfAPage(), "p2.jpg"),
                asJpegUri(photographOfAPage(), "p3.jpg"),
            )
            val out = File(context.cacheDir, "scan-out.pdf")

            var lastDone = 0
            val pdf = ScanPipeline.build(context, pages, ScanFilter.Document, out) { done, _ ->
                lastDone = done
            }

            assertTrue("the PDF should exist and have content", pdf.length() > 0)
            assertEquals("progress should have reached the last page", 3, lastDone)
            PDDocument.load(pdf).use { assertEquals(3, it.numberOfPages) }
            pdf.delete()
        }
    }

    @Test
    fun aScanIsWrittenOutsideTheCache() {
        // Scans used to land in cache/share, where the system — and our own
        // six-hour sweep of that directory — may delete them. A scan is the
        // only copy of its document.
        val out = ScanPipeline.outputFile(context)
        assertTrue(
            "a scan must not live under the cache: ${out.absolutePath}",
            !out.absolutePath.startsWith(context.cacheDir.absolutePath),
        )
        assertTrue(out.absolutePath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun twoScansInTheSameMinuteDoNotCollide() {
        val first = ScanPipeline.outputFile(context).apply { writeBytes(ByteArray(4)) }
        val second = ScanPipeline.outputFile(context)
        assertTrue("the second scan would have overwritten the first", first != second)
        first.delete()
    }

    @Test
    fun decodingCapsHugeImages() {
        val huge = photographOfAPage(width = 4000, height = 5200)
        val uri = asJpegUri(huge, "huge.jpg")
        huge.recycle()

        val decoded = ScanPipeline.decode(context, uri, 1100)

        assertNotNull(decoded)
        assertTrue(
            "decoded at ${decoded!!.width}x${decoded.height}, which is not capped",
            maxOf(decoded.width, decoded.height) <= 2200,
        )
    }

    @Test
    fun writeTheFiltersOutToBeLookedAt() {
        // Not an assertion. Numbers say the lighting flattened; only an eye can
        // say the page looks like a scan.
        val photo = photographOfAPage()
        val dir = File(context.cacheDir, "scanproof").apply { mkdirs() }
        File(dir, "0-photo.png").outputStream().use {
            photo.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        ScanFilter.entries.forEach { filter ->
            val result = ScanPipeline.enhanced(photographOfAPage(), filter)
            File(dir, "${filter.key}.png").outputStream().use {
                result.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue(dir.listFiles()!!.size >= 4)
    }
}
