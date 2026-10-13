package com.paperview.mobile.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

import com.paperview.mobile.tools.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Turning ML Kit's captured pages into a finished document.
 *
 * ML Kit hands back both a PDF and the individual JPEGs. We used to take the
 * PDF and ignore the pages, which meant accepting its photograph of the paper
 * exactly as shot. Taking the pages instead lets [Enhance] flatten the lighting
 * first, and the PDF is then rebuilt with the same PDFBox routine the Image to
 * PDF tool already uses.
 */
object ScanPipeline {

    /**
     * Longest edge worked on.
     *
     * A page at this size still prints sharply, and bounds one bitmap at about
     * 28 MB — pages are processed one at a time, so that is the ceiling for the
     * whole operation rather than per page.
     */
    private const val MAX_EDGE = 2400

    /** Smaller, for the preview on the review screen, which must feel instant. */
    const val PREVIEW_EDGE = 1100

    /**
     * Decodes [uri], scaled so its long edge is at most [maxEdge].
     *
     * Subsampled during the decode rather than resized afterwards: the full
     * image is never held, which is what keeps a multi-page scan off the heap.
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    /** A cleaned copy of [bitmap]; the original is left alone. */
    fun enhanced(bitmap: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.Original) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        Enhance.apply(pixels, width, height, filter)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The cleaned first page, small enough to render while the chips are tapped. */
    suspend fun preview(context: Context, page: Uri, filter: ScanFilter): Bitmap? =
        withContext(Dispatchers.Default) {
            val source = decode(context, page, PREVIEW_EDGE) ?: return@withContext null
            val result = enhanced(source, filter)
            if (result !== source) source.recycle()
            result
        }

    /**
     * Cleans every page and writes them into one PDF.
     *
     * Each page goes to disk as it is finished rather than being held, so a
     * thirty-page scan costs one page's worth of memory.
     */
    suspend fun build(
        context: Context,
        pages: List<Uri>,
        filter: ScanFilter,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.Default) {
        val staging = File(context.cacheDir, "scan").apply { mkdirs() }
        staging.listFiles()?.forEach { it.delete() }
        val prepared = ArrayList<File>(pages.size)
        try {
            pages.forEachIndexed { index, uri ->
                val source = decode(context, uri, MAX_EDGE)
                    ?: throw PdfOps.OpException("Could not read page ${index + 1}")
                val cleaned = try {
                    enhanced(source, filter)
                } finally {
                    // enhanced() may hand back the original for Original mode.
                    if (filter != ScanFilter.Original) source.recycle()
                }
                val file = File(staging, "page-%03d.jpg".format(index))
                try {
                    file.outputStream().use {
                        cleaned.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                    }
                } finally {
                    cleaned.recycle()
                }
                prepared += file
                onProgress(index + 1, pages.size)
            }
            PdfOps.imagesToPdf(prepared, out, fit = PdfOps.PageFit.FitImage, jpegQuality = 0.9f)
        } finally {
            prepared.forEach { it.delete() }
        }
    }

    /** High enough that text edges stay crisp after the second JPEG pass. */
    private const val JPEG_QUALITY = 92

    /**
     * Where a finished scan lives.
     *
     * Permanent storage, deliberately: scans used to be written into
     * `cache/share`, and a scan is the only copy of its document — the system
     * may clear a cache directory whenever it wants the space.
     */
    fun outputFile(context: Context): File {
        val dir = File(context.filesDir, "scans").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US)
            .format(java.util.Date())
        var candidate = File(dir, "Scan $stamp.pdf")
        var n = 2
        while (candidate.exists()) candidate = File(dir, "Scan $stamp ($n).pdf").also { n++ }
        return candidate
    }
}
