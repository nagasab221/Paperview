package com.paperview.mobile.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Where a document's bytes come from. */
sealed interface DocSource {
    /** A SAF `content://` URI — the normal path on Android. */
    data class Content(val uri: Uri) : DocSource

    /** A real filesystem path (cloud cache, or the all-files browser). */
    data class Local(val file: File) : DocSource

    val key: String
        get() = when (this) {
            is Content -> uri.toString()
            is Local -> file.absolutePath
        }
}

/** Raised when a document needs a password we don't have (or the wrong one). */
class PasswordRequired(val wrongPassword: Boolean) : Exception("password required")

/**
 * Process-wide PDFium access.
 *
 * Every call runs on one dedicated thread. PDFium's thread safety only makes
 * individual FFI calls atomic — interleaving page, text and search sessions
 * from different threads corrupts results even across separate documents,
 * which is exactly what the desktop build's global gate exists to prevent.
 */
object PdfEngine {

    /** Single worker thread: the serialization point for all PDFium work. */
    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdfium").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    @Volatile
    private var core: PdfiumCore? = null

    private fun core(context: Context): PdfiumCore =
        core ?: synchronized(this) {
            core ?: PdfiumCore(context.applicationContext).also { core = it }
        }

    /**
     * Opens a document. Throws [PasswordRequired] when the file is encrypted,
     * so the caller can prompt and retry with the same source.
     */
    suspend fun open(
        context: Context,
        source: DocSource,
        password: String? = null,
    ): PdfDoc = withContext(dispatcher) {
        val app = context.applicationContext
        val pfd: ParcelFileDescriptor = when (source) {
            is DocSource.Content ->
                app.contentResolver.openFileDescriptor(source.uri, "r")
                    ?: throw java.io.IOException("Could not open ${source.uri}")
            is DocSource.Local ->
                ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            val doc = try {
                core(app).newDocument(pfd, password)
            } catch (e: PdfPasswordException) {
                throw PasswordRequired(wrongPassword = !password.isNullOrEmpty())
            }
            PdfDoc.measure(doc, pfd, source)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }
}
