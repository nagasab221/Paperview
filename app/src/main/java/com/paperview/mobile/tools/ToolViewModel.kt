package com.paperview.mobile.tools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperview.mobile.PaperViewApp
import com.paperview.mobile.files.FileAccess
import com.paperview.mobile.pdf.DocSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a finished tool produced. */
data class ToolResult(val files: List<File>, val summary: String)

/** One input document, already copied somewhere we can read repeatedly. */
data class ToolInputFile(val file: File, val displayName: String)

class ToolViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs get() = (getApplication<Application>() as PaperViewApp).prefs

    var tool by mutableStateOf<Tool?>(null)
        private set

    val inputs = mutableStateListOf<ToolInputFile>()

    var pageCount by mutableIntStateOf(0)
        private set

    /** Password for an encrypted input, once the user has supplied it. */
    var password by mutableStateOf<String?>(null)
        private set
    var needsPassword by mutableStateOf(false)
        private set
    var wrongPassword by mutableStateOf(false)
        private set

    var preparing by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var statusText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<ToolResult?>(null)
        private set

    /** Opens a tool with the files the user picked. */
    fun begin(selected: Tool, uris: List<Uri>) {
        tool = selected
        inputs.clear()
        pageCount = 0
        password = null
        needsPassword = false
        wrongPassword = false
        error = null
        result = null
        preparing = true
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val copied = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val name = FileAccess.displayName(context, uri)
                        // Tools need real bytes on disk they can re-read, and a
                        // content URI's grant can vanish mid-operation.
                        FileAccess.materialize(context, DocSource.Content(uri), name)
                            ?.let { ToolInputFile(it, name) }
                    }
                }
                if (copied.isEmpty()) {
                    error = "Could not read the file you picked"
                } else {
                    inputs.addAll(copied)
                    if (selected.input != ToolInput.Images) probePages()
                }
            } catch (t: Throwable) {
                error = t.message ?: "Could not open that file"
            } finally {
                preparing = false
            }
        }
    }

    /** Appends more inputs to a multi-file tool (Merge, Image to PDF). */
    fun addMore(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val copied = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = FileAccess.displayName(context, uri)
                    FileAccess.materialize(context, DocSource.Content(uri), name)
                        ?.let { ToolInputFile(it, name) }
                }
            }
            // Materialised copies land in one shared cache directory, so two
            // files with the same name would collide; keep the first.
            val existing = inputs.map { it.file.absolutePath }.toSet()
            inputs.addAll(copied.filterNot { it.file.absolutePath in existing })
        }
    }

    private suspend fun probePages() {
        val first = inputs.firstOrNull() ?: return
        try {
            pageCount = PdfOps.pageCount(first.file, password)
            needsPassword = false
        } catch (e: PdfOps.NeedsPassword) {
            needsPassword = true
        } catch (t: Throwable) {
            error = t.message ?: "Could not read that PDF"
        }
    }

    fun submitPassword(value: String) {
        password = value
        wrongPassword = false
        viewModelScope.launch {
            try {
                pageCount = PdfOps.pageCount(inputs.first().file, value)
                needsPassword = false
            } catch (e: PdfOps.NeedsPassword) {
                wrongPassword = true
            } catch (t: Throwable) {
                error = t.message ?: "Could not read that PDF"
            }
        }
    }

    /** Where tool output lands before the user saves or shares it. */
    fun outputDir(): File =
        File(getApplication<Application>().cacheDir, "tools").apply { mkdirs() }

    /**
     * Builds an output path that won't collide with a previous run, so running
     * a tool twice doesn't silently overwrite the first result.
     */
    fun outputFile(suffix: String, extension: String = "pdf"): File {
        val base = inputs.firstOrNull()?.displayName?.substringBeforeLast('.') ?: "document"
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        var candidate = File(outputDir(), "$safe$suffix.$extension")
        var n = 2
        while (candidate.exists()) {
            candidate = File(outputDir(), "$safe$suffix ($n).$extension")
            n++
        }
        return candidate
    }

    /**
     * Runs a tool operation, funnelling every failure into [error] so no tool
     * has to repeat the try/catch, and surfacing a password prompt when the
     * input turns out to be encrypted.
     */
    fun run(status: String, work: suspend () -> ToolResult) {
        if (running) return
        running = true
        statusText = status
        error = null
        result = null
        viewModelScope.launch {
            try {
                result = work()
                recordHistory()
            } catch (e: PdfOps.NeedsPassword) {
                needsPassword = true
            } catch (e: PdfOps.OpException) {
                error = e.message
            } catch (t: Throwable) {
                error = t.message ?: "Something went wrong"
            } finally {
                running = false
                statusText = ""
            }
        }
    }

    private fun recordHistory() {
        val id = tool?.id ?: return
        viewModelScope.launch { ToolHistory.record(prefs, id) }
    }

    /** Copies a produced file to wherever the user chose in the system picker. */
    fun saveTo(destination: Uri, source: File, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination)?.use { out ->
                            source.inputStream().use { it.copyTo(out) }
                        } != null
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    fun clearError() { error = null }

    fun reset() {
        tool = null
        inputs.clear()
        pageCount = 0
        password = null
        needsPassword = false
        wrongPassword = false
        running = false
        preparing = false
        error = null
        result = null
        statusText = ""
    }
}
