package com.paperview.mobile.cloud

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Cloud accounts — the same model, field names and conventions as the desktop
 * build (`src/cloud/mod.rs` there), so both apps see the same files in the same
 * places. See the desktop's docs/ANDROID_CLOUD_PORT.md.
 */
@Serializable
enum class Kind {
    Folder, WebDav, S3, B2, Ssh;

    val label: String
        get() = when (this) {
            Folder -> "Folder"
            WebDav -> "WebDAV"
            S3 -> "S3"
            B2 -> "Backblaze B2"
            Ssh -> "SSH"
        }

    /** Needs a password / secret key (SSH: optional, the phone's key comes first). */
    val hasSecret: Boolean get() = this == WebDav || this == S3 || this == B2 || this == Ssh
}

@Serializable
data class Account(
    /** Stable: names the stored secret and the download folder. */
    val id: String = "",
    val name: String = "",
    val kind: Kind = Kind.Ssh,
    /** Folder: a tree URI · WebDAV: URL incl. path · S3: endpoint · SSH: host. */
    val address: String = "",
    /** WebDAV user (optional) · S3 access key ID · B2 key ID · SSH user. */
    val user: String = "",
    val bucket: String = "",
    /** S3 ("auto" for R2; empty = us-east-1). */
    val region: String = "",
    /** Folder inside the bucket (S3/B2) or on the server (SSH, default "PaperView"). */
    val folder: String = "",
    val port: String = "",
    @SerialName("insecure_tls") val insecureTls: Boolean = false,
    /** The secret, kept here only when the Keystore wasn't available. */
    @SerialName("plain_secret") val plainSecret: String? = null,
    /** SSH: the server's key fingerprint, remembered the first time. */
    @SerialName("host_key") val hostKey: String? = null,
) {
    /** Enough filled in to try connecting. */
    val ready: Boolean
        get() = when (kind) {
            Kind.Folder -> address.isNotBlank()
            Kind.WebDav -> address.isNotBlank()
            Kind.S3 -> address.isNotBlank() && user.isNotBlank() && bucket.isNotBlank()
            Kind.B2 -> user.isNotBlank() && bucket.isNotBlank()
            Kind.Ssh -> address.isNotBlank() && user.isNotBlank()
        }

    /** "SSH · nagasab@host:PaperView", for lists and the Library subtitle. */
    val summary: String
        get() {
            val where = when (kind) {
                Kind.Folder -> folderLabel(address)
                Kind.WebDav -> address.trim()
                Kind.S3 -> joinDisplay(
                    "${address.trim().removePrefix("https://").removePrefix("http://")}/${bucket.trim()}",
                    folder,
                )
                Kind.B2 -> joinDisplay(bucket.trim(), folder)
                Kind.Ssh -> {
                    val host = if (user.isBlank()) address.trim() else "${user.trim()}@${address.trim()}"
                    "$host:${sshFolder(folder)}"
                }
            }
            return "${kind.label} · $where"
        }

    fun trimmed(): Account {
        var a = copy(
            name = name.trim(),
            address = address.trim(),
            user = user.trim(),
            bucket = bucket.trim(),
            region = region.trim(),
            folder = folder.trim().trim('/'),
            port = port.trim(),
        )
        if (a.kind == Kind.Ssh && a.folder.isEmpty()) a = a.copy(folder = DEFAULT_SSH_FOLDER)
        return a
    }

    companion object {
        const val DEFAULT_SSH_FOLDER = "PaperView"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decodeList(raw: String): List<Account> =
            if (raw.isBlank()) emptyList()
            else runCatching { json.decodeFromString<List<Account>>(raw) }.getOrDefault(emptyList())

        fun encodeList(list: List<Account>): String = json.encodeToString(list)

        fun newId(): String =
            java.lang.Long.toHexString(System.currentTimeMillis()) +
                java.lang.Integer.toHexString((Math.random() * 0xFFFF).toInt())
    }
}

/** What a remote folder holds: subfolders and documents. */
data class CloudFile(
    /** From the account's top folder, `/`-separated: `Math/Calc.pdf`. */
    val path: String,
    val size: Long = 0,
    /** Modification / upload time, unix ms (0 when unknown). */
    val modifiedMs: Long = 0,
    /** B2 file id; empty elsewhere. */
    val id: String = "",
    val isDir: Boolean = false,
) {
    val name: String get() = lastPart(path)

    companion object {
        fun dir(path: String) = CloudFile(path.trimEnd('/'), isDir = true)
    }
}

class CloudException(message: String) : Exception(message)

fun sshFolder(folder: String): String =
    folder.trim().trim('/').ifEmpty { Account.DEFAULT_SSH_FOLDER }

private fun joinDisplay(a: String, b: String): String =
    b.trim().trim('/').let { if (it.isEmpty()) a else "$a/$it" }

/** A readable name for a picked folder's tree URI. */
fun folderLabel(treeUri: String): String {
    val decoded = android.net.Uri.decode(treeUri)
    val doc = decoded.substringAfter("/tree/", decoded).substringBefore("/document/")
    return doc.substringAfter(':').ifBlank { doc }.let { if (it.isBlank()) "Folder" else it }
}

// ── Paths ────────────────────────────────────────────────────────────────────

fun joinPath(dir: String, name: String): String {
    val d = dir.trim('/')
    val n = name.trim('/')
    return when {
        d.isEmpty() -> n
        n.isEmpty() -> d
        else -> "$d/$n"
    }
}

fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

fun lastPart(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** Documents PaperView shows and uploads: PDFs and books (as on the desktop). */
fun isDocument(name: String): Boolean =
    name.lowercase().endsWith(".pdf") || com.paperview.mobile.book.Ebooks.isEbook(name)

/** What a listing shows: folders and documents, no hidden files. */
internal fun keep(f: CloudFile): Boolean =
    f.name.isNotEmpty() && !f.name.startsWith(".") && (f.isDir || isDocument(f.name))

/** Folders first, then files, each by name ignoring case. */
internal fun sortListing(files: List<CloudFile>): List<CloudFile> =
    files.sortedWith(compareBy<CloudFile>({ !it.isDir }, { it.name.lowercase() }))

private fun safePart(s: String): String {
    val flat = s.map { if (it == '/' || it == '\\' || it == ':' || it.isISOControl()) '_' else it }
        .joinToString("")
    return if (flat.isEmpty() || flat == "." || flat == "..") "_" else flat
}

fun localName(name: String): String = safePart(name)

object CloudCache {
    /** All downloads: one folder per account. */
    fun root(context: Context): File = File(context.filesDir, "cloud").apply { mkdirs() }

    fun accountDir(context: Context, account: String): File = File(root(context), safePart(account))

    /** Where a file of [account] downloads to; subfolders are kept. */
    fun pathFor(context: Context, account: String, path: String): File {
        var out = accountDir(context, account)
        path.split('/').filter { it.isNotEmpty() }.forEach { out = File(out, safePart(it)) }
        return out
    }

    /** (count, bytes) of every download. */
    fun stats(context: Context): Pair<Int, Long> {
        val files = root(context).walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    fun clear(context: Context) {
        root(context).listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** "12.3 MB"-style sizes. */
fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val b = bytes.toDouble()
    return when {
        b >= kb * kb * kb -> "%.2f GB".format(b / (kb * kb * kb))
        b >= kb * kb -> "%.1f MB".format(b / (kb * kb))
        b >= kb -> "%.0f KB".format(b / kb)
        else -> "$bytes B"
    }
}

/** UTF-8 percent-encoding, unreserved characters kept, `/` only when asked. */
fun percentEncode(s: String, keepSlash: Boolean): String {
    val out = StringBuilder(s.length * 3)
    for (b in s.toByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        val keep = (c < 128 && ch.isLetterOrDigit()) || ch in "-._~" || (keepSlash && ch == '/')
        if (keep) out.append(ch) else out.append("%%%02X".format(c))
    }
    return out.toString()
}

/** "3 documents and 2 folders", "empty so far". */
fun listingSummary(files: List<CloudFile>): String {
    val dirs = files.count { it.isDir }
    val docs = files.size - dirs
    fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"
    return when {
        docs == 0 && dirs == 0 -> "empty so far"
        dirs == 0 -> plural(docs, "document")
        docs == 0 -> plural(dirs, "folder")
        else -> "${plural(docs, "document")} and ${plural(dirs, "folder")}"
    }
}
