package com.paperview.mobile.cloud

import android.util.Xml
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3-compatible storage — Cloudflare R2, Wasabi, AWS, MinIO, iDrive e2…
 * Requests are signed with AWS Signature V4. Folders are key prefixes; "New
 * folder" writes an empty `name/` object, as the AWS console does.
 */
class S3Backend(acct: Account, secret: String) : Backend {

    private val http = Http.client(acct.insecureTls)
    private val scheme: String
    private val host: String
    private val bucket = acct.bucket.trim()
    private val pathStyle: Boolean
    private val region = acct.region.trim().ifEmpty { "us-east-1" }
    private val access = acct.user.trim()
    private val secretKey = secret.trim()
    private val prefix = acct.folder.trim().trim('/')

    init {
        val endpoint = acct.address.trim()
        scheme = if (endpoint.contains("://")) endpoint.substringBefore("://").lowercase() else "https"
        val h = endpoint.substringAfter("://").substringBefore('/')
        if (h.isEmpty()) throw CloudException("The endpoint needs a host, e.g. https://s3.eu-central-1.wasabisys.com")
        // AWS itself wants bucket.host; everything else is happiest with host/bucket.
        pathStyle = !h.endsWith("amazonaws.com")
        host = if (pathStyle) h else "$bucket.$h"
    }

    private fun key(path: String) = joinPath(prefix, path)

    private fun unkey(key: String) = if (prefix.isEmpty()) key else key.removePrefix(prefix).trimStart('/')

    private fun uriPath(key: String): String = when {
        pathStyle && key.isEmpty() -> "/${percentEncode(bucket, false)}"
        pathStyle -> "/${percentEncode(bucket, false)}/${percentEncode(key, true)}"
        else -> "/${percentEncode(key, true)}"
    }

    private fun request(
        method: String,
        key: String,
        query: List<Pair<String, String>> = emptyList(),
        extra: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        progress: ((Long) -> Unit)? = null,
        what: String,
    ): okhttp3.Response {
        val path = uriPath(key)
        val qs = canonicalQuery(query)
        val payload = sha256Hex(body ?: ByteArray(0))
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val headers = mutableListOf(
            "host" to host,
            "x-amz-content-sha256" to payload,
            "x-amz-date" to amzDate,
        )
        extra.forEach { (k, v) -> headers.add(k.lowercase() to v) }
        val auth = sign(method, path, qs, headers, payload, access, secretKey, region, amzDate)
        val url = "$scheme://$host$path" + if (qs.isEmpty()) "" else "?$qs"
        val builder = Request.Builder().url(url).header("Authorization", auth)
        headers.filter { it.first != "host" }.forEach { (k, v) -> builder.header(k, v) }
        val reqBody = when {
            body == null -> null
            progress != null -> countingBody(body, "application/octet-stream", progress)
            else -> body.toRequestBody()
        }
        builder.method(method, reqBody ?: if (method == "PUT") ByteArray(0).toRequestBody() else null)
        return Http.check(what, http.newCall(builder.build()).execute())
    }

    override fun list(dir: String): List<CloudFile> {
        val p = key(dir).let { if (it.isEmpty()) it else "$it/" }
        val out = ArrayList<CloudFile>()
        var token = ""
        while (true) {
            val q = mutableListOf("list-type" to "2", "delimiter" to "/", "prefix" to p)
            if (token.isNotEmpty()) q.add("continuation-token" to token)
            val text = request("GET", "", q, what = "Could not list files").use { it.body!!.string() }
            var more = false
            token = ""
            val parser = Xml.newPullParser().apply { setInput(text.reader()) }
            var tag = ""
            var inContents = false
            var inPrefixes = false
            var k = ""
            var size = 0L
            var modified = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "Contents") { inContents = true; k = ""; size = 0; modified = "" }
                        if (tag == "CommonPrefixes") inPrefixes = true
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text
                        when {
                            inContents && tag == "Key" -> k += t
                            inContents && tag == "Size" -> size = t.trim().toLongOrNull() ?: 0
                            inContents && tag == "LastModified" -> modified += t
                            inPrefixes && tag == "Prefix" -> out.add(CloudFile.dir(unkey(t.trim())))
                            !inContents && !inPrefixes && tag == "IsTruncated" -> more = t.trim() == "true"
                            !inContents && !inPrefixes && tag == "NextContinuationToken" -> token += t.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Contents") {
                            inContents = false
                            // Skip the folder's own marker object.
                            if (k != p && !k.endsWith("/")) {
                                out.add(CloudFile(unkey(k), size, parseIso(modified)))
                            }
                        }
                        if (parser.name == "CommonPrefixes") inPrefixes = false
                        tag = ""
                    }
                }
            }
            if (!more || token.isEmpty() || out.size >= 10_000) break
        }
        return out
    }

    override fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long =
        request("GET", key(path), what = "Download failed").use { Http.stream(it, out, progress) }

    override fun put(path: String, data: ByteArray, progress: (Long) -> Unit) {
        request("PUT", key(path), body = data, progress = progress, what = "Upload failed").close()
    }

    override fun delete(path: String, id: String) {
        request("DELETE", key(path), what = "Could not delete $path").close()
    }

    /** S3 has no rename: copy server-side, then delete the original. */
    override fun rename(from: String, id: String, to: String) {
        val source = "/${percentEncode(bucket, false)}/${percentEncode(key(from), true)}"
        request(
            "PUT",
            key(to),
            extra = listOf("x-amz-copy-source" to source),
            body = ByteArray(0),
            what = "Could not copy $from to $to",
        ).close()
        delete(from, "")
    }

    override fun mkdir(path: String) {
        request("PUT", key(path) + "/", body = ByteArray(0), what = "Could not make the folder").close()
    }

    override val needsFolders: Boolean get() = false

    companion object {
        fun canonicalQuery(query: List<Pair<String, String>>): String =
            query.map { (k, v) -> percentEncode(k, false) to percentEncode(v, false) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray())
            }

        /** The `Authorization` header for a request (AWS Signature V4). */
        fun sign(
            method: String,
            path: String,
            query: String,
            headers: List<Pair<String, String>>,
            payload: String,
            access: String,
            secret: String,
            region: String,
            amzDate: String,
        ): String {
            val sorted = headers.map { it.first.lowercase() to it.second.trim() }.sortedBy { it.first }
            val canonicalHeaders = sorted.joinToString("") { "${it.first}:${it.second}\n" }
            val signed = sorted.joinToString(";") { it.first }
            val request = "$method\n$path\n$query\n$canonicalHeaders\n$signed\n$payload"
            val date = amzDate.substring(0, 8)
            val scope = "$date/$region/s3/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(request.toByteArray())}"
            var k = hmac("AWS4$secret".toByteArray(), date)
            k = hmac(k, region)
            k = hmac(k, "s3")
            k = hmac(k, "aws4_request")
            val signature = hmac(k, toSign).joinToString("") { "%02x".format(it) }
            return "AWS4-HMAC-SHA256 Credential=$access/$scope, SignedHeaders=$signed, Signature=$signature"
        }

        private fun parseIso(s: String): Long = runCatching {
            val t = s.trim().replace(Regex("\\.\\d+Z$"), "Z")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(t)!!.time
        }.getOrDefault(0L)
    }
}
