package com.paperview.mobile.tools

import android.content.Context
import android.graphics.Bitmap
import com.paperview.mobile.pdf.DocSource
import com.paperview.mobile.pdf.PdfEngine
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.snapPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF pages out as images, rendered through the same PDFium path the viewer
 * uses — so an exported page looks exactly like the page on screen.
 */
object ImageExport {

    /** PDF points are 1/72 inch, so this is just the dpi ratio. */
    private const val POINTS_PER_INCH = 72f

    suspend fun export(
        context: Context,
        input: File,
        password: String?,
        pages: List<Int>,
        dpi: Int,
        png: Boolean,
        outDir: File,
        baseName: String,
    ): List<File> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw PdfOps.OpException("No pages selected")
        outDir.mkdirs()
        val doc = PdfEngine.open(context, DocSource.Local(input), password)
        val written = ArrayList<File>()
        try {
            val scale = dpi / POINTS_PER_INCH
            val safeBase = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
            for (index in pages) {
                if (index !in 0 until doc.pageCount) continue
                val box = doc.pages[index]
                val w = snapPx(box.widthPt * scale)
                val h = snapPx(box.heightPt * scale)
                // A page at 400 dpi can be enormous; refuse rather than OOM.
                if (w.toLong() * h > MAX_PIXELS) {
                    throw PdfOps.OpException(
                        "Page ${index + 1} is too large at $dpi dpi — try a lower resolution",
                    )
                }
                val bitmap = doc.render(index, w, h, Rotation.R0)
                val extension = if (png) "png" else "jpg"
                val out = File(outDir, "${safeBase}_${index + 1}.$extension")
                try {
                    out.outputStream().use { stream ->
                        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        bitmap.compress(format, if (png) 100 else 92, stream)
                    }
                    written.add(out)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            doc.close()
        }
        if (written.isEmpty()) throw PdfOps.OpException("Nothing was exported")
        written
    }

    /** ~40 megapixels — comfortably past anything useful, well short of an OOM. */
    private const val MAX_PIXELS = 40_000_000L
}
