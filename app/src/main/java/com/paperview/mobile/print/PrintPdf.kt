package com.paperview.mobile.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

/**
 * Printing, via Android's own print pipeline.
 *
 * The desktop build had to hand-roll Win32 print dialogs and then add its own
 * preview because the classic API can't drive Windows 11's preview pane. None
 * of that applies here: the system print UI already provides preview, copies,
 * page ranges and PDF export, so the job is simply to hand it the bytes.
 */
object PrintPdf {

    fun print(context: Context, file: File, name: String, pageCount: Int) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = name.ifBlank { "Document" }
        manager.print(
            jobName,
            PdfDocumentAdapter(file, jobName, pageCount),
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .build(),
        )
    }

    private class PdfDocumentAdapter(
        private val file: File,
        private val name: String,
        private val pageCount: Int,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            if (!file.exists() || file.length() == 0L) {
                callback.onLayoutFailed("Document is not available")
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(
                    if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN,
                )
                .build()
            // Layout never actually changes — the file is fixed — but reporting
            // `true` keeps the spooler from caching a stale render.
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val fd = destination?.fileDescriptor
            if (fd == null) {
                callback.onWriteFailed("No destination")
                return
            }
            try {
                file.inputStream().use { input ->
                    FileOutputStream(fd).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                // The whole document goes across; reporting ALL_PAGES tells the
                // print system it may pick the user's selected range out of it.
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                callback.onWriteFailed(t.message ?: "Could not write the document")
            }
        }
    }
}
