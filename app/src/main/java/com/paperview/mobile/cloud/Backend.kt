package com.paperview.mobile.cloud

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** What every kind of account can do. Paths are relative to its top folder. */
interface Backend : AutoCloseable {
    fun list(dir: String): List<CloudFile>

    /** Streams a file into [out]; [progress] gets the bytes so far. */
    fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long

    fun put(path: String, data: ByteArray, progress: (Long) -> Unit)
    fun delete(path: String, id: String)
    fun rename(from: String, id: String, to: String)
    fun mkdir(path: String)

    /** Whether a folder must exist before files go in (S3 and B2 don't need one). */
    val needsFolders: Boolean get() = true

    /** SSH: the server's key fingerprint seen on this connection. */
    val hostKey: String? get() = null

    override fun close() {}
}

object Backends {
    /** Connects (SSH and B2 sign in here; the others on their first request). */
    fun connect(context: Context, acct: Account, secret: String): Backend = when (acct.kind) {
        Kind.Folder -> FolderBackend(context, acct)
        Kind.WebDav -> WebDavBackend(acct, secret)
        Kind.S3 -> S3Backend(acct, secret)
        Kind.B2 -> B2Backend(acct, secret)
        Kind.Ssh -> SftpBackend(context, acct, secret)
    }

    /** What the worker is doing first, in words. */
    fun stage(acct: Account): String {
        val host = acct.address.trim().substringAfter("://").substringBefore('/')
        return when (acct.kind) {
            Kind.Folder -> "Opening ${folderLabel(acct.address)}…"
            Kind.Ssh -> "Connecting to $host over SSH…"
            Kind.B2 -> "Signing in to Backblaze…"
            Kind.WebDav, Kind.S3 -> "Contacting $host…"
        }
    }
}

// ── HTTP ─────────────────────────────────────────────────────────────────────

object Http {
    /** Dead or unreachable servers fail in seconds, not minutes. */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Trusts any certificate — only for an account that asked to. */
    private val insecure: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun client(insecureTls: Boolean): OkHttpClient = if (insecureTls) insecure else base

    /** Passes a success through; otherwise the server's own explanation. */
    fun check(what: String, resp: Response): Response {
        if (resp.isSuccessful) return resp
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        resp.close()
        val detail = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            ?: Regex("<Message>([^<]*)</Message>").find(body)?.groupValues?.get(1)
            ?: Regex("<s:message>([^<]*)</s:message>").find(body)?.groupValues?.get(1)
        val hint = when (resp.code) {
            401, 403 -> " — check the user name / key and password"
            404 -> " — check the address, bucket and folder"
            else -> ""
        }
        throw CloudException(
            if (!detail.isNullOrBlank()) "$what: ${resp.code} ${resp.message}: $detail"
            else "$what: ${resp.code} ${resp.message}$hint",
        )
    }

    /** Copies a response body into [out], reporting bytes so far. */
    fun stream(resp: Response, out: OutputStream, progress: (Long) -> Unit): Long {
        val input = resp.body?.byteStream() ?: throw CloudException("Empty response")
        return copy(input, out, progress)
    }

    fun basic(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
}

fun copy(input: InputStream, out: OutputStream, progress: (Long) -> Unit): Long {
    val buf = ByteArray(64 * 1024)
    var done = 0L
    input.use {
        while (true) {
            val n = it.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            done += n
            progress(done)
        }
    }
    return done
}

/** A request body that reports how much of it has gone. */
fun countingBody(
    data: ByteArray,
    type: String,
    progress: (Long) -> Unit,
): okhttp3.RequestBody = object : okhttp3.RequestBody() {
    override fun contentType() = okhttp3.MediaType.run { type.toMediaType() }
    override fun contentLength() = data.size.toLong()
    override fun writeTo(sink: okio.BufferedSink) {
        var sent = 0
        while (sent < data.size) {
            val n = minOf(64 * 1024, data.size - sent)
            sink.write(data, sent, n)
            sent += n
            progress(sent.toLong())
        }
    }
}

/**
 * Turns a failure into a sentence a reader can act on: what went wrong, and
 * for the usual network trouble, what to check.
 */
fun explain(t: Throwable): String {
    val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    return when (t) {
        is CloudException -> msg
        is UnknownHostException ->
            "The address couldn't be found — check the host name. ($msg)"
        is ConnectException ->
            "Nothing answered at that address and port — is the server running, and is the port right? ($msg)"
        is SocketTimeoutException ->
            "No answer in time — is the server on, and reachable from this network? ($msg)"
        is NoRouteToHostException -> "That network can't be reached from here. ($msg)"
        is SSLException ->
            "The server's certificate isn't trusted ($msg). A NAS with a self-signed " +
                "certificate needs “Trust a self-signed certificate” in the account."
        else -> {
            val cause = t.cause
            if (cause != null && cause !== t && cause !is CloudException) explain(cause) else msg
        }
    }
}
