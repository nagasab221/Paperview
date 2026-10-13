package com.paperview.mobile.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import com.paperview.mobile.pdf.DocSource
import java.io.File

/**
 * Getting at PDFs two ways, as configured in Settings:
 *
 *  * the system picker (SAF), which needs no permission at all; and
 *  * an optional in-app folder browser, which needs all-files access.
 *
 * SAF is the default because it works everywhere and survives reboots via
 * persisted URI grants; the browser is opt-in for people who'd rather walk
 * their own Downloads folder.
 */
object FileAccess {

    /** Human-readable name for a content URI, falling back to its last segment. */
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "Document.pdf"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) return c.getLong(i)
            }
        }
        return 0L
    }

    /**
     * Holds on to read access across restarts, returning whether it worked.
     *
     * Only the system picker hands out a grant that can be kept. A document
     * arriving by intent — tapped in a file manager, sent from a chat — comes
     * with a one-shot grant that dies with the task, and asking to persist it
     * throws. That return value is the difference between a Recent entry that
     * still opens tomorrow and one that doesn't, so callers must act on it
     * rather than letting the failure disappear into a `runCatching`.
     */
    fun persist(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** True when we can still read [uri] — Recent entries can go stale. */
    fun canRead(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return File(uri.path ?: return false).canRead()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** Rebuilds a [DocSource] from a stored recents key. */
    fun sourceFor(key: String): DocSource =
        if (key.startsWith("content://")) DocSource.Content(Uri.parse(key))
        else DocSource.Local(File(key))

    // ── All-files browser ────────────────────────────────────────────────────

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Settings screen where the user grants all-files access, if it exists. */
    fun allFilesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** Directories worth offering as browser starting points. */
    fun browseRoots(): List<File> = buildList {
        val ext = Environment.getExternalStorageDirectory()
        listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
        ).forEach { name ->
            File(ext, name).takeIf { it.isDirectory }?.let { add(it) }
        }
        if (ext.isDirectory) add(ext)
    }

    /** Folders and PDFs in [dir], folders first, each group alphabetical. */
    fun listPdfs(dir: File): List<File> {
        val entries = dir.listFiles()?.filterNot { it.isHidden }.orEmpty()
        val dirs = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val pdfs = entries
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        return dirs + pdfs
    }

    /** Intent for the system picker, restricted to PDFs. */
    fun openDocumentIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    /** Intent for "Save a copy", pre-filled with the document's name. */
    fun createDocumentIntent(name: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name.ifBlank { "document.pdf" })
        }

    /** Copies a document's bytes to a destination the user picked. */
    fun copyTo(context: Context, from: DocSource, to: Uri): Boolean = runCatching {
        val input = when (from) {
            is DocSource.Content -> context.contentResolver.openInputStream(from.uri)
            is DocSource.Local -> from.file.inputStream()
        } ?: return false
        input.use { src ->
            context.contentResolver.openOutputStream(to)?.use { dst -> src.copyTo(dst) }
                ?: return false
        }
        true
    }.getOrDefault(false)

    /**
     * A local file for [source], copying a content URI into the cache when
     * needed — printing and uploading both need real bytes on disk.
     */
    fun materialize(context: Context, source: DocSource, name: String): File? = runCatching {
        val wanted = shareName(name)
        // A local file already under the right name needs no copy. One stored
        // under a different name does: a shelf copy is named after a hash of
        // where it came from, and a download after whatever the bucket called
        // it — neither is a name worth handing to someone else.
        if (source is DocSource.Local && source.file.name == wanted) return source.file

        val staging = File(context.cacheDir, "share").apply { mkdirs() }
        sweepStaging(staging)
        val out = File(staging, wanted)
        val input = when (source) {
            is DocSource.Local -> source.file.inputStream()
            is DocSource.Content -> context.contentResolver.openInputStream(source.uri)
        } ?: return null
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        out
    }.getOrNull()

    /**
     * Drops staged copies old enough that the app they were handed to is long
     * finished with them.
     *
     * Every share, print and upload writes a full copy of the document here and
     * nothing ever removed it, so the directory grew without limit — and it grew
     * faster once shelf copies, which are named after a digest, started being
     * renamed on the way out. Nothing is deleted promptly: a receiver may still
     * be reading the URI it was given, and pulling the file out from under it
     * would break the share this is meant to serve.
     */
    private fun sweepStaging(dir: File) {
        val cutoff = System.currentTimeMillis() - STAGING_TTL_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    /** Long enough that no receiver is still reading a staged copy. */
    private const val STAGING_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * A filename safe to write and to hand to another app.
     *
     * Titles can come from PDF metadata rather than a filename, so they may
     * contain path separators or no extension at all — and a file without
     * `.pdf` gets rejected by some receiving apps.
     */
    internal fun shareName(name: String): String {
        val cleaned = name.ifBlank { "document.pdf" }
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .take(120)
            .ifBlank { "document.pdf" }
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }

    /** True if the URI still points at something (used to prune Recents). */
    fun exists(context: Context, key: String): Boolean =
        if (key.startsWith("content://")) canRead(context, Uri.parse(key))
        else File(key).exists()

    @Suppress("unused")
    fun documentTreeUri(uri: Uri): Boolean = DocumentsContract.isTreeUri(uri)
}
