package com.paperview.mobile.book

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperview.mobile.PaperViewApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The open book: its pages at the current size and style, where you are,
 * and your bookmarks. Your place is kept as a spot in the text (block and
 * character), so a new size, face or orientation lands on the same words.
 */
class BookViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs get() = (getApplication<Application>() as PaperViewApp).prefs

    var book by mutableStateOf<Book?>(null)
        private set
    var entry by mutableStateOf<BookEntry?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var style by mutableStateOf(ReadingStyle())
        private set

    /** Pages laid out so far (text books). */
    val pages = mutableStateListOf<Page>()
    var laidOut by mutableStateOf(false)
        private set
    var paginator by mutableStateOf<Paginator?>(null)
        private set
    var images: ImageCache? = null
        private set
    /** The page shown (text: index into [pages]; comics: the image). */
    var current by mutableIntStateOf(0)
        private set
    /** Where you are in the text; survives re-layouts. */
    private var spot = Spot()
    /** Waiting for the page holding [spot] to be laid out. */
    var seeking by mutableStateOf(false)
        private set

    var file: File? = null
        private set
    private var layoutJob: Job? = null
    private var layoutKey: Any? = null
    private var saveJob: Job? = null
    /** Characters before each block, for progress. */
    private var blockStart = IntArray(0)
    private var totalChars = 1

    init {
        viewModelScope.launch { style = ReadingStyle.decode(prefs.readingRaw.first()) }
    }

    fun open(f: File, name: String) {
        if (file?.path == f.path && book != null) return
        close()
        file = f
        loading = true
        error = null
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Ebooks.open(f, name) } }
            loading = false
            loaded.onFailure { error = (it as? BookException)?.message ?: "Couldn't open this book (${it.message})" }
            val b = loaded.getOrNull() ?: return@launch
            val saved = BookStore.find(prefs, f.path)
            val e = (saved ?: BookEntry(path = f.path, name = name))
                .copy(title = b.title, author = b.author, opened = System.currentTimeMillis())
            entry = e
            spot = e.spot
            (b.content as? Content.Text)?.let { t ->
                images = ImageCache(t.images)
                blockStart = IntArray(t.blocks.size + 1)
                for (i in t.blocks.indices) blockStart[i + 1] = blockStart[i] + maxOf(1, t.blocks[i].text.length)
                totalChars = maxOf(1, blockStart.last())
            }
            if (b.content is Content.Comic) current = spot.block.coerceIn(0, b.content.pages.size - 1)
            book = b
            BookStore.update(prefs, f.path) { e }
        }
    }

    fun close() {
        saveNow()
        layoutJob?.cancel()
        book = null
        entry = null
        file = null
        pages.clear()
        paginator = null
        images = null
        laidOut = false
        layoutKey = null
        current = 0
        error = null
    }

    /** Lays the book out for pages of [w] × [h] px (again, if anything changed). */
    fun layout(w: Int, h: Int) {
        val b = book ?: return
        val text = b.content as? Content.Text ?: return
        if (w <= 0 || h <= 0) return
        val key = listOf(w, h) + style.layoutKey()
        if (key == layoutKey) return
        layoutKey = key
        layoutJob?.cancel()
        pages.clear()
        laidOut = false
        seeking = true
        val p = Paginator(getApplication(), text, style, w, h)
        paginator = p
        val target = spot
        layoutJob = viewModelScope.launch(Dispatchers.Default) {
            val job = coroutineContext[Job]
            p.run(
                emit = { batch ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (paginator !== p) return@launch
                        pages.addAll(batch)
                        if (seeking) {
                            val last = pages.last()
                            if (Spot(last.block, last.char) > target || batch.size < 20) {
                                current = pageFor(target)
                                seeking = false
                            }
                        }
                    }
                },
                cancelled = { job?.isActive != true },
            )
            withContext(Dispatchers.Main) {
                if (paginator === p) {
                    laidOut = true
                    if (seeking) { current = pageFor(target); seeking = false }
                }
            }
        }
    }

    /** The last page starting at or before [s]. */
    fun pageFor(s: Spot): Int {
        var lo = 0
        var hi = pages.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val p = pages[mid]
            if (Spot(p.block, p.char) <= s) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    val pageCount: Int
        get() = when (val c = book?.content) {
            is Content.Comic -> c.pages.size
            else -> pages.size
        }

    fun goTo(index: Int) {
        val n = pageCount
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        current = i
        spot = when (book?.content) {
            is Content.Comic -> Spot(i, 0)
            else -> pages.getOrNull(i)?.let { Spot(it.block, it.char) } ?: spot
        }
        scheduleSave()
    }

    fun goToSpot(s: Spot) {
        if (book?.isComic == true) goTo(s.block) else goTo(pageFor(s))
    }

    fun goToChapter(c: Chapter) = goToSpot(Spot(c.block, 0))

    /** 0–1 through the book at a page. */
    fun progressAt(index: Int): Float = when (val c = book?.content) {
        is Content.Comic -> if (c.pages.size <= 1) 1f else index / (c.pages.size - 1f)
        else -> pages.getOrNull(index)?.let { p ->
            val start = blockStart.getOrElse(p.block) { 0 } + p.char
            (start.toFloat() / totalChars).coerceIn(0f, 1f)
        } ?: 0f
    }

    fun chapterAt(index: Int): Chapter? = when (book?.content) {
        is Content.Comic -> null
        else -> pages.getOrNull(index)?.let { book?.chapterAt(it.block) }
    }

    /** Pages left before the next chapter starts (null when unknown). */
    fun pagesLeftInChapter(index: Int): Int? {
        val b = book ?: return null
        val p = pages.getOrNull(index) ?: return null
        val next = b.chapters.firstOrNull { it.block > p.block } ?: return if (laidOut) pages.size - 1 - index else null
        val start = pageFor(Spot(next.block, 0))
        return if (start > index) start - index - 1 else null
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    private fun rangeOf(index: Int): Pair<Spot, Spot?> = when (book?.content) {
        is Content.Comic -> Spot(index, 0) to Spot(index + 1, 0)
        else -> {
            val p = pages.getOrNull(index)
            val n = pages.getOrNull(index + 1)
            Spot(p?.block ?: 0, p?.char ?: 0) to n?.let { Spot(it.block, it.char) }
        }
    }

    fun bookmarkOn(index: Int): Bookmark? {
        val (from, to) = rangeOf(index)
        return entry?.bookmarks?.firstOrNull { it.spot >= from && (to == null || it.spot < to) }
    }

    fun toggleBookmark(index: Int) {
        val e = entry ?: return
        val existing = bookmarkOn(index)
        val list = if (existing != null) {
            e.bookmarks - existing
        } else {
            val (from, _) = rangeOf(index)
            val label = when (val c = book?.content) {
                is Content.Comic -> "Page ${index + 1}"
                is Content.Text -> {
                    val snippet = c.blocks.drop(from.block).firstOrNull { it.text.isNotBlank() }?.text
                        ?.drop(if (c.blocks.getOrNull(from.block)?.text?.isNotBlank() == true) from.char else 0)
                        ?.trim()?.take(80).orEmpty()
                    snippet.ifEmpty { "Page ${index + 1}" }
                }
                null -> "Page ${index + 1}"
            }
            (e.bookmarks + Bookmark(from, label, System.currentTimeMillis())).sortedBy { it.spot }
        }
        entry = e.copy(bookmarks = list)
        saveNow()
    }

    fun removeBookmark(b: Bookmark) {
        val e = entry ?: return
        entry = e.copy(bookmarks = e.bookmarks - b)
        saveNow()
    }

    // ── Style ────────────────────────────────────────────────────────────────

    fun updateStyle(s: ReadingStyle) {
        style = s
        viewModelScope.launch { prefs.setReadingRaw(s.encode()) }
    }

    // ── Saving your place ────────────────────────────────────────────────────

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            saveNow()
        }
    }

    fun saveNow() {
        val e = entry ?: return
        val f = file ?: return
        val progress = progressAt(current)
        val updated = e.copy(spot = spot, progress = if (book?.isComic == true || pages.isNotEmpty()) progress else e.progress)
        entry = updated
        viewModelScope.launch { BookStore.update(prefs, f.path) { updated } }
    }

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }
}
