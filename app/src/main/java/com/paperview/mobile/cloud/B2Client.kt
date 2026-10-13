package com.paperview.mobile.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Minimal Backblaze B2 client — native JSON API, no OAuth and no request
 * signing. A port of the desktop build's `cloud.rs`, so both versions talk to
 * the same bucket in the same way.
 */

@Serializable
data class B2Config(
    val keyId: String = "",
    val appKey: String = "",
    val bucket: String = "",
) {
    val ready: Boolean
        get() = keyId.isNotBlank() && appKey.isNotBlank() && bucket.isNotBlank()

    /**
     * B2 hands out a short `keyID` and a long `K005…` applicationKey, and it is
     * genuinely easy to paste them into the wrong boxes — the desktop build
     * added the same warning after it happened.
     */
    val looksSwapped: Boolean
        get() = appKey.isNotBlank() && !appKey.startsWith("K")
}

data class CloudFile(
    val name: String,
    val size: Long,
    val uploadedMs: Long,
    val fileId: String,
)

class B2Exception(message: String) : Exception(message)

@Serializable
private data class AuthResp(
    val accountId: String,
    val apiUrl: String,
    val downloadUrl: String,
    @SerialName("authorizationToken") val token: String,
)

@Serializable
private data class Bucket(val bucketId: String, val bucketName: String)

@Serializable
private data class BucketsResp(val buckets: List<Bucket>)

@Serializable
private data class FileInfo(
    val fileName: String,
    val fileId: String = "",
    val contentLength: Long = 0,
    val uploadTimestamp: Long = 0,
    val action: String = "",
)

@Serializable
private data class ListResp(
    val files: List<FileInfo> = emptyList(),
    val nextFileName: String? = null,
)

@Serializable
private data class UploadUrlResp(
    val uploadUrl: String,
    @SerialName("authorizationToken") val token: String,
)

class B2Session internal constructor(
    internal val apiUrl: String,
    internal val downloadUrl: String,
    internal val token: String,
    internal val bucketId: String,
    internal val bucketName: String,
)

class B2Client(private val cfg: B2Config) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun connect(): B2Session = withContext(Dispatchers.IO) {
        if (!cfg.ready) throw B2Exception("Backblaze credentials are not set")
        val creds = Base64.getEncoder()
            .encodeToString("${cfg.keyId.trim()}:${cfg.appKey.trim()}".toByteArray())
        val auth = json.decodeFromString<AuthResp>(
            get(
                "https://api.backblazeb2.com/b2api/v2/b2_authorize_account",
                "Basic $creds",
                onError = "Authorization failed (check Key ID / Application Key)",
            ),
        )

        val bucketsBody = """{"accountId":"${auth.accountId}","bucketName":"${cfg.bucket.trim()}"}"""
        val buckets = json.decodeFromString<BucketsResp>(
            post(
                "${auth.apiUrl}/b2api/v2/b2_list_buckets",
                auth.token,
                bucketsBody,
                onError = "Could not list buckets",
            ),
        )
        val bucket = buckets.buckets.firstOrNull { it.bucketName == cfg.bucket.trim() }
            ?: throw B2Exception(
                "Bucket \"${cfg.bucket.trim()}\" not found " +
                    "(is the application key allowed to access it?)",
            )
        B2Session(auth.apiUrl, auth.downloadUrl, auth.token, bucket.bucketId, bucket.bucketName)
    }

    /** Full listing, paginated, so the stats card totals are accurate. */
    suspend fun list(s: B2Session): List<CloudFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudFile>()
        var start: String? = null
        while (true) {
            val body = buildString {
                append("""{"bucketId":"${s.bucketId}","maxFileCount":1000""")
                start?.let { append(""","startFileName":"${it.jsonEscape()}"""") }
                append("}")
            }
            val resp = json.decodeFromString<ListResp>(
                post("${s.apiUrl}/b2api/v2/b2_list_file_names", s.token, body, "Could not list files"),
            )
            resp.files
                .filter { it.action.isEmpty() || it.action == "upload" }
                .forEach {
                    out.add(CloudFile(it.fileName, it.contentLength, it.uploadTimestamp, it.fileId))
                }
            val next = resp.nextFileName
            if (next == null || out.size >= MAX_LISTING) break
            start = next
        }
        out
    }

    suspend fun upload(
        s: B2Session,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): CloudFile = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val up = json.decodeFromString<UploadUrlResp>(
            post(
                "${s.apiUrl}/b2api/v2/b2_get_upload_url",
                s.token,
                """{"bucketId":"${s.bucketId}"}""",
                "Could not get upload URL",
            ),
        )

        val req = Request.Builder()
            .url(up.uploadUrl)
            .header("Authorization", up.token)
            .header("X-Bz-File-Name", percentEncode(file.name, keepSlash = false))
            .header("Content-Type", "application/pdf")
            .header("X-Bz-Content-Sha1", sha)
            .post(countingBody(bytes, onProgress))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw B2Exception("Upload failed: ${resp.code} ${resp.message}")
            }
        }
        CloudFile(file.name, bytes.size.toLong(), System.currentTimeMillis(), "")
    }

    /** Downloads to [dest], reporting bytes and transfer speed as it goes. */
    suspend fun download(
        s: B2Session,
        name: String,
        expected: Long,
        dest: File,
        onProgress: (done: Long, total: Long, bytesPerSecond: Double) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val url = "${s.downloadUrl}/file/${s.bucketName}/${percentEncode(name, keepSlash = true)}"
        val req = Request.Builder().url(url).header("Authorization", s.token).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw B2Exception("Download failed: ${resp.code} ${resp.message}")
            }
            dest.parentFile?.mkdirs()
            val body = resp.body ?: throw B2Exception("Download failed: empty response")
            // Write to a temp file first so an interrupted transfer can never
            // leave a half-written PDF sitting in the cache looking valid.
            val tmp = File(dest.absolutePath + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = System.nanoTime()
                    var lastBytes = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        val elapsed = (now - lastEmit) / 1e9
                        if (elapsed >= 0.15) {
                            onProgress(done, maxOf(expected, done), (done - lastBytes) / elapsed)
                            lastEmit = now
                            lastBytes = done
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw B2Exception("Could not save download")
        }
        dest
    }

    suspend fun delete(s: B2Session, items: List<Pair<String, String>>): Int =
        withContext(Dispatchers.IO) {
            for ((name, id) in items) {
                post(
                    "${s.apiUrl}/b2api/v2/b2_delete_file_version",
                    s.token,
                    """{"fileName":"${name.jsonEscape()}","fileId":"${id.jsonEscape()}"}""",
                    "Could not delete $name",
                )
            }
            items.size
        }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    private fun get(url: String, auth: String, onError: String): String {
        val req = Request.Builder().url(url).header("Authorization", auth).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw B2Exception("$onError: ${resp.code}")
            return body
        }
    }

    private fun post(url: String, auth: String, body: String, onError: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw B2Exception("$onError: ${resp.code}")
            return text
        }
    }

    private fun countingBody(
        bytes: ByteArray,
        onProgress: (Long, Long) -> Unit,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = PDF_MEDIA
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            val chunk = 64 * 1024
            while (sent < bytes.size) {
                val n = minOf(chunk.toLong(), bytes.size - sent).toInt()
                sink.write(bytes, sent.toInt(), n)
                sent += n
                onProgress(sent, bytes.size.toLong())
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val PDF_MEDIA = "application/pdf".toMediaType()
        private const val MAX_LISTING = 10_000

        /** Where downloads land — mirrors the desktop's cloud cache directory. */
        fun cacheDir(context: Context): File =
            File(context.filesDir, "cloud").apply { mkdirs() }

        fun localPathFor(context: Context, name: String): File {
            // Uploads use flat names, but sanitise anything else defensively.
            val flat = name.map { if (it == '/' || it == '\\' || it == ':') '_' else it }
                .joinToString("")
            return File(cacheDir(context), flat)
        }

        /** (file count, total bytes) of the local download cache. */
        fun cacheStats(context: Context): Pair<Int, Long> {
            val files = cacheDir(context).listFiles()?.filter { it.isFile }.orEmpty()
            return files.size to files.sumOf { it.length() }
        }

        /**
         * Percent-encodes a name for B2 headers and URLs: UTF-8 bytes, keeping
         * only unreserved characters, and `/` only when it's part of a path.
         */
        fun percentEncode(s: String, keepSlash: Boolean): String {
            val out = StringBuilder(s.length * 3)
            for (b in s.toByteArray()) {
                val c = b.toInt() and 0xFF
                val keep = (c.toChar().isLetterOrDigit() && c < 128) ||
                    c.toChar() in "-._~" ||
                    (keepSlash && c.toChar() == '/')
                if (keep) out.append(c.toChar()) else out.append("%%%02X".format(c))
            }
            return out.toString()
        }

        /** "12.3 MB"-style sizes for the Saved tab. */
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
    }
}

private fun String.jsonEscape(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
