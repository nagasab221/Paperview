package com.paperview.mobile.cloud

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperview.mobile.PaperViewApp
import com.paperview.mobile.pdf.DocSource
import com.paperview.mobile.store.Recents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A queued cloud operation. One runs at a time, mirroring the desktop build. */
private sealed interface CloudOp {
    data class ListDir(val dir: String) : CloudOp
    data class Upload(val items: List<UploadItem>, val folders: List<String>, val label: String) : CloudOp
    data class Download(val path: String, val size: Long, val openAfter: Boolean) : CloudOp
    data class Delete(val items: List<Pair<String, String>>) : CloudOp
    data class Rename(val from: String, val id: String, val to: String) : CloudOp
    data class MakeFolder(val path: String) : CloudOp
}

/** One file to upload: where it goes, and how to read it. */
class UploadItem(val remote: String, val size: Long, val read: () -> ByteArray)

/** A document already downloaded to this device. */
data class LocalDoc(val file: File, val name: String, val size: Long, val modified: Long)

/** Live transfer readout, for the row it belongs to (or the batch line). */
data class Transfer(
    val path: String,
    val label: String,
    val done: Long,
    val total: Long,
    val bytesPerSecond: Double,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** What an account last said: connected (and what's there) or why not. */
data class Health(val ok: Boolean, val text: String, val at: Long)

/** A connection check running in the background. */
data class TestState(val account: String, val stage: String, val startedAt: Long, val fromForm: Boolean)

/** Something to open once it's ready. */
data class OpenRequest(val source: DocSource, val name: String)

/** The account form in Settings. */
class AccountEditor(val editing: String?, draft: Account) {
    var draft by mutableStateOf(draft)
    /** A new password / secret; empty keeps the saved one. */
    var secret by mutableStateOf("")
    var showSecret by mutableStateOf(false)
    /** Why the last check failed (nothing was saved). */
    var failed by mutableStateOf<String?>(null)
}

class CloudViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val prefs get() = (getApplication<Application>() as PaperViewApp).prefs

    val accounts = mutableStateListOf<Account>()
    var currentId by mutableStateOf("")
        private set
    /** The folder being shown ("" is the top). */
    var dir by mutableStateOf("")
        private set

    /** The user's on/off switch for the Cloud tab. */
    var enabled by mutableStateOf(true)
        private set

    val current: Account? get() = accounts.firstOrNull { it.id == currentId }
    val active: Boolean get() = enabled && current?.ready == true

    /** The current folder's listing: subfolders and documents. */
    val files = mutableStateListOf<CloudFile>()
    /** [files] is the listing of the current account and folder. */
    var listed by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var opStartedAt by mutableStateOf(0L)
        private set
    var transfer by mutableStateOf<Transfer?>(null)
        private set

    /** Downloaded documents of the folder being shown, read off the disk. */
    val localFiles = mutableStateListOf<LocalDoc>()
    /** Paths ticked for a batch download or delete. */
    val selected = mutableStateListOf<String>()
    /** Paths queued for download but not started. */
    val queuedPaths = mutableStateListOf<String>()

    val health = mutableStateMapOf<String, Health>()
    var test by mutableStateOf<TestState?>(null)
        private set
    var editor by mutableStateOf<AccountEditor?>(null)

    var openRequest by mutableStateOf<OpenRequest?>(null)
    /** A one-off message for a toast. */
    var message by mutableStateOf<String?>(null)

    var cacheCount by mutableStateOf(0)
        private set
    var cacheBytes by mutableStateOf(0L)
        private set

    private val queue = Channel<Pair<Account, CloudOp>>(Channel.UNLIMITED)
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            load()
            refreshLocal()
            refreshCacheStats()
            if (active) list()
            drainQueue()
        }
    }

    // ── Accounts ─────────────────────────────────────────────────────────────

    private suspend fun load() {
        val raw = prefs.cloudAccountsRaw.first()
        val loaded = Account.decodeList(raw)
        accounts.clear()
        accounts.addAll(loaded)
        if (raw.isBlank()) migrateLegacyB2()
        currentId = prefs.cloudCurrent.first()
        dir = prefs.cloudDir.first()
        enabled = prefs.b2Enabled.first()
        if (current == null) {
            currentId = accounts.firstOrNull()?.id.orEmpty()
            dir = ""
        }
    }

    /**
     * The single Backblaze bucket of earlier versions becomes an account; its
     * downloads (and Recents pointing at them) move into its folder.
     */
    private suspend fun migrateLegacyB2() {
        val keyId = prefs.b2KeyId.first()
        val appKey = prefs.b2AppKey.first()
        val bucket = prefs.b2Bucket.first()
        if (keyId.isBlank() || bucket.isBlank()) return
        val id = "b2-legacy"
        var acct = Account(id = id, name = "Backblaze B2", kind = Kind.B2, user = keyId.trim(), bucket = bucket.trim())
        withContext(Dispatchers.IO) {
            if (!Secrets.store(context, id, appKey.trim())) acct = acct.copy(plainSecret = appKey.trim())
            val root = CloudCache.root(context)
            val target = CloudCache.accountDir(context, id).apply { mkdirs() }
            val moved = HashMap<String, String>()
            root.listFiles()?.filter { it.isFile }?.forEach { f ->
                val to = File(target, f.name)
                if (f.renameTo(to)) moved[f.absolutePath] = to.absolutePath
            }
            if (moved.isNotEmpty()) Recents.follow(prefs, moved)
        }
        accounts.add(acct)
        currentId = id
        dir = ""
        persist()
        prefs.setB2("", "", "")
    }

    private fun persist() {
        val list = accounts.toList()
        val cur = currentId
        val d = dir
        viewModelScope.launch {
            prefs.setCloudAccountsRaw(Account.encodeList(list))
            prefs.setCloudCurrent(cur)
            prefs.setCloudDir(d)
        }
    }

    fun setCloudEnabled(on: Boolean) {
        enabled = on
        viewModelScope.launch { prefs.setB2Enabled(on) }
    }

    /** Shows another account (or the same one from its top folder). */
    fun switchAccount(id: String) {
        currentId = id
        enterDir("")
    }

    /** Shows a folder of the current account. */
    fun enterDir(path: String) {
        dir = path
        files.clear()
        listed = false
        error = null
        selected.clear()
        persist()
        refreshLocal()
        list()
    }

    fun removeAccount(id: String) {
        val acct = accounts.firstOrNull { it.id == id } ?: return
        accounts.remove(acct)
        Secrets.forget(context, id)
        CloudCache.accountDir(context, id).deleteRecursively()
        health.remove(id)
        if (cached?.first?.id == id) dropCached()
        if (currentId == id) switchAccount(accounts.firstOrNull()?.id.orEmpty()) else persist()
        refreshCacheStats()
        message = "Removed ${acct.name}"
    }

    fun startEditor(id: String?) {
        editor = if (id == null) {
            AccountEditor(null, Account(kind = Kind.Ssh))
        } else {
            accounts.firstOrNull { it.id == id }?.let { AccountEditor(id, it) }
        }
    }

    fun hasSavedSecret(ed: AccountEditor): Boolean {
        val id = ed.editing ?: return false
        val acct = accounts.firstOrNull { it.id == id } ?: return false
        return acct.kind == ed.draft.kind && (acct.plainSecret != null || Secrets.has(context, id))
    }

    /** The account as the form describes it — named, with an id. */
    private fun prepare(ed: AccountEditor): Account {
        var a = ed.draft.trimmed()
        if (a.name.isEmpty()) {
            a = a.copy(
                name = when (a.kind) {
                    Kind.Ssh, Kind.WebDav -> a.address.substringAfter("://").substringBefore('/')
                        .substringBefore(':').ifEmpty { a.kind.label }
                    Kind.Folder -> folderLabel(a.address)
                    else -> a.kind.label
                },
            )
        }
        return a.copy(id = ed.editing ?: Account.newId())
    }

    // ── Connection checks ────────────────────────────────────────────────────

    /** "Connect and save": check first, save only once it answers. */
    fun connectAndSave() {
        val ed = editor ?: return
        ed.failed = null
        startTest(prepare(ed), ed.secret.trim().ifEmpty { null }, fromForm = true)
    }

    /** Saves the form's account without checking (a NAS that's off right now). */
    fun saveAnyway() {
        val ed = editor ?: return
        commit(prepare(ed), ed.secret.trim().ifEmpty { null }, null, null)
        editor = null
    }

    fun testAccount(id: String) {
        accounts.firstOrNull { it.id == id }?.let { startTest(it, null, fromForm = false) }
    }

    fun stopTest() {
        testJob?.cancel()
        test = null
    }

    private fun startTest(acct: Account, secret: String?, fromForm: Boolean) {
        testJob?.cancel()
        val started = System.currentTimeMillis()
        test = TestState(acct.id, Backends.stage(acct), started, fromForm)
        testJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val backend = Backends.connect(context, acct, secret ?: Secrets.get(context, acct))
                    if (acct.kind == Kind.Ssh || acct.kind == Kind.B2) {
                        test = test?.copy(stage = "Signed in — reading the folder…")
                    }
                    val listing = try {
                        sortListing(backend.list("").filter(::keep))
                    } catch (t: Throwable) {
                        backend.close(); throw t
                    }
                    backend to listing
                }
            }
            if (test?.startedAt != started) {
                result.getOrNull()?.first?.close()
                return@launch
            }
            test = null
            result.fold(
                onSuccess = { (backend, listing) ->
                    val summary = listingSummary(listing)
                    health[acct.id] = Health(true, "Connected · $summary", System.currentTimeMillis())
                    message = "Connected to ${acct.name} — $summary"
                    val withKey = backend.hostKey?.let { acct.copy(hostKey = acct.hostKey ?: it) } ?: acct
                    if (fromForm) {
                        editor = null
                        commit(withKey, secret, listing, backend)
                    } else {
                        if (withKey != acct) replaceAccount(withKey)
                        if (acct.id == currentId) keepCached(withKey, backend) else backend.close()
                    }
                },
                onFailure = { t ->
                    val why = explain(t)
                    health[acct.id] = Health(false, why, System.currentTimeMillis())
                    if (fromForm) editor?.failed = why else message = "Couldn't connect to ${acct.name}"
                },
            )
        }
    }

    private fun replaceAccount(acct: Account) {
        val i = accounts.indexOfFirst { it.id == acct.id }
        if (i >= 0) accounts[i] = acct else accounts.add(acct)
        persist()
    }

    /**
     * Saves an account (and its new secret) and shows it on the Cloud tab;
     * [listing] is its top folder when a check just read it.
     */
    private fun commit(acct: Account, secret: String?, listing: List<CloudFile>?, backend: Backend?) {
        var a = acct
        if (secret != null) {
            a = if (Secrets.store(context, a.id, secret)) a.copy(plainSecret = null) else a.copy(plainSecret = secret)
        }
        replaceAccount(a)
        enabled = true
        viewModelScope.launch { prefs.setB2Enabled(true) }
        currentId = a.id
        dir = ""
        selected.clear()
        error = null
        persist()
        refreshLocal()
        if (listing != null) {
            files.clear()
            files.addAll(listing)
            listed = true
            backend?.let { keepCached(a, it) }
        } else {
            files.clear()
            listed = false
            list()
        }
    }

    // ── Kept-open connection ─────────────────────────────────────────────────

    /** The last connection, kept for a few minutes (an SSH sign-in is slow). */
    private var cached: Triple<Account, Backend, Long>? = null

    private fun sameConfig(a: Account, b: Account) =
        a.copy(hostKey = null, plainSecret = null, name = "") == b.copy(hostKey = null, plainSecret = null, name = "")

    private fun keepCached(acct: Account, backend: Backend) {
        cached?.let { if (it.second !== backend) it.second.close() }
        cached = Triple(acct, backend, System.currentTimeMillis())
    }

    private fun dropCached() {
        cached?.second?.close()
        cached = null
    }

    /** On the IO dispatcher: the cached connection, or a new one. */
    private fun backendFor(acct: Account): Backend {
        cached?.let { (a, b, at) ->
            val fresh = System.currentTimeMillis() - at < 5 * 60_000
            val alive = (b as? SftpBackend)?.alive ?: true
            if (sameConfig(a, acct) && fresh && alive) {
                cached = Triple(a, b, System.currentTimeMillis())
                return b
            }
        }
        dropCached()
        status = Backends.stage(acct)
        val b = Backends.connect(context, acct, Secrets.get(context, acct))
        // Remember an SSH server's key the first time.
        val withKey = b.hostKey?.takeIf { acct.hostKey == null }?.let { acct.copy(hostKey = it) }
        if (withKey != null) viewModelScope.launch { replaceAccount(withKey) }
        cached = Triple(acct, b, System.currentTimeMillis())
        return b
    }

    // ── Operations ───────────────────────────────────────────────────────────

    private fun enqueue(op: CloudOp) {
        val acct = current ?: return
        pending.incrementAndGet()
        if (queue.trySend(acct to op).isFailure) pending.decrementAndGet()
    }

    fun list() = enqueue(CloudOp.ListDir(dir))

    fun refresh() {
        error = null
        list()
    }

    fun download(path: String, size: Long, openAfter: Boolean) {
        if (!queuedPaths.contains(path)) queuedPaths.add(path)
        enqueue(CloudOp.Download(path, size, openAfter))
    }

    fun downloadSelected() {
        files.filter { !it.isDir && selected.contains(it.path) }
            .filterNot { localFile(it.path).exists() }
            .forEach { download(it.path, it.size, openAfter = false) }
        selected.clear()
    }

    fun delete(items: List<Pair<String, String>>) {
        // Downloaded copies go too.
        items.forEach { (path, _) -> localFile(path).delete() }
        refreshLocal()
        selected.clear()
        enqueue(CloudOp.Delete(items))
    }

    fun deleteSelected() =
        delete(files.filter { !it.isDir && selected.contains(it.path) }.map { it.path to it.id })

    fun rename(file: CloudFile, newName: String) =
        enqueue(CloudOp.Rename(file.path, file.id, joinPath(parentPath(file.path), newName)))

    fun makeFolder(name: String) = enqueue(CloudOp.MakeFolder(joinPath(dir, name)))

    fun toggleSelected(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    fun clearError() { error = null }

    /** Uploads a local file into the folder being shown. */
    fun uploadLocal(file: File, name: String = file.name) {
        val n = if (isDocument(name)) name else "$name.pdf"
        enqueue(CloudOp.Upload(listOf(UploadItem(joinPath(dir, n), file.length()) { file.readBytes() }), emptyList(), n))
        message = "Uploading $n to ${placeName()}…"
    }

    /** Uploads documents picked with the system picker into the folder shown. */
    fun uploadUris(uris: List<Uri>) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val doc = DocumentFile.fromSingleUri(context, uri) ?: return@mapNotNull null
                    val name = doc.name ?: return@mapNotNull null
                    if (!isDocument(name)) return@mapNotNull null
                    UploadItem(joinPath(dir, name), doc.length()) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw CloudException("couldn't read it")
                    }
                }
            }
            if (items.isEmpty()) {
                message = "Only PDFs can be uploaded"
                return@launch
            }
            val label = if (items.size == 1) lastPart(items[0].remote) else "${items.size} documents"
            enqueue(CloudOp.Upload(items, emptyList(), label))
            message = "Uploading $label to ${placeName()}…"
        }
    }

    /**
     * Uploads a picked folder — every PDF in it and its subfolders — into the
     * folder being shown, keeping its subfolders.
     */
    fun uploadTree(tree: Uri) {
        val acct = current ?: return
        if (acct.kind == Kind.Folder && sameTree(acct.address, tree.toString())) {
            message = "That folder is part of ${acct.name} already"
            return
        }
        viewModelScope.launch {
            status = "Looking for documents…"
            val (name, items, folders) = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(context, tree)
                val name = root?.name ?: "Folder"
                val target = joinPath(dir, name)
                val items = ArrayList<UploadItem>()
                val folders = sortedSetOf(target)
                fun walk(d: DocumentFile, rel: String) {
                    d.listFiles().sortedBy { it.name }.forEach { f ->
                        val n = f.name ?: return@forEach
                        if (n.startsWith(".")) return@forEach
                        val path = joinPath(rel, n)
                        if (f.isDirectory) walk(f, path)
                        else if (f.isFile && isDocument(n)) {
                            items.add(
                                UploadItem(joinPath(target, path), f.length()) {
                                    context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                                        ?: throw CloudException("couldn't read it")
                                },
                            )
                            var p = parentPath(path)
                            while (p.isNotEmpty()) { folders.add(joinPath(target, p)); p = parentPath(p) }
                        }
                    }
                }
                root?.let { walk(it, "") }
                Triple(name, items, folders.toList())
            }
            status = ""
            if (items.isEmpty()) {
                message = "There are no PDFs in $name or its subfolders"
                return@launch
            }
            enqueue(CloudOp.Upload(items, folders, "the folder $name"))
            message = "Uploading the folder $name to ${placeName()}…"
        }
    }

    private fun sameTree(a: String, b: String) =
        Uri.decode(a).substringAfter("/tree/") == Uri.decode(b).substringAfter("/tree/")

    /** "Laptop › Math", for messages. */
    fun placeName(): String {
        val name = current?.name ?: "the cloud"
        return if (dir.isEmpty()) name else "$name › ${dir.replace("/", " › ")}"
    }

    fun localFile(path: String): File = CloudCache.pathFor(context, currentId, path)

    /** Opens a Folder account's document in place. */
    fun openInPlace(file: CloudFile) {
        val acct = current ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { FolderBackend(context, acct).find(file.path)?.uri }.getOrNull()
            }
            if (uri != null) openRequest = OpenRequest(DocSource.Content(uri), file.name)
            else message = "Couldn't open ${file.name}"
        }
    }

    /**
     * Runs queued operations one at a time and re-lists once the queue
     * drains, so the Cloud tab never shows a stale folder after a change.
     */
    private suspend fun drainQueue() {
        for ((acct, op) in queue) {
            val remaining = pending.decrementAndGet()
            busy = true
            error = null
            opStartedAt = System.currentTimeMillis()
            var changed = false
            try {
                withContext(Dispatchers.IO) {
                    val b = backendFor(acct)
                    when (op) {
                        is CloudOp.ListDir -> {
                            if (acct.kind == Kind.Ssh || acct.kind == Kind.B2) status = "Reading the folder…"
                            val listing = sortListing(b.list(op.dir).filter(::keep))
                            withContext(Dispatchers.Main) {
                                if (op.dir.isEmpty()) {
                                    health[acct.id] = Health(true, "Connected · ${listingSummary(listing)}", System.currentTimeMillis())
                                }
                                if (acct.id == currentId && op.dir == dir) {
                                    files.clear()
                                    files.addAll(listing)
                                    listed = true
                                }
                            }
                        }
                        is CloudOp.Upload -> {
                            changed = true
                            runUpload(b, op)
                        }
                        is CloudOp.Download -> runDownload(b, acct, op)
                        is CloudOp.Delete -> {
                            changed = true
                            status = "Deleting…"
                            op.items.forEach { (path, id) -> b.delete(path, id) }
                            val n = op.items.size
                            message = "Deleted $n file${if (n == 1) "" else "s"} from ${acct.name}"
                        }
                        is CloudOp.Rename -> {
                            changed = true
                            status = "Renaming to ${lastPart(op.to)}…"
                            b.rename(op.from, op.id, op.to)
                            // A downloaded copy follows the new name.
                            val from = CloudCache.pathFor(context, acct.id, op.from)
                            val to = CloudCache.pathFor(context, acct.id, op.to)
                            if (from.exists()) {
                                to.parentFile?.mkdirs()
                                if (from.renameTo(to)) Recents.follow(prefs, mapOf(from.absolutePath to to.absolutePath))
                            }
                            message = "Renamed to ${lastPart(op.to)}"
                        }
                        is CloudOp.MakeFolder -> {
                            changed = true
                            b.mkdir(op.path)
                            message = "Made the folder ${lastPart(op.path)}"
                        }
                    }
                }
                status = ""
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                dropCached()
                val why = explain(t)
                error = why
                status = ""
                if (op is CloudOp.ListDir && op.dir.isEmpty() && acct.id == currentId && !listed) {
                    health[acct.id] = Health(false, why, System.currentTimeMillis())
                }
                if (op is CloudOp.Download) queuedPaths.remove(op.path)
            } finally {
                transfer = null
                busy = false
                opStartedAt = 0
            }
            refreshLocal()
            refreshCacheStats()
            if (changed && remaining == 0 && acct.id == currentId) list()
        }
    }

    /** On IO. */
    private fun runDownload(b: Backend, acct: Account, op: CloudOp.Download) {
        status = "Downloading ${lastPart(op.path)}…"
        queuedPaths.remove(op.path)
        transfer = Transfer(op.path, "Downloading", 0, op.size, 0.0)
        val dest = CloudCache.pathFor(context, acct.id, op.path)
        dest.parentFile?.mkdirs()
        // A hidden part-file first, so a broken transfer never looks downloaded.
        val part = File(dest.parentFile, ".${dest.name}.part")
        val meter = Meter(op.size) { done, total, bps ->
            transfer = Transfer(op.path, "Downloading", done, total, bps)
        }
        try {
            part.outputStream().buffered().use { out -> b.get(op.path, op.size, out) { meter.tick(it) } }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw CloudException("Could not save the download")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        if (op.openAfter) openRequest = OpenRequest(DocSource.Local(dest), dest.name)
    }

    /** On IO: folders first (where needed), then one file at a time. */
    private fun runUpload(b: Backend, op: CloudOp.Upload) {
        if (b.needsFolders) op.folders.forEach { runCatching { b.mkdir(it) } }
        val total = op.items.sumOf { it.size }
        var before = 0L
        val failed = ArrayList<String>()
        val n = op.items.size
        op.items.forEachIndexed { i, item ->
            val rel = item.remote
            status = if (n == 1) "Uploading ${lastPart(rel)}…" else "Uploading ${i + 1} of $n: ${lastPart(rel)}"
            val meter = Meter(total) { done, t, bps ->
                transfer = Transfer(rel, "Uploading", done, t, bps)
            }
            try {
                val data = item.read()
                b.put(rel, data) { meter.tick(before + it) }
            } catch (t: Throwable) {
                if (n == 1) throw t
                failed.add("${lastPart(rel)} (${explain(t)})")
            }
            before += item.size
        }
        message = when {
            failed.isEmpty() && n == 1 -> "Uploaded ${op.label}"
            failed.isEmpty() -> "Uploaded ${docs(n)} from ${op.label}"
            else -> "${docs(failed.size)} didn't upload: ${failed.take(3).joinToString("; ")}"
        }
        if (failed.isNotEmpty()) error = "Uploaded ${docs(n - failed.size)} of $n. Not uploaded: ${failed.joinToString("; ")}"
    }

    private fun docs(n: Int) = "$n document${if (n == 1) "" else "s"}"

    /** Throttled progress: at most every 150 ms, with the speed since the last. */
    private class Meter(val total: Long, val emit: (Long, Long, Double) -> Unit) {
        private var last = System.nanoTime()
        private var lastBytes = 0L
        fun tick(done: Long) {
            val now = System.nanoTime()
            val secs = (now - last) / 1e9
            if (secs < 0.15) return
            emit(done, maxOf(total, done), (done - lastBytes) / secs)
            last = now
            lastBytes = done
        }
    }

    // ── Local copies ─────────────────────────────────────────────────────────

    /** Downloaded documents of the folder shown, newest first. */
    fun refreshLocal() {
        val folder = CloudCache.pathFor(context, currentId, dir)
        val docs = folder.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LocalDoc(it, it.name, it.length(), it.lastModified()) }
            .orEmpty()
        localFiles.clear()
        localFiles.addAll(docs)
    }

    fun refreshCacheStats() {
        val (count, bytes) = CloudCache.stats(context)
        cacheCount = count
        cacheBytes = bytes
    }

    fun clearCache() {
        CloudCache.clear(context)
        refreshCacheStats()
        refreshLocal()
    }

    fun removeLocal(file: File) {
        file.delete()
        refreshCacheStats()
        refreshLocal()
    }

    override fun onCleared() {
        dropCached()
        super.onCleared()
    }

    companion object {
        /** Backblaze's free tier, beside a B2 account's usage. */
        const val FREE_TIER_BYTES = 10L * 1024 * 1024 * 1024
    }
}
