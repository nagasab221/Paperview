package com.paperview.mobile.cloud

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperview.mobile.PaperViewApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** A queued cloud operation. One runs at a time, mirroring the desktop build. */
private sealed interface CloudOp {
    data object Refresh : CloudOp
    data class Upload(val file: File) : CloudOp
    data class Download(val name: String, val size: Long, val openAfter: Boolean) : CloudOp
    data class Delete(val items: List<Pair<String, String>>) : CloudOp
}

/** A document already downloaded to this device. */
data class LocalDoc(val file: File, val name: String, val size: Long, val modified: Long)

/**
 * Live transfer readout. Carries the file's name so the row it belongs to can
 * show its own progress instead of a single anonymous bar at the bottom.
 */
data class Transfer(
    val name: String,
    val label: String,
    val done: Long,
    val total: Long,
    val bytesPerSecond: Double,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class CloudViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs get() = (getApplication<Application>() as PaperViewApp).prefs

    var config by mutableStateOf(B2Config())
        private set

    val files = mutableStateListOf<CloudFile>()
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var transfer by mutableStateOf<Transfer?>(null)
        private set
    var loadedOnce by mutableStateOf(false)
        private set

    /** Names ticked for a batch download or delete. */
    val selected = mutableStateListOf<String>()

    /** File names present in the local cache, so rows can show what's on device. */
    val cachedFiles = mutableStateListOf<String>()

    /**
     * Downloaded documents, read straight off the disk.
     *
     * Deliberately independent of [files]: that list comes from Backblaze, so
     * deriving the on-device group from it meant everything you had already
     * downloaded vanished the moment you lost signal.
     */
    val localFiles = mutableStateListOf<LocalDoc>()

    /** Names queued for download but not started — a tap gives feedback at once. */
    val queuedNames = mutableStateListOf<String>()

    fun isDownloaded(name: String): Boolean =
        cachedFiles.contains(B2Client.localPathFor(getApplication(), name).name)

    fun isQueued(name: String): Boolean = queuedNames.contains(name)

    fun isDownloading(name: String): Boolean = transfer?.name == name

    /** Set when a download finishes and the user asked to open it. */
    var openRequest by mutableStateOf<File?>(null)

    var cacheCount by mutableStateOf(0)
        private set
    var cacheBytes by mutableStateOf(0L)
        private set

    private val queue = Channel<CloudOp>(Channel.UNLIMITED)

    /** Depth of [queue]; a re-list only fires once this drains to zero. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    private fun enqueue(op: CloudOp) {
        pending.incrementAndGet()
        if (queue.trySend(op).isFailure) pending.decrementAndGet()
    }

    init {
        viewModelScope.launch {
            combine(prefs.b2KeyId, prefs.b2AppKey, prefs.b2Bucket) { k, a, b -> B2Config(k, a, b) }
                .collect { config = it }
        }
        viewModelScope.launch { drainQueue() }
        refreshCacheStats()
        refreshCached()
    }

    fun setCredentials(keyId: String, appKey: String, bucket: String) {
        viewModelScope.launch {
            prefs.setB2(keyId.trim(), appKey.trim(), bucket.trim())
            config = B2Config(keyId.trim(), appKey.trim(), bucket.trim())
            files.clear()
            loadedOnce = false
            if (config.ready) refresh()
        }
    }

    fun refresh() = enqueue(CloudOp.Refresh)

    fun upload(file: File) = enqueue(CloudOp.Upload(file))

    fun download(name: String, size: Long, openAfter: Boolean) {
        // Mark it queued before anything touches the network, so tapping a row
        // changes it immediately rather than looking like nothing happened.
        if (!queuedNames.contains(name)) queuedNames.add(name)
        enqueue(CloudOp.Download(name, size, openAfter))
    }

    fun downloadSelected() {
        selected.mapNotNull { name -> files.firstOrNull { it.name == name } }
            .forEach { download(it.name, it.size, openAfter = false) }
        selected.clear()
    }

    fun deleteSelected() {
        val items = selected.mapNotNull { name ->
            files.firstOrNull { it.name == name }?.let { it.name to it.fileId }
        }
        if (items.isNotEmpty()) enqueue(CloudOp.Delete(items))
        selected.clear()
    }

    fun toggleSelected(name: String) {
        if (!selected.remove(name)) selected.add(name)
    }

    fun clearError() { error = null }

    /**
     * Runs queued operations one at a time and re-lists once the queue drains,
     * so the Saved tab is never showing a stale bucket after an upload or
     * delete.
     */
    private suspend fun drainQueue() {
        for (op in queue) {
            // Decrement up front so the "queue drained" check below is accurate
            // even on the paths that bail out early.
            val remaining = pending.decrementAndGet()
            val cfg = config.takeIf { it.ready } ?: run {
                error = "Set your Backblaze key ID, application key and bucket in Settings."
                continue
            }
            busy = true
            error = null
            try {
                val client = B2Client(cfg)
                // Re-authorising before every operation added a couple of
                // network round trips to each download, which is most of the
                // "nothing happens for a while" after tapping a file. B2 tokens
                // are valid for 24 hours; reuse one until it goes stale.
                val session = cachedSession(client, cfg)
                when (op) {
                    is CloudOp.Refresh -> {
                        status = "Listing…"
                        val listed = client.list(session)
                        files.clear()
                        files.addAll(listed.sortedByDescending { it.uploadedMs })
                        loadedOnce = true
                    }
                    is CloudOp.Upload -> {
                        status = "Uploading ${op.file.name}…"
                        client.upload(session, op.file) { sent, total ->
                            transfer = Transfer(op.file.name, "Uploading", sent, total, 0.0)
                        }
                        status = "Uploaded ${op.file.name}"
                    }
                    is CloudOp.Download -> {
                        status = "Downloading ${op.name}…"
                        queuedNames.remove(op.name)
                        // Show 0% straight away: a big file spends real time in
                        // the request before the first byte lands, and a row
                        // that says nothing looks broken.
                        transfer = Transfer(op.name, "Downloading", 0, op.size, 0.0)
                        val dest = B2Client.localPathFor(getApplication(), op.name)
                        client.download(session, op.name, op.size, dest) { done, total, bps ->
                            transfer = Transfer(op.name, "Downloading", done, total, bps)
                        }
                        refreshCacheStats()
                        refreshCached()
                        status = "Downloaded ${op.name}"
                        if (op.openAfter) openRequest = dest
                    }
                    is CloudOp.Delete -> {
                        status = "Deleting…"
                        val n = client.delete(session, op.items)
                        status = if (n == 1) "Deleted 1 file" else "Deleted $n files"
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Cloud operation failed"
                status = ""
                // A stale or rejected token must not stick around and fail
                // every later operation too.
                session = null
                if (op is CloudOp.Download) queuedNames.remove(op.name)
            } finally {
                transfer = null
                busy = false
            }
            // Anything that changed the bucket needs a fresh listing, but only
            // once the queue drains — not after every item in a batch.
            if (op !is CloudOp.Refresh && remaining == 0) enqueue(CloudOp.Refresh)
        }
    }

    // ── Session reuse ────────────────────────────────────────────────────────

    private var session: B2Session? = null
    private var sessionAt: Long = 0L
    private var sessionFor: B2Config? = null

    private suspend fun cachedSession(client: B2Client, cfg: B2Config): B2Session {
        val existing = session
        val fresh = System.currentTimeMillis() - sessionAt < SESSION_TTL_MS
        if (existing != null && fresh && sessionFor == cfg) return existing
        status = "Connecting…"
        return client.connect().also {
            session = it
            sessionAt = System.currentTimeMillis()
            sessionFor = cfg
        }
    }

    fun refreshCacheStats() {
        val (count, bytes) = B2Client.cacheStats(getApplication())
        cacheCount = count
        cacheBytes = bytes
    }

    /** Which listed files already exist on the device. */
    fun refreshCached() {
        val onDisk = B2Client.cacheDir(getApplication()).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        cachedFiles.clear()
        cachedFiles.addAll(onDisk.map { it.name })
        localFiles.clear()
        localFiles.addAll(onDisk.map { LocalDoc(it, it.name, it.length(), it.lastModified()) })
    }

    fun clearCache() {
        B2Client.cacheDir(getApplication()).listFiles()?.forEach { it.delete() }
        refreshCacheStats()
        refreshCached()
    }

    /** Removes just this file's local copy, leaving it in the bucket. */
    fun removeLocal(file: File) {
        file.delete()
        refreshCacheStats()
        refreshCached()
    }

    /** Removes just this file's local copy, leaving it in the bucket. */
    fun removeDownload(name: String) {
        B2Client.localPathFor(getApplication(), name).delete()
        refreshCacheStats()
        refreshCached()
    }

    /** Eagerly list once, as soon as credentials are available. */
    fun listIfReady() {
        viewModelScope.launch {
            val cfg = config
            if (cfg.ready && !loadedOnce && !busy) refresh()
        }
    }

    companion object {
        /** Backblaze's free tier, for the usage bar. */
        const val FREE_TIER_BYTES = 10L * 1024 * 1024 * 1024

        /** B2 tokens last 24h; refresh well inside that. */
        private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000
    }
}
