package com.paperview.mobile.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.paperview.mobile.store.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A place in a book: block, and character within it. */
@Serializable
data class Spot(val block: Int = 0, val char: Int = 0) : Comparable<Spot> {
    override fun compareTo(other: Spot) = compareValuesBy(this, other, { it.block }, { it.char })
}

@Serializable
data class Bookmark(val spot: Spot, val label: String, val at: Long)

/** One book on the shelf, with where you were and your bookmarks. */
@Serializable
data class BookEntry(
    /** The book's file (an app-private copy, or a cloud download). */
    val path: String,
    val name: String,
    val title: String = "",
    val author: String? = null,
    val spot: Spot = Spot(),
    /** 0–1, for the shelf. */
    val progress: Float = 0f,
    val opened: Long = 0,
    val bookmarks: List<Bookmark> = emptyList(),
)

object BookStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    suspend fun list(prefs: Prefs): List<BookEntry> =
        runCatching { json.decodeFromString<List<BookEntry>>(prefs.booksRaw.first()) }.getOrDefault(emptyList())

    suspend fun find(prefs: Prefs, path: String): BookEntry? = list(prefs).firstOrNull { it.path == path }

    suspend fun update(prefs: Prefs, path: String, change: (BookEntry?) -> BookEntry?) = lock.withLock {
        val current = list(prefs)
        val old = current.firstOrNull { it.path == path }
        val new = change(old)
        val rest = current.filterNot { it.path == path }
        val next = if (new == null) rest else (listOf(new) + rest)
        prefs.setBooksRaw(json.encodeToString(next.sortedByDescending { it.opened }))
    }

    suspend fun remove(context: Context, prefs: Prefs, entry: BookEntry) {
        update(prefs, entry.path) { null }
        // Our own copy goes too; a cloud download stays (the Cloud tab owns it).
        val f = File(entry.path)
        if (f.startsWith(dir(context))) f.delete()
        coverFile(context, entry.path).delete()
    }

    private fun dir(context: Context) = File(context.filesDir, "books").apply { mkdirs() }

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * A local file for a book: parsers need random access, and a content URI
     * from another app may not be readable tomorrow, so it's copied once.
     */
    fun localCopy(context: Context, uri: Uri, name: String): File? = runCatching {
        val ext = if (name.lowercase().endsWith(".fb2.zip")) "fb2.zip" else name.substringAfterLast('.', "epub")
        val out = File(dir(context), "${hash(uri.toString())}.$ext")
        if (!out.exists() || out.length() == 0L) {
            val tmp = File(out.parentFile, ".${out.name}.part")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return null
            tmp.renameTo(out)
        }
        out
    }.getOrNull()

    private fun coverFile(context: Context, path: String) =
        File(File(context.cacheDir, "book_covers").apply { mkdirs() }, hash(path) + ".png")

    /** The cover as a small bitmap, made once and kept. Call off the main thread. */
    fun cover(context: Context, entry: BookEntry, widthPx: Int): Bitmap? {
        val cached = coverFile(context, entry.path)
        if (cached.exists()) return BitmapFactory.decodeFile(cached.path)
        val bytes = Ebooks.cover(File(entry.path), entry.name) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= widthPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        runCatching { cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        return bmp
    }
}
