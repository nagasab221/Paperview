package com.paperview.mobile.cloud

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

/**
 * This phone's SSH key: made once, kept in the app's private storage. Its
 * public half goes into the server's ~/.ssh/authorized_keys, so no password
 * needs to be stored at all.
 */
object SshKey {
    private fun dir(context: Context) = File(context.filesDir, "ssh").apply { mkdirs() }
    fun privateFile(context: Context) = File(dir(context), "id_ecdsa")
    private fun publicFile(context: Context) = File(dir(context), "id_ecdsa.pub")

    /** The public key line, making the key pair first if there isn't one. */
    @Synchronized
    fun publicKey(context: Context): String {
        val pub = publicFile(context)
        if (!privateFile(context).exists() || !pub.exists()) {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
            privateFile(context).outputStream().use { kp.writePrivateKey(it) }
            pub.outputStream().use { kp.writePublicKey(it, "paperview@${android.os.Build.MODEL.replace(' ', '-')}") }
            kp.dispose()
        }
        return pub.readText().trim()
    }
}

/**
 * SSH accounts: SFTP through JSch. Signs in with this phone's key (and the
 * password, if one was given); the server's own key is remembered the first
 * time and a different one later is refused.
 */
class SftpBackend(context: Context, acct: Account, password: String) : Backend {

    private val session: com.jcraft.jsch.Session
    private val sftp: ChannelSftp
    /** The account's folder: absolute, or under the home folder. */
    private val base: String
    override val hostKey: String?

    init {
        val host = acct.address.trim()
        val user = acct.user.trim()
        if (host.isEmpty()) throw CloudException("No SSH host given")
        if (user.isEmpty()) throw CloudException("Enter the user name to sign in as")
        val port = acct.port.trim().ifEmpty { "22" }.toIntOrNull()
            ?: throw CloudException("The SSH port “${acct.port}” isn't a number")
        val jsch = JSch()
        SshKey.publicKey(context)
        jsch.addIdentity(SshKey.privateFile(context).absolutePath)
        session = jsch.getSession(user, host, port)
        if (password.isNotEmpty()) session.setPassword(password)
        // Host keys are checked below against the one remembered for the
        // account (trust on first use), not against a known_hosts file.
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.serverAliveInterval = 15_000
        try {
            session.connect(15_000)
        } catch (e: JSchException) {
            throw CloudException(sshMessage(e, host, port))
        }
        hostKey = session.hostKey?.getFingerPrint(jsch)
        val known = acct.hostKey
        if (known != null && hostKey != null && known != hostKey) {
            session.disconnect()
            throw CloudException(
                "The server's SSH key changed (was $known, now $hostKey), so PaperView won't " +
                    "connect. If you reinstalled the server, remove and re-add this account.",
            )
        }
        sftp = (session.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }
        val folder = sshFolder(acct.folder).removePrefix("~/")
        base = if (folder.startsWith("/")) folder else "${sftp.home.trimEnd('/')}/$folder"
        ensureBase()
    }

    private fun sshMessage(e: JSchException, host: String, port: Int): String {
        val m = e.message.orEmpty()
        val cause = e.cause
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                "The server didn't accept the sign-in. Add this phone's key (shown in the " +
                    "account form) to ~/.ssh/authorized_keys on the server, or enter the password."
            cause != null -> explain(cause)
            m.contains("UnknownHost", true) -> "The address $host couldn't be found — check the host name."
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "No answer from $host:$port in time — is it on, and reachable from this network?"
            m.contains("refused", true) ->
                "Nothing answered at $host port $port — is SSH running there, and is the port right?"
            else -> "SSH: $m"
        }
    }

    private fun full(rel: String): String = rel.trim('/').let { if (it.isEmpty()) base else "$base/$it" }

    private fun stat(path: String): SftpATTRS? = try {
        sftp.stat(path)
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw sftpError("Could not look at $path", e)
    }

    /** The account folder is made on first use (`mkdir -p`). */
    private fun ensureBase() {
        stat(base)?.let {
            if (it.isDir) return
            throw CloudException("$base on the server is a file, not a folder")
        }
        var soFar = ""
        for (part in base.split('/').filter { it.isNotEmpty() }) {
            soFar += "/$part"
            if (stat(soFar) == null) {
                try { sftp.mkdir(soFar) } catch (e: SftpException) { throw sftpError("Could not make $soFar", e) }
            }
        }
    }

    private fun sftpError(what: String, e: SftpException): CloudException = CloudException(
        when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> "$what: no such file or folder"
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> "$what: permission denied"
            else -> "$what: ${e.message}"
        },
    )

    override fun list(dir: String): List<CloudFile> {
        val entries = try {
            @Suppress("UNCHECKED_CAST")
            sftp.ls(full(dir)) as java.util.Vector<ChannelSftp.LsEntry>
        } catch (e: SftpException) {
            throw sftpError("Could not open the folder", e)
        }
        val out = ArrayList<CloudFile>()
        for (e in entries) {
            val name = e.filename
            if (name == "." || name == ".." || name.startsWith(".")) continue
            val path = joinPath(dir, name)
            var attrs = e.attrs
            if (attrs.isLink) attrs = stat(full(path)) ?: continue
            when {
                attrs.isDir -> out.add(CloudFile.dir(path))
                attrs.isReg -> out.add(CloudFile(path, attrs.size, attrs.mTime * 1000L))
            }
        }
        return out
    }

    private fun monitor(progress: (Long) -> Unit) = object : SftpProgressMonitor {
        var done = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(count: Long): Boolean { done += count; progress(done); return true }
        override fun end() {}
    }

    override fun get(path: String, size: Long, out: OutputStream, progress: (Long) -> Unit): Long {
        val m = monitor(progress)
        try {
            sftp.get(full(path), out, m)
        } catch (e: SftpException) {
            throw sftpError("Download failed", e)
        }
        return m.done
    }

    override fun put(path: String, data: ByteArray, progress: (Long) -> Unit) {
        try {
            sftp.put(ByteArrayInputStream(data), full(path), monitor(progress), ChannelSftp.OVERWRITE)
        } catch (e: SftpException) {
            throw sftpError("Upload failed", e)
        }
    }

    override fun delete(path: String, id: String) {
        try { sftp.rm(full(path)) } catch (e: SftpException) { throw sftpError("Could not delete $path", e) }
    }

    override fun rename(from: String, id: String, to: String) {
        try { sftp.rename(full(from), full(to)) } catch (e: SftpException) { throw sftpError("Could not rename", e) }
    }

    override fun mkdir(path: String) {
        try { sftp.mkdir(full(path)) } catch (e: SftpException) { throw sftpError("Could not make the folder", e) }
    }

    /** Still connected (a kept-open session can drop while idle). */
    val alive: Boolean get() = session.isConnected && sftp.isConnected

    override fun close() {
        runCatching { sftp.disconnect() }
        runCatching { session.disconnect() }
    }
}
