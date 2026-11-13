package com.paperview.mobile.viewer

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperview.mobile.PaperViewApp
import com.paperview.mobile.pdf.Box
import com.paperview.mobile.pdf.DocLayout
import com.paperview.mobile.pdf.DocSource
import com.paperview.mobile.pdf.LinkInfo
import com.paperview.mobile.pdf.MAX_SCALE
import com.paperview.mobile.pdf.MIN_SCALE
import com.paperview.mobile.pdf.PageBox
import com.paperview.mobile.pdf.PageCache
import com.paperview.mobile.pdf.PasswordRequired
import com.paperview.mobile.pdf.PdfDoc
import com.paperview.mobile.pdf.PdfEngine
import com.paperview.mobile.pdf.Pt
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.SearchHit
import com.paperview.mobile.pdf.TocEntry
import com.paperview.mobile.pdf.Zoom
import com.paperview.mobile.pdf.displayHeightPt
import com.paperview.mobile.pdf.displayWidthPt
import com.paperview.mobile.pdf.resolveScale
import com.paperview.mobile.pdf.viewToPage
import com.paperview.mobile.store.RecentEntry
import com.paperview.mobile.store.PeekSpot
import com.paperview.mobile.store.PeekSpots
import com.paperview.mobile.store.Recents
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.first
import kotlin.math.min

/** What the viewer is currently showing. */
sealed interface DocState {
    data object Idle : DocState
    data object Loading : DocState
    data object Ready : DocState
    data class NeedPassword(val wrong: Boolean) : DocState
    data class Failed(val message: String) : DocState
}

enum class ViewMode { Continuous, Single }

enum class SidebarMode { Hidden, Thumbnails, Toc }

/** One end of a text selection. */
data class SelAnchor(val page: Int, val charIndex: Int)

/** Which end of a selection a drag has hold of. */
enum class HandleEnd { Start, End }

/** Highlight boxes for one page, in page space. */
data class PageOverlay(val page: Int, val rects: List<Box>)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs get() = (getApplication<Application>() as PaperViewApp).prefs

    // ── Document ─────────────────────────────────────────────────────────────

    var state by mutableStateOf<DocState>(DocState.Idle)
        private set
    var doc by mutableStateOf<PdfDoc?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var source: DocSource? = null
        private set

    private var password: String? = null
    var cache: PageCache? = null
        private set

    val pages: List<PageBox> get() = doc?.pages.orEmpty()
    val pageCount: Int get() = pages.size

    // ── View state ───────────────────────────────────────────────────────────

    var viewMode by mutableStateOf(ViewMode.Continuous)
    var sidebar by mutableStateOf(SidebarMode.Hidden)
    var rotation by mutableStateOf(Rotation.R0)
        private set
    var zoom by mutableStateOf<Zoom>(Zoom.FitPage)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var currentPage by mutableIntStateOf(0)
        private set

    /** Scroll offset into the content, in px. */
    var scrollY by mutableFloatStateOf(0f)
    var scrollX by mutableFloatStateOf(0f)

    var viewportW by mutableFloatStateOf(0f)
        private set
    var viewportH by mutableFloatStateOf(0f)
        private set

    /** Set when navigation needs the viewport to jump; the UI consumes it. */
    var pendingScroll by mutableStateOf<Float?>(null)

    var toc by mutableStateOf<List<TocEntry>>(emptyList())
        private set

    private val linkCache = HashMap<Int, List<LinkInfo>>()

    private var cachedLayout: DocLayout? = null
    private var cachedLayoutKey: Triple<Int, Rotation, Float>? = null

    /**
     * The page stack for the current scale and rotation.
     *
     * Memoised: this is read several times per frame (draw, hit-testing, fling
     * clamping) and building it walks every page, so recreating it each time
     * put an O(pages) allocation in the middle of the scroll loop.
     */
    val layout: DocLayout
        get() {
            val key = Triple(pages.size, rotation, scale)
            cachedLayout?.let { if (cachedLayoutKey == key) return it }
            return DocLayout(pages, rotation, scale, PAGE_GAP_PX).also {
                cachedLayout = it
                cachedLayoutKey = key
            }
        }

    // ── Search ───────────────────────────────────────────────────────────────

    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    val hits = mutableStateListOf<SearchHit>()
    var hitIndex by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchScanned by mutableIntStateOf(0)
        private set
    private var searchJob: Job? = null

    // ── Selection ────────────────────────────────────────────────────────────

    var selStart by mutableStateOf<SelAnchor?>(null)
        private set
    var selEnd by mutableStateOf<SelAnchor?>(null)
        private set
    val selectionRects = mutableStateListOf<PageOverlay>()

    /** Character boxes at each end of the selection, for the drag handles. */
    var startHandle by mutableStateOf<Pair<Int, Box>?>(null)
        private set
    var endHandle by mutableStateOf<Pair<Int, Box>?>(null)
        private set

    private var selectionJob: Job? = null

    val hasSelection: Boolean
        get() = selStart != null && selEnd != null && selStart != selEnd

    // ── History ──────────────────────────────────────────────────────────────

    private val backStack = ArrayDeque<Int>()
    private val forwardStack = ArrayDeque<Int>()
    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    // ── Peek ─────────────────────────────────────────────────────────────────

    /** This document's saved peek spot, or null when none is set. */
    var peekSpot by mutableStateOf<PeekSpot?>(null)
        private set

    /** Exactly where the view was, so a peek can put it back to the pixel. */
    private data class ViewSnapshot(
        val page: Int,
        val zoom: Zoom,
        val scale: Float,
        val scrollX: Float,
        val scrollY: Float,
    )

    /** Where a tap on the peek button jumped from; set while "away". */
    private var peekReturn by mutableStateOf<ViewSnapshot?>(null)

    /** Where a held peek started; restored on release. */
    private var holdReturn: ViewSnapshot? = null

    /** True after a tap jumped to the peek spot, until the next tap goes back. */
    val peekAway: Boolean get() = peekReturn != null

    /** True while the peek button is being held. */
    var peeking by mutableStateOf(false)
        private set

    /**
     * Bumped whenever the view is about to be moved programmatically, so the
     * viewport can stop a fling that would otherwise drag it straight off.
     */
    var interruptFling by mutableIntStateOf(0)
        private set

    // ── Opening ──────────────────────────────────────────────────────────────

    fun open(context: Context, src: DocSource, displayName: String, restore: RecentEntry? = null) {
        val previous = doc
        doc = null
        cache?.clear()
        cache = null
        state = DocState.Loading
        source = src
        title = displayName
        password = null
        resetPerDocState()
        viewModelScope.launch {
            previous?.close()
            loadDocument(context, src, restore)
        }
    }

    /** Retry after the user typed a password. */
    fun unlock(context: Context, pw: String) {
        val src = source ?: return
        password = pw
        state = DocState.Loading
        viewModelScope.launch { loadDocument(context, src, null) }
    }

    private suspend fun loadDocument(context: Context, src: DocSource, restore: RecentEntry?) {
        try {
            val opened = PdfEngine.open(context, src, password)
            doc = opened
            cache = PageCache(opened, viewModelScope)
            toc = opened.toc()
            opened.title()?.let { if (title.isBlank()) title = it }

            // Per-file memory: page, zoom and rotation come back as they were.
            restore?.let {
                rotation = Rotation.ofDegrees(it.rotation)
                zoom = Zoom.from(it.zoom)
                currentPage = it.page.coerceIn(0, opened.pageCount - 1)
            }
            peekSpot = PeekSpots.get(prefs, src.key)
            state = DocState.Ready
            recomputeScale()
            if (restore != null && currentPage > 0) pendingScroll = layout.scrollToPage(currentPage)
        } catch (e: PasswordRequired) {
            state = DocState.NeedPassword(e.wrongPassword)
        } catch (t: Throwable) {
            state = DocState.Failed(t.message ?: "Could not open this file")
        }
    }

    private fun resetPerDocState() {
        rotation = Rotation.R0
        zoom = Zoom.FitPage
        currentPage = 0
        scrollX = 0f
        scrollY = 0f
        toc = emptyList()
        linkCache.clear()
        backStack.clear()
        forwardStack.clear()
        peekSpot = null
        peekReturn = null
        holdReturn = null
        peeking = false
        clearSearch()
        clearSelection()
    }

    fun closeDocument() {
        val d = doc
        doc = null
        cache?.clear()
        cache = null
        state = DocState.Idle
        source = null
        title = ""
        resetPerDocState()
        viewModelScope.launch { d?.close() }
    }

    /** Snapshot for the recents list, so reopening lands where you left off. */
    fun snapshot(): RecentEntry? {
        val src = source ?: return null
        return RecentEntry(
            uri = src.key,
            name = title,
            page = currentPage,
            zoom = zoom.key,
            rotation = rotation.degrees,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun persistState() {
        // Mid-peek the view is somewhere the reader is only glancing at;
        // remembering it as "where you left off" would be wrong.
        if (peeking) return
        val entry = snapshot() ?: return
        viewModelScope.launch { Recents.record(prefs, entry) }
    }

    // ── Viewport & zoom ──────────────────────────────────────────────────────

    private var wasLandscape: Boolean? = null

    fun setViewport(w: Float, h: Float) {
        if (w == viewportW && h == viewportH) return
        viewportW = w
        viewportH = h

        // Auto zoom modes follow the orientation: a whole page in landscape is
        // small and margin-heavy, so open at fit-width there and let pinching
        // out reach the whole page. A zoom the reader set by hand is left alone.
        val landscape = w > h
        if (zoom !is Zoom.Factor && (wasLandscape == null || wasLandscape != landscape)) {
            zoom = if (landscape) Zoom.FitWidth else Zoom.FitPage
        }
        wasLandscape = landscape

        recomputeScale()
    }

    /**
     * Where the content block starts on screen.
     *
     * Pages are laid out relative to the widest page, which says nothing about
     * the screen. When the whole block is narrower than the viewport — every
     * zoomed-out landscape view — it has to be centred here, or it sits pinned
     * to the left edge and all the slack collects on one side.
     */
    fun contentOriginX(): Float = max(0f, (viewportW - layout.contentWidthPx) / 2f)

    /** Same idea vertically, for a document shorter than the screen. */
    fun contentOriginY(): Float =
        if (viewMode == ViewMode.Continuous) {
            max(0f, (viewportH - layout.contentHeightPx) / 2f)
        } else 0f

    private fun recomputeScale() {
        val page = pages.getOrNull(currentPage) ?: pages.firstOrNull() ?: return
        if (viewportW <= 0f || viewportH <= 0f) return
        val padded = (viewportW - 2 * PAGE_GAP_PX).coerceAtLeast(1f)
        scale = resolveScale(zoom, page, rotation, padded, viewportH)
        clampScroll()
    }

    fun applyZoom(z: Zoom) {
        zoom = z
        recomputeScale()
        persistZoom()
    }

    /**
     * Smallest useful zoom: one whole page fitted to the viewport.
     *
     * Going below this only buys a view of several tiny pages at once, which
     * isn't reading — it's just a way to lose your place.
     */
    fun minScale(): Float {
        val page = pages.getOrNull(currentPage) ?: pages.firstOrNull() ?: return MIN_SCALE
        if (viewportW <= 0f || viewportH <= 0f) return MIN_SCALE
        val padded = (viewportW - 2 * PAGE_GAP_PX).coerceAtLeast(1f)
        return resolveScale(Zoom.FitPage, page, rotation, padded, viewportH)
    }

    /**
     * Pinch zoom about a focal point, keeping the content under the fingers
     * still — the same anchored-zoom math the desktop build uses for the mouse
     * cursor.
     */
    fun pinch(factor: Float, focalX: Float, focalY: Float) {
        val old = scale
        val next = (old * factor).coerceIn(minScale(), MAX_SCALE)
        if (next == old) return
        val ratio = next / old

        // Work in content space, which means undoing the centring offset before
        // scaling and reapplying it after — otherwise the point under the
        // fingers drifts whenever the content is narrower than the screen.
        val originX = contentOriginX()
        val originY = contentOriginY()
        val contentX = scrollX + focalX - originX
        val contentY = scrollY + focalY - originY

        val l = layout
        val nextOriginX = max(0f, (viewportW - l.contentWidthPx * ratio) / 2f)
        val nextOriginY = if (viewMode == ViewMode.Continuous) {
            max(0f, (viewportH - l.contentHeightPx * ratio) / 2f)
        } else 0f

        scrollX = contentX * ratio - (focalX - nextOriginX)
        scrollY = contentY * ratio - (focalY - nextOriginY)
        scale = next
        zoom = Zoom.Factor(next)
        clampScroll()
        syncCurrentPageToScroll()
    }

    /** Double-tap: toggle between fitting the page and a comfortable 2x. */
    fun toggleZoom(focalX: Float, focalY: Float) {
        if (zoom is Zoom.Factor) {
            applyZoom(Zoom.FitPage)
            pendingScroll = layout.scrollToPage(currentPage)
        } else {
            pinch(2f, focalX, focalY)
        }
    }

    /**
     * Vertical scroll limits.
     *
     * Continuous mode runs the whole document; single-page mode pins scrolling
     * to the current page's row, and stepping off the edge flips pages — the
     * same model as the desktop build's wheel-past-edge behaviour.
     */
    fun scrollBounds(): ClosedFloatingPointRange<Float> {
        val l = layout
        if (l.count == 0) return 0f..0f
        return if (viewMode == ViewMode.Continuous) {
            0f..max(0f, l.contentHeightPx - viewportH)
        } else {
            val top = l.pageTopPx(currentPage)
            val bottom = top + l.pageHeightPx(currentPage)
            top..max(top, bottom - viewportH)
        }
    }

    fun clampScroll() {
        val l = layout
        val bounds = scrollBounds()
        val maxX = max(0f, l.contentWidthPx - viewportW)
        scrollY = scrollY.coerceIn(bounds.start, bounds.endInclusive)
        scrollX = scrollX.coerceIn(0f, maxX)
    }

    /**
     * In single-page mode, a drag that pushes past the top or bottom of the
     * page flips to the neighbouring one. Returns true when a flip happened.
     */
    fun flipIfPastEdge(overscrollPx: Float): Boolean {
        if (viewMode != ViewMode.Single) return false
        if (abs(overscrollPx) < EDGE_FLIP_PX) return false
        val bounds = scrollBounds()
        return when {
            overscrollPx > 0 && scrollY >= bounds.endInclusive && currentPage < pageCount - 1 -> {
                currentPage += 1
                pendingScroll = layout.scrollToPage(currentPage)
                true
            }
            overscrollPx < 0 && scrollY <= bounds.start && currentPage > 0 -> {
                currentPage -= 1
                // Land at the bottom of the previous page, so reading backwards
                // is continuous rather than jumping to its top.
                val l = layout
                pendingScroll =
                    max(l.pageTopPx(currentPage), l.pageTopPx(currentPage) + l.pageHeightPx(currentPage) - viewportH)
                true
            }
            else -> false
        }
    }

    fun syncCurrentPageToScroll() {
        if (pageCount == 0 || viewMode != ViewMode.Continuous) return
        val p = layout.pageAtViewportCentre(scrollY, viewportH)
        if (p != currentPage) currentPage = p
    }

    fun rotateLeft() = applyRotation(rotation.left())

    fun rotateRight() = applyRotation(rotation.right())

    private fun applyRotation(r: Rotation) {
        rotation = r
        cache?.clear()
        recomputeScale()
        pendingScroll = layout.scrollToPage(currentPage)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    fun goToPage(index: Int, pushHistory: Boolean = false) {
        val clamped = index.coerceIn(0, max(0, pageCount - 1))
        if (pushHistory && clamped != currentPage) {
            backStack.addLast(currentPage)
            forwardStack.clear()
            if (backStack.size > HISTORY_LIMIT) backStack.removeFirst()
        }
        currentPage = clamped
        pendingScroll = layout.scrollToPage(clamped)
    }

    fun goBack() {
        val target = backStack.removeLastOrNull() ?: return
        forwardStack.addLast(currentPage)
        currentPage = target
        pendingScroll = layout.scrollToPage(target)
    }

    fun goForward() {
        val target = forwardStack.removeLastOrNull() ?: return
        backStack.addLast(currentPage)
        currentPage = target
        pendingScroll = layout.scrollToPage(target)
    }

    fun nextPage() = goToPage(currentPage + 1)

    // ── Peek ─────────────────────────────────────────────────────────────────

    /** Saves what is on screen now as this document's peek spot. */
    fun savePeekSpot() {
        val src = source ?: return
        val l = layout
        if (l.count == 0) return
        // The page under the top edge of the screen anchors the spot.
        val anchor = if (viewMode == ViewMode.Single) currentPage else {
            var i = 0
            while (i + 1 < l.count && l.pageTopPx(i + 1) <= scrollY) i++
            i
        }
        val spot = PeekSpot(
            page = anchor,
            yFrac = (scrollY - l.pageTopPx(anchor)) / l.pageHeightPx(anchor).coerceAtLeast(1f),
            xFrac = if (l.contentWidthPx > 0f) scrollX / l.contentWidthPx else 0f,
            zoom = zoom.key,
        )
        peekSpot = spot
        // Saving here means "here" is the spot now: there is nothing to go back to.
        peekReturn = null
        viewModelScope.launch { PeekSpots.put(prefs, src.key, spot) }
    }

    fun removePeekSpot() {
        val src = source ?: return
        peekSpot = null
        peekReturn = null
        viewModelScope.launch { PeekSpots.remove(prefs, src.key) }
    }

    /**
     * A tap on the peek button: go to the spot, or, if a tap already took you
     * there, come back. Returns true when it jumped *to* the spot.
     */
    fun togglePeekJump(): Boolean {
        val spot = peekSpot ?: return false
        val back = peekReturn
        return if (back != null) {
            restoreView(back)
            peekReturn = null
            false
        } else {
            peekReturn = captureView()
            showSpot(spot)
            true
        }
    }

    /** Button pressed and held: show the spot until [endPeek]. */
    fun beginPeek() {
        val spot = peekSpot ?: return
        if (peeking) return
        holdReturn = captureView()
        peeking = true
        showSpot(spot)
    }

    /** Button released: back to exactly where the reader was. */
    fun endPeek() {
        holdReturn?.let { restoreView(it) }
        holdReturn = null
        peeking = false
    }

    private fun captureView() = ViewSnapshot(currentPage, zoom, scale, scrollX, scrollY)

    private fun restoreView(v: ViewSnapshot) {
        interruptFling++
        pendingScroll = null
        currentPage = v.page.coerceIn(0, max(0, pageCount - 1))
        zoom = v.zoom
        scale = v.scale
        scrollX = v.scrollX
        scrollY = v.scrollY
        clampScroll()
        syncCurrentPageToScroll()
    }

    private fun showSpot(s: PeekSpot) {
        if (pageCount == 0) return
        interruptFling++
        pendingScroll = null
        val page = s.page.coerceIn(0, pageCount - 1)
        currentPage = page
        zoom = Zoom.from(s.zoom)
        recomputeScale()
        val l = layout
        scrollY = l.pageTopPx(page) + s.yFrac * l.pageHeightPx(page)
        scrollX = s.xFrac * l.contentWidthPx
        clampScroll()
        syncCurrentPageToScroll()
    }

    /** Counts tips shown, so the "hold to peek" hint stops after a few. */
    suspend fun takePeekTip(limit: Int): Boolean {
        val shown = prefs.peekTips.first()
        if (shown >= limit) return false
        prefs.setPeekTips(shown + 1)
        return true
    }

    fun prevPage() = goToPage(currentPage - 1)

    // ── Links ────────────────────────────────────────────────────────────────

    suspend fun linksFor(page: Int): List<LinkInfo> {
        linkCache[page]?.let { return it }
        val d = doc ?: return emptyList()
        val links = d.links(page)
        linkCache[page] = links
        return links
    }

    /** The link under a tap, in page-space points, or null. */
    suspend fun linkAt(page: Int, p: Pt): LinkInfo? =
        linksFor(page).firstOrNull { l ->
            p.x >= l.bounds.left && p.x <= l.bounds.right &&
                p.y >= l.bounds.bottom && p.y <= l.bounds.top
        }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Whole-document search that reports matches as it goes, starting at the
     * current page and wrapping — so the first hit usually appears immediately
     * instead of after a full scan, exactly like the desktop build.
     */
    fun search(text: String) {
        searchJob?.cancel()
        query = text
        hits.clear()
        hitIndex = -1
        searchScanned = 0
        if (text.isBlank()) { searching = false; return }
        val d = doc ?: return
        searching = true
        val start = currentPage
        searchJob = viewModelScope.launch {
            for (n in 0 until d.pageCount) {
                val page = (start + n) % d.pageCount
                val found = d.findOnPage(page, text, matchCase = false)
                if (found.isNotEmpty()) {
                    hits.addAll(found)
                    if (hitIndex < 0) {
                        hitIndex = 0
                        jumpToHit(0)
                    }
                }
                searchScanned = n + 1
                yield()
            }
            searching = false
        }
    }

    fun nextHit() {
        if (hits.isEmpty()) return
        hitIndex = (hitIndex + 1).mod(hits.size)
        jumpToHit(hitIndex)
    }

    fun prevHit() {
        if (hits.isEmpty()) return
        hitIndex = (hitIndex - 1).mod(hits.size)
        jumpToHit(hitIndex)
    }

    private fun jumpToHit(i: Int) {
        val hit = hits.getOrNull(i) ?: return
        currentPage = hit.page
        viewModelScope.launch {
            // Centre the match rather than the page, so a hit low on a long
            // page is actually on screen.
            val rects = doc?.rectsFor(hit.page, hit.charIndex, hit.charCount).orEmpty()
            val l = layout
            val base = l.scrollToPage(hit.page)
            val target = rects.firstOrNull()?.let { r ->
                val box = pages[hit.page]
                val hPx = l.pageHeightPx(hit.page)
                val wPx = l.pageWidthPx(hit.page)
                val v = com.paperview.mobile.pdf.rectToView(r, box, rotation, wPx, hPx)
                base + v.top - viewportH / 3f
            } ?: base
            pendingScroll = target.coerceAtLeast(0f)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searching = false
        query = ""
        hits.clear()
        hitIndex = -1
        searchScanned = 0
    }

    /** Highlight boxes for one page: every hit, with the current one flagged. */
    suspend fun searchOverlay(page: Int): List<Pair<Box, Boolean>> {
        val d = doc ?: return emptyList()
        val onPage = hits.withIndex().filter { it.value.page == page }
        if (onPage.isEmpty()) return emptyList()
        return onPage.flatMap { (i, hit) ->
            d.rectsFor(page, hit.charIndex, hit.charCount).map { it to (i == hitIndex) }
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    fun beginSelection(page: Int, charIndex: Int) {
        if (charIndex < 0) return
        selStart = SelAnchor(page, charIndex)
        selEnd = SelAnchor(page, charIndex)
        refreshSelectionRects()
    }

    fun extendSelection(page: Int, charIndex: Int) {
        if (charIndex < 0 || selStart == null) return
        selEnd = SelAnchor(page, charIndex)
        refreshSelectionRects()
    }

    /** Moves whichever end of the selection the user grabbed. */
    fun dragHandle(which: HandleEnd, page: Int, charIndex: Int) {
        if (charIndex < 0) return
        val (from, to) = normalizedSelection() ?: return
        when (which) {
            HandleEnd.Start -> { selStart = SelAnchor(page, charIndex); selEnd = to }
            HandleEnd.End -> { selStart = from; selEnd = SelAnchor(page, charIndex) }
        }
        refreshSelectionRects()
    }

    /** Selects the word under a point — the long-press entry point. */
    fun selectWordAt(page: Int, charIndex: Int) {
        val d = doc ?: return
        if (charIndex < 0) return
        viewModelScope.launch {
            val total = d.charCount(page)
            if (total <= 0) return@launch
            val window = 64
            val from = max(0, charIndex - window)
            val text = d.text(page, from, min(window * 2, total - from))
            val local = charIndex - from
            if (local !in text.indices) {
                beginSelection(page, charIndex)
                return@launch
            }
            var s = local
            var e = local
            while (s > 0 && text[s - 1].isLetterOrDigit()) s--
            while (e + 1 < text.length && text[e + 1].isLetterOrDigit()) e++
            selStart = SelAnchor(page, from + s)
            selEnd = SelAnchor(page, from + e)
            refreshSelectionRects()
        }
    }

    fun selectAll() {
        val d = doc ?: return
        viewModelScope.launch {
            val last = d.pageCount - 1
            val lastCount = d.charCount(last)
            selStart = SelAnchor(0, 0)
            selEnd = SelAnchor(last, max(0, lastCount - 1))
            refreshSelectionRects()
        }
    }

    fun clearSelection() {
        selectionJob?.cancel()
        selStart = null
        selEnd = null
        startHandle = null
        endHandle = null
        selectionRects.clear()
    }

    /** Ordered (first, last) ends of the selection. */
    fun normalizedSelection(): Pair<SelAnchor, SelAnchor>? {
        val a = selStart ?: return null
        val b = selEnd ?: return null
        val forward = a.page < b.page || (a.page == b.page && a.charIndex <= b.charIndex)
        return if (forward) a to b else b to a
    }

    private fun refreshSelectionRects() {
        selectionJob?.cancel()
        val d = doc ?: return
        val (from, to) = normalizedSelection() ?: return
        selectionJob = viewModelScope.launch {
            val out = ArrayList<PageOverlay>()
            for (page in from.page..to.page) {
                val count = d.charCount(page)
                if (count <= 0) continue
                val s = if (page == from.page) from.charIndex else 0
                val e = if (page == to.page) to.charIndex else count - 1
                if (e < s) continue
                out.add(PageOverlay(page, d.rectsFor(page, s, e - s + 1)))
                yield()
            }
            selectionRects.clear()
            selectionRects.addAll(out)
            startHandle = d.charBox(from.page, from.charIndex)?.let { from.page to it }
            endHandle = d.charBox(to.page, to.charIndex)?.let { to.page to it }
        }
    }

    /** The selected text, joined across pages. */
    suspend fun selectedText(): String {
        val d = doc ?: return ""
        val (from, to) = normalizedSelection() ?: return ""
        val sb = StringBuilder()
        for (page in from.page..to.page) {
            val count = d.charCount(page)
            if (count <= 0) continue
            val s = if (page == from.page) from.charIndex else 0
            val e = if (page == to.page) to.charIndex else count - 1
            if (e < s) continue
            sb.append(d.text(page, s, e - s + 1))
            if (page != to.page) sb.append('\n')
        }
        return sb.toString()
    }

    fun copySelection(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val text = selectedText()
            if (text.isBlank()) { onDone(false); return@launch }
            val cm = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PaperView", text))
            onDone(true)
        }
    }

    /** Converts a touch inside a page's rect to page-space points. */
    fun toPageSpace(page: Int, xInPage: Float, yInPage: Float): Pt {
        val l = layout
        return viewToPage(
            Pt(xInPage, yInPage),
            pages[page],
            rotation,
            l.pageWidthPx(page),
            l.pageHeightPx(page),
        )
    }

    private fun persistZoom() {
        viewModelScope.launch { prefs.setZoom(zoom.key) }
    }

    override fun onCleared() {
        super.onCleared()
        val d = doc
        cache?.clear()
        viewModelScope.launch { d?.close() }
    }

    companion object {
        /** Gap between pages, and the viewport's side padding. */
        const val PAGE_GAP_PX = 12f
        private const val HISTORY_LIMIT = 64

        /** How far past a page edge a single-page drag must go to flip. */
        private const val EDGE_FLIP_PX = 90f
    }
}

/** Page size on screen, in px, for the current scale and rotation. */
fun DocLayout.pageSizePx(page: Int, pages: List<PageBox>, rot: Rotation): Pair<Float, Float> =
    pages[page].displayWidthPt(rot) * scale to pages[page].displayHeightPt(rot) * scale
