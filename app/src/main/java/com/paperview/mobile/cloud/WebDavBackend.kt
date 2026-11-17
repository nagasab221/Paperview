package com.paperview.mobile.cloud

import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WebDAV: Nextcloud / ownCloud, Synology, QNAP, TrueNAS, OpenMediaVault,
 * Koofr… Basic authentication over HTTP(S).
 */
class WebDavBackend(acct: Account, secret: String) : Backend {

    private val http = Http.client(acct.insecureTls)
    /** `scheme://host[:port]` */
    private val origin: String
    /** The account's folder on the server, encoded once, no trailing slash. */
    private val basePath: String
    private val auth: String? = acct.user.trim().takeIf { it.isNotEmpty() }?.let { Http.basic(it, secret) }

    init {
        val raw = acct.address.trim().let { if (it.contains("://")) it else "https://$it" }
        val scheme = raw.substringBefore("://").lowercase()
        val rest = raw.substringAfter("://")
        val host = rest.substringBefore('/')
        if (host.isEmpty()) throw CloudException("The server address needs a host, e.g. https://nas.local:5006/PaperView")
        val path = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
        origin = "$scheme://$host"
        basePath = percentEncode(android.net.Uri.decode(path.trimEnd('/')), keepSlash = true)
    }

    private fun serverPath(rel: String, dir: Boolean): String {
        val r = rel.trim('/')
        var p = basePath
        if (r.isNotEmpty()) p += "/" + percentEncode(r, keepSlash = true)
        if (dir || p.isEmpty()) p += "/"
        return p
    }

    private fun url(rel: String, dir: Boolean) = origin + serverPath(rel, dir)

    private fun send(
        method: String,
        url: String,
        what: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: okhttp3.RequestBody? = null,
    ): okhttp3.Response {
        val b = Request.Builder().url(url).method(method, body)
        auth?.let { b.header("Authorization", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        return Http.check(what, http.newCall(b.build()).execute())
    }

    override fun list(dir: String): List<CloudFile> {
        val listed = serverPath(dir, true)
        val text = send(
            "PROPFIND",
            origin + listed,
            "Could not list files",
            listOf("Depth" to "1"),
            PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()),
        ).use { it.body!!.string() }
        return parseListing(text, listed, dir)
    }

    override fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long =
        send("GET", url(path, false), "Download failed").use { Http.stream(it, out, progress) }

    override fun put(path: String, data: ByteArray, progress: (Long) -> Unit) {
        val type = if (isDocument(path)) "application/pdf" else "application/octet-stream"
        send("PUT", url(path, false), "Upload failed", body = countingBody(data, type, progress)).close()
    }

    override fun delete(path: String, id: String) {
        send("DELETE", url(path, false), "Could not delete $path").close()
    }

    override fun rename(from: String, id: String, to: String) {
        send(
            "MOVE",
            url(from, false),
            "Could not rename",
            listOf("Destination" to url(to, false), "Overwrite" to "F"),
        ).close()
    }

    override fun mkdir(path: String) {
        send("MKCOL", url(path, true), "Could not make the folder").close()
    }

    companion object {
        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        /**
         * Reads a PROPFIND multistatus. [listed] is the (encoded) server path
         * that was asked for — its own entry is skipped; [dir] is the account
         * path.
         */
        fun parseListing(body: String, listed: String, dir: String): List<CloudFile> {
            fun norm(href: String): String {
                val p = if (href.contains("://")) {
                    href.substringAfter("://").let { r -> if (r.contains('/')) "/" + r.substringAfter('/') else "/" }
                } else href
                return android.net.Uri.decode(p).trimEnd('/')
            }
            val me = norm(listed)
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(body.reader())
            }
            val out = ArrayList<CloudFile>()
            var tag = ""
            var href = ""
            var collection = false
            var length = ""
            var modified = ""
            var inResourceType = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        when (tag) {
                            "response" -> { href = ""; collection = false; length = ""; modified = "" }
                            "resourcetype" -> inResourceType = true
                            "collection" -> if (inResourceType) collection = true
                        }
                    }
                    XmlPullParser.TEXT -> when (tag) {
                        "href" -> href += parser.text
                        "getcontentlength" -> length += parser.text
                        "getlastmodified" -> modified += parser.text
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "resourcetype" -> inResourceType = false
                            "response" -> {
                                val h = norm(href.trim())
                                val name = h.substringAfterLast('/')
                                if (h != me && h.isNotEmpty() && name.isNotEmpty()) {
                                    val path = joinPath(dir, name)
                                    if (collection) out.add(CloudFile.dir(path))
                                    else out.add(
                                        CloudFile(
                                            path,
                                            length.trim().toLongOrNull() ?: 0,
                                            parseHttpDate(modified.trim()),
                                        ),
                                    )
                                }
                            }
                        }
                        tag = ""
                    }
                }
            }
            return out
        }

        private fun parseHttpDate(s: String): Long = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)!!.time
        }.getOrDefault(0L)
    }
}
