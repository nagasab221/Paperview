package com.paperview.mobile.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Backblaze B2 through its native JSON API (no request signing). Folders are
 * name prefixes; an empty folder is kept alive by a zero-byte `.bzEmpty` file,
 * the same convention as Backblaze's web UI and the desktop build.
 */
class B2Backend(acct: Account, secret: String) : Backend {

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
        val fileId: String? = null,
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

    private val json = Json { ignoreUnknownKeys = true }
    private val http = Http.client(false)
    private val apiUrl: String
    private val downloadUrl: String
    private val token: String
    private val bucketId: String
    private val bucketName = acct.bucket.trim()
    private val prefix = acct.folder.trim().trim('/')

    init {
        val resp = http.newCall(
            Request.Builder()
                .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
                .header("Authorization", Http.basic(acct.user.trim(), secret.trim()))
                .build(),
        ).execute()
        val auth = json.decodeFromString<AuthResp>(
            Http.check("Backblaze didn't accept the Key ID / Application key", resp)
                .use { it.body!!.string() },
        )
        apiUrl = auth.apiUrl
        downloadUrl = auth.downloadUrl
        token = auth.token
        val buckets = json.decodeFromString<BucketsResp>(
            api(
                "b2_list_buckets",
                buildJsonObject {
                    put("accountId", auth.accountId)
                    put("bucketName", bucketName)
                }.toString(),
                "Could not list buckets",
            ),
        )
        bucketId = buckets.buckets.firstOrNull { it.bucketName == bucketName }?.bucketId
            ?: throw CloudException(
                "Bucket “$bucketName” not found (is the application key allowed to access it?)",
            )
    }

    private fun api(call: String, body: String, what: String): String {
        val resp = http.newCall(
            Request.Builder()
                .url("$apiUrl/b2api/v2/$call")
                .header("Authorization", token)
                .post(body.toRequestBody(JSON))
                .build(),
        ).execute()
        return Http.check(what, resp).use { it.body!!.string() }
    }

    private fun key(path: String) = joinPath(prefix, path)

    private fun unkey(key: String): String =
        if (prefix.isEmpty()) key else key.removePrefix(prefix).trimStart('/')

    override fun list(dir: String): List<CloudFile> {
        val p = key(dir).let { if (it.isEmpty()) it else "$it/" }
        val out = ArrayList<CloudFile>()
        var start: String? = null
        while (true) {
            val body = buildJsonObject {
                put("bucketId", bucketId)
                put("maxFileCount", 1000)
                put("prefix", p)
                put("delimiter", "/")
                start?.let { put("startFileName", it) }
            }.toString()
            val listing = json.decodeFromString<ListResp>(api("b2_list_file_names", body, "Could not list files"))
            for (f in listing.files) {
                val path = unkey(f.fileName)
                when (f.action) {
                    "folder" -> out.add(CloudFile.dir(path))
                    "", "upload" -> out.add(
                        CloudFile(path, f.contentLength, f.uploadTimestamp, f.fileId.orEmpty()),
                    )
                }
            }
            val next = listing.nextFileName
            if (next == null || out.size >= 10_000) break
            start = next
        }
        return out
    }

    override fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long {
        val url = "$downloadUrl/file/$bucketName/${percentEncode(key(path), keepSlash = true)}"
        val resp = http.newCall(Request.Builder().url(url).header("Authorization", token).build()).execute()
        return Http.check("Download failed", resp).use { Http.stream(it, out, progress) }
    }

    private fun upload(key: String, data: ByteArray, type: String, progress: (Long) -> Unit) {
        val sha = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
        val up = json.decodeFromString<UploadUrlResp>(
            api("b2_get_upload_url", buildJsonObject { put("bucketId", bucketId) }.toString(), "Could not get an upload URL"),
        )
        val resp = http.newCall(
            Request.Builder()
                .url(up.uploadUrl)
                .header("Authorization", up.token)
                .header("X-Bz-File-Name", percentEncode(key, keepSlash = true))
                .header("X-Bz-Content-Sha1", sha)
                .post(countingBody(data, type, progress))
                .build(),
        ).execute()
        Http.check("Upload failed", resp).close()
    }

    override fun put(path: String, data: ByteArray, progress: (Long) -> Unit) =
        upload(key(path), data, if (isDocument(path)) "application/pdf" else "b2/x-auto", progress)

    private fun fileId(key: String): String {
        val body = buildJsonObject {
            put("bucketId", bucketId)
            put("startFileName", key)
            put("maxFileCount", 1)
        }.toString()
        val listing = json.decodeFromString<ListResp>(api("b2_list_file_names", body, "Could not look up the file"))
        return listing.files.firstOrNull { it.fileName == key }?.fileId
            ?: throw CloudException("$key is no longer in the bucket")
    }

    override fun delete(path: String, id: String) {
        val k = key(path)
        val fid = id.ifEmpty { fileId(k) }
        api(
            "b2_delete_file_version",
            buildJsonObject { put("fileName", k); put("fileId", fid) }.toString(),
            "Could not delete $path",
        )
    }

    /** B2 has no rename: copy server-side under the new name, then delete. */
    override fun rename(from: String, id: String, to: String) {
        val fid = id.ifEmpty { fileId(key(from)) }
        api(
            "b2_copy_file",
            buildJsonObject {
                put("sourceFileId", fid)
                put("fileName", key(to))
                put("metadataDirective", "COPY")
            }.toString(),
            "Could not copy $from to $to",
        )
        delete(from, fid)
    }

    override fun mkdir(path: String) =
        upload(joinPath(key(path), ".bzEmpty"), ByteArray(0), "application/x-bzEmpty") {}

    override val needsFolders: Boolean get() = false

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
