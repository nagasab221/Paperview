package com.paperview.mobile.landing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import com.paperview.mobile.files.FileAccess
import com.paperview.mobile.pdf.PdfEngine
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.snapPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * First-page covers for the Library.
 *
 * Rendering every document on every visit would mean opening N PDFs each time
 * the tab is shown, so covers are written to the cache once and read back as
 * plain JPEGs afterwards. The cache key includes the size the file was when it
 * was rendered, so a document that changes gets a fresh cover.
 */
object ThumbnailStore {

    /** PDFium work is serialised anyway; this stops a long list queueing hundreds deep. */
    private val gate = Semaphore(2)

    private fun dir(context: Context): File =
        File(context.cacheDir, "covers").apply { mkdirs() }

    private fun keyFor(uri: String, sizeBytes: Long, widthPx: Int): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$uri|$sizeBytes|$widthPx".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Cover for one document, rendered on first request and cached after.
     * Returns null when the file can't be opened — a stale Recent entry, a
     * revoked permission, or a document that needs a password.
     */
    suspend fun cover(
        context: Context,
        uriKey: String,
        widthPx: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val source = FileAccess.sourceFor(uriKey)
        val size = FileAccess.sizeOf(context, android.net.Uri.parse(uriKey)).takeIf { it > 0 }
            ?: runCatching { (source as? com.paperview.mobile.pdf.DocSource.Local)?.file?.length() }
                .getOrNull() ?: 0L
        val cached = File(dir(context), keyFor(uriKey, size, widthPx) + ".jpg")
        if (cached.exists() && cached.length() > 0) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return@withContext it }
        }

        gate.withPermit {
            val doc = runCatching { PdfEngine.open(context, source, null) }.getOrNull()
                ?: return@withPermit null
            try {
                if (doc.pageCount == 0) return@withPermit null
                val box = doc.pages[0]
                val w = snapPx(widthPx.toFloat())
                val h = snapPx(widthPx * box.heightPt / box.widthPt)
                val bitmap = doc.render(0, w, h, Rotation.R0)
                runCatching {
                    cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                }
                bitmap
            } catch (t: Throwable) {
                null
            } finally {
                doc.close()
            }
        }
    }

    /** Drops every cached cover — used when clearing caches from Settings. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun cacheBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}

/** Loads a cover for [uriKey], recomposing once it's ready. */
@Composable
fun rememberCover(uriKey: String, widthPx: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, uriKey, widthPx) {
        value = ThumbnailStore.cover(context, uriKey, widthPx)
    }
}
