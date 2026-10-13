package com.paperview.mobile.store

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Private copies of documents Android will not let us open a second time.
 *
 * Tapping a PDF in a file manager, a chat or a mail app starts PaperView with a
 * one-shot read grant. It cannot be made persistable, and it dies with the
 * task — so at the next launch the document is unreadable, and the Recent entry
 * pointing at it was being dropped from the shelf without a word. Since
 * PaperView is the default PDF app that accounted for nearly everything opened,
 * which is why the shelf always looked empty.
 *
 * Documents opened through the system picker need none of this: that grant *is*
 * persistable, so those keep reading the original file and pick up any edit made
 * to it elsewhere. A copy is the fallback for the files Android won't let us
 * keep, not the normal path.
 *
 * **A copy is named after a digest of its contents**, never of where it came
 * from. Sharing the same document from WhatsApp twice produces two different
 * URIs, so naming copies after the source filed the same bytes twice, showed
 * the document twice on the shelf, and — the part that actually hurt — gave each
 * duplicate its own reading position, so reopening a document from a chat always
 * started from scratch. Identical bytes now resolve to one copy, one shelf
 * entry, and the page you were on.
 *
 * Copies are capped as a whole and individually, and the least recently read go
 * first: a shelf is worth some disk, but not an unbounded amount of it.
 */
object Shelf {

    private const val DIR = "shelf"
    private const val SUFFIX = ".pdf"

    /** Half-written copies, named so they can never be mistaken for finished ones. */
    private const val PART_SUFFIX = ".part"

    /** Total disk the copies may occupy before the least recently read go. */
    const val MAX_BYTES = 200L * 1024 * 1024

    /**
     * Largest single document worth copying.
     *
     * Past this the copy costs more than the convenience is worth, and one file
     * would crowd out the whole rest of the shelf. Such a document still opens
     * normally; it just won't be remembered once its grant expires.
     */
    const val MAX_FILE_BYTES = 80L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** The finished copies, ignoring anything half-written. */
    private fun copies(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }.orEmpty()

    /** True when [file] is one of our copies rather than a document elsewhere. */
    fun holds(context: Context, file: File): Boolean =
        file.parentFile?.absolutePath == dir(context).absolutePath

    /**
     * Copies the bytes behind [uri] onto the shelf, returning the copy.
     *
     * Blocking: call it off the main thread. Returns null when the document is
     * too large to be worth keeping, or when the read fails — callers fall back
     * to the live URI, which is still readable for as long as this task lives.
     *
     * [size] is only a hint, and often 0: providers aren't obliged to report one.
     * The real limit is enforced against the bytes as they arrive.
     */
    fun store(context: Context, uri: Uri, size: Long): File? {
        if (size > MAX_FILE_BYTES) return null
        val dir = dir(context)
        sweepPartials(dir)
        val temp = File(dir, "copy-${System.nanoTime()}$PART_SUFFIX")
        val digest = MessageDigest.getInstance("SHA-256")

        val written = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> drain(input, out, digest) }
            } ?: FAILED
        }.getOrDefault(FAILED)

        if (written <= 0) {
            temp.delete()
            return null
        }

        val dest = File(dir, digest.digest().hex() + SUFFIX)
        if (dest.isFile && dest.length() == written) {
            // The same bytes are already on the shelf. Reading them again is
            // what makes them recent, so the document you keep coming back to
            // is the last one evicted.
            temp.delete()
            touch(dest)
            return dest
        }
        dest.delete()
        if (!temp.renameTo(dest)) {
            temp.delete()
            return null
        }
        trim(context, keep = dest)
        return dest
    }

    /**
     * Streams [input] to [output] while digesting it, returning the byte count
     * or [FAILED] if the document turned out to be larger than we keep.
     *
     * The size a provider reports can't be trusted — many report none at all —
     * so the cap is enforced here, against bytes actually seen.
     */
    private fun drain(input: InputStream, output: OutputStream, digest: MessageDigest): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            total += read
            if (total > MAX_FILE_BYTES) return FAILED
        }
        return total
    }

    /** Marks a copy as just used, so eviction drops the least recently read. */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Drops the least recently read copies until the shelf is back under the
     * cap. [maxBytes] is a parameter so a test can fill the shelf without
     * writing two hundred megabytes to do it.
     */
    fun trim(context: Context, keep: File? = null, maxBytes: Long = MAX_BYTES) {
        val files = copies(context)
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (keep != null && file.absolutePath == keep.absolutePath) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** How many copies are held, and how much disk they take. */
    fun stats(context: Context): Pair<Int, Long> {
        val files = copies(context)
        return files.size to files.sumOf { it.length() }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    // ── Repairing shelves written before copies were content-addressed ───────

    /**
     * Renames existing copies to match their contents and reports what moved,
     * as old path to new path.
     *
     * Copies made by an earlier version are named after the URI they arrived
     * from, so the same document can sit here several times over. Renaming by
     * content makes those collide, and the duplicates collapse into one.
     */
    fun rekeyByContent(context: Context): Map<String, String> {
        val dir = dir(context)
        sweepPartials(dir)
        val moved = HashMap<String, String>()
        // Newest first, so when duplicates collide the survivor is the copy the
        // reader touched most recently.
        for (file in copies(context).sortedByDescending { it.lastModified() }) {
            val hash = runCatching { file.inputStream().use { digestOf(it) } }.getOrNull() ?: continue
            val dest = File(dir, hash + SUFFIX)
            if (dest.absolutePath == file.absolutePath) continue
            if (dest.isFile) {
                // A copy of these bytes already survived; this one is surplus.
                if (file.delete()) moved[file.absolutePath] = dest.absolutePath
            } else if (file.renameTo(dest)) {
                moved[file.absolutePath] = dest.absolutePath
            }
        }
        return moved
    }

    /**
     * Brings the shelf and the Recent list back into agreement, once.
     *
     * Runs on first launch after the upgrade: copies are renamed by content and
     * the Recent list follows them, so documents that were listed several times
     * become one entry holding the most recent reading position.
     */
    suspend fun consolidate(context: Context, prefs: Prefs) {
        if (prefs.shelfConsolidated.first()) return
        val moved = withContext(Dispatchers.IO) { rekeyByContent(context) }
        Recents.follow(prefs, moved)
        prefs.setShelfConsolidated(true)
    }

    /** Deletes copies abandoned half-written by a process that died mid-copy. */
    private fun sweepPartials(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(PART_SUFFIX)) it.delete() }
    }

    private fun digestOf(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().hex()
    }

    /** Short, stable, filesystem-safe name for a digest. */
    private fun ByteArray.hex(): String = take(10).joinToString("") { "%02x".format(it) }

    private const val FAILED = -1L
}
