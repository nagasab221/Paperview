package com.paperview.mobile.cloud

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * A folder standing in for the cloud: any folder picked with the system's
 * folder picker — on the phone, or one a NAS / Nextcloud / SMB app exposes as
 * a document provider. Its documents open in place.
 */
class FolderBackend(private val context: Context, acct: Account) : Backend {

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(acct.address.trim()))
        ?.takeIf { it.exists() && it.isDirectory }
        ?: throw CloudException(
            "${folderLabel(acct.address)} can't be opened any more — pick the folder again in Settings.",
        )

    /** The document at [path], or null. */
    fun find(path: String): DocumentFile? {
        var cur: DocumentFile = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur = cur.findFile(part) ?: return null
        }
        return cur
    }

    private fun need(path: String, what: String) =
        find(path) ?: throw CloudException("$what: ${lastPart(path).ifEmpty { "the folder" }} isn't there")

    override fun list(dir: String): List<CloudFile> {
        val folder = need(dir, "Could not read the folder")
        return folder.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val path = joinPath(dir, name)
            when {
                f.isDirectory -> CloudFile.dir(path)
                f.isFile -> CloudFile(path, f.length(), f.lastModified())
                else -> null
            }
        }
    }

    override fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long {
        val f = need(path, "Could not open the file")
        val input = context.contentResolver.openInputStream(f.uri)
            ?: throw CloudException("Could not open ${lastPart(path)}")
        return copy(input, out, progress)
    }

    override fun put(path: String, data: ByteArray, progress: (Long) -> Unit) {
        val dir = need(parentPath(path), "Could not save")
        val name = lastPart(path)
        // Replace an existing file in place; createFile would add " (1)".
        val target = dir.findFile(name)?.takeIf { it.isFile }
            ?: dir.createFile(if (name.lowercase().endsWith(".pdf")) "application/pdf" else "application/octet-stream", name)
            ?: throw CloudException("Could not create $name")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            var sent = 0
            while (sent < data.size) {
                val n = minOf(256 * 1024, data.size - sent)
                out.write(data, sent, n)
                sent += n
                progress(sent.toLong())
            }
        } ?: throw CloudException("Could not write $name")
    }

    override fun delete(path: String, id: String) {
        if (!need(path, "Could not delete").delete()) throw CloudException("Could not delete ${lastPart(path)}")
    }

    override fun rename(from: String, id: String, to: String) {
        val f = need(from, "Could not rename")
        if (find(to) != null) throw CloudException("There's already a file called ${lastPart(to)}")
        if (!f.renameTo(lastPart(to))) throw CloudException("Could not rename ${lastPart(from)}")
    }

    override fun mkdir(path: String) {
        val parent = need(parentPath(path), "Could not make the folder")
        if (parent.findFile(lastPart(path))?.isDirectory == true) return
        parent.createDirectory(lastPart(path)) ?: throw CloudException("Could not make the folder")
    }
}
