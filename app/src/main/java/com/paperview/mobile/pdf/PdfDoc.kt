package com.paperview.mobile.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.FindFlags
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One search match: a character range on a page. */
data class SearchHit(val page: Int, val charIndex: Int, val charCount: Int)

/** A link annotation: either an internal jump or a URI. */
data class LinkInfo(val bounds: Box, val destPage: Int?, val uri: String?)

/** A bookmark in the document outline, flattened with its nesting depth. */
data class TocEntry(val title: String, val page: Int, val depth: Int)

/**
 * PDFium hands back page boxes with y growing up but no guarantee about which
 * corner landed in `top`, so normalise rather than trust the field order.
 */
internal fun RectF.toBox(): Box = Box(
    left = min(left, right),
    bottom = min(top, bottom),
    right = max(left, right),
    top = max(top, bottom),
)

private fun RectF.usable(): Boolean = abs(right - left) > 1f && abs(top - bottom) > 1f

/**
 * An open PDF. Everything here runs on [PdfEngine.dispatcher]; the suspend
 * functions hop onto it themselves.
 *
 * Page and text-page handles live in a tiny LRU: search walks pages in order
 * and selection touches one or two, so a handful of live handles avoids
 * constant open/close churn without pinning a large document in memory.
 */
class PdfDoc private constructor(
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor,
    val source: DocSource,
    val pages: List<PageBox>,
) {
    val pageCount: Int get() = pages.size

    @Volatile
    private var closed = false

    private val openPages = LinkedHashMap<Int, PdfPage>()
    private val openTextPages = LinkedHashMap<Int, PdfTextPage>()

    private fun page(index: Int): PdfPage {
        openPages[index]?.let {
            openPages.remove(index); openPages[index] = it // mark most-recently-used
            return it
        }
        val p = doc.openPage(index)
        openPages[index] = p
        trim(openPages, HANDLE_CACHE) { it.close() }
        return p
    }

    private fun textPage(index: Int): PdfTextPage {
        openTextPages[index]?.let {
            openTextPages.remove(index); openTextPages[index] = it
            return it
        }
        val t = doc.openTextPage(page(index))
        openTextPages[index] = t
        trim(openTextPages, HANDLE_CACHE) { it.close() }
        return t
    }

    private inline fun <T> trim(map: LinkedHashMap<Int, T>, keep: Int, close: (T) -> Unit) {
        while (map.size > keep) {
            val oldest = map.keys.first()
            map.remove(oldest)?.let { runCatching { close(it) } }
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Renders page [index] at exactly [widthPx] x [heightPx] for user rotation
     * [rot]. Sizes arrive already snapped to whole pixels, so the bitmap is
     * painted 1:1 and fractional zoom stays sharp.
     *
     * PDFium applies the page's own `/Rotate` itself, so it renders the page
     * "naturally" and the user's rotation is a plain raster turn on top. Doing
     * it this way keeps rendering and [pageToView] in agreement by
     * construction — a render matrix that disagreed would drift every
     * highlight on rotated pages.
     */
    suspend fun render(index: Int, widthPx: Int, heightPx: Int, rot: Rotation): Bitmap =
        withContext(PdfEngine.dispatcher) {
            check(!closed) { "document closed" }
            val natW = if (rot.swaps) heightPx else widthPx
            val natH = if (rot.swaps) widthPx else heightPx
            val bmp = Bitmap.createBitmap(natW, natH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page(index).renderPageBitmap(
                bmp, 0, 0, natW, natH,
                /* renderAnnot = */ true, /* textMask = */ false,
            )
            if (rot == Rotation.R0) bmp else rotate(bmp, rot.degrees)
        }

    /**
     * Renders one tile of a page that is being displayed at
     * [fullWidthPx] x [fullHeightPx].
     *
     * PDFium has no "render this sub-rectangle" call, but its draw origin may
     * be negative: positioning the whole page at `-tileLeft, -tileTop` inside a
     * tile-sized bitmap leaves exactly the wanted region visible. That's what
     * makes deep zoom affordable — only what's on screen is ever rasterised.
     */
    suspend fun renderTile(
        index: Int,
        fullWidthPx: Int,
        fullHeightPx: Int,
        tileLeft: Int,
        tileTop: Int,
        tileWidth: Int,
        tileHeight: Int,
        rot: Rotation,
    ): Bitmap = withContext(PdfEngine.dispatcher) {
        check(!closed) { "document closed" }
        // Page space before the user's rotation is applied.
        val natFullW = if (rot.swaps) fullHeightPx else fullWidthPx
        val natFullH = if (rot.swaps) fullWidthPx else fullHeightPx

        val nat = tileToNatural(
            rot, natFullW, natFullH, tileLeft, tileTop, tileWidth, tileHeight,
        )

        val bmp = Bitmap.createBitmap(
            max(1, nat.w), max(1, nat.h), Bitmap.Config.ARGB_8888,
        )
        bmp.eraseColor(Color.WHITE)
        page(index).renderPageBitmap(
            bmp, -nat.x, -nat.y, natFullW, natFullH,
            /* renderAnnot = */ true, /* textMask = */ false,
        )
        if (rot == Rotation.R0) bmp else rotate(bmp, rot.degrees)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    suspend fun charCount(index: Int): Int = withContext(PdfEngine.dispatcher) {
        if (closed) 0 else runCatching { textPage(index).textPageCountChars() }.getOrDefault(0)
    }

    suspend fun text(index: Int, start: Int, count: Int): String =
        withContext(PdfEngine.dispatcher) {
            if (closed || count <= 0) return@withContext ""
            runCatching { textPage(index).textPageGetText(start, count) }.getOrNull().orEmpty()
        }

    /**
     * Character index nearest a point, in page-space points.
     *
     * PDFium's own hit test gives up past a small tolerance, so a touch in a
     * page margin returns nothing. When that happens and [snapVertical] is set,
     * fall back to the nearest character above or below — what a drag-select
     * needs once the finger leaves the text column. Ported from the desktop
     * build's `char_index_at`.
     */
    suspend fun charIndexAt(index: Int, p: Pt, tolerancePt: Float, snapVertical: Boolean): Int =
        withContext(PdfEngine.dispatcher) {
            if (closed) return@withContext -1
            val tp = textPage(index)
            val direct = runCatching {
                tp.textPageGetCharIndexAtPos(
                    p.x.toDouble(), p.y.toDouble(),
                    tolerancePt.toDouble(), tolerancePt.toDouble(),
                )
            }.getOrDefault(-1)
            if (direct >= 0 || !snapVertical) return@withContext direct

            val n = runCatching { tp.textPageCountChars() }.getOrDefault(0)
            if (n <= 0) return@withContext -1
            var above = -1
            var aboveY = Float.NEGATIVE_INFINITY
            var below = -1
            var belowY = Float.POSITIVE_INFINITY
            for (i in 0 until n) {
                val r = runCatching { tp.textPageGetCharBox(i) }.getOrNull() ?: continue
                val cy = (r.top + r.bottom) / 2f
                if (cy <= p.y && cy > aboveY) { aboveY = cy; above = i }
                if (cy >= p.y && cy < belowY) { belowY = cy; below = i }
            }
            when {
                above >= 0 && below >= 0 ->
                    if (abs(aboveY - p.y) <= abs(belowY - p.y)) above else below
                above >= 0 -> above
                else -> below
            }
        }

    /** Boxes covering a character range — selection and search highlights. */
    suspend fun rectsFor(index: Int, start: Int, count: Int): List<Box> =
        withContext(PdfEngine.dispatcher) {
            if (closed || count <= 0) return@withContext emptyList()
            val tp = textPage(index)
            val n = runCatching { tp.textPageCountRects(start, count) }.getOrDefault(0)
            (0 until n).mapNotNull { i ->
                runCatching { tp.textPageGetRect(i) }.getOrNull()?.toBox()
            }
        }

    /** Box of a single character — used to place the selection handles. */
    suspend fun charBox(index: Int, charIndex: Int): Box? =
        withContext(PdfEngine.dispatcher) {
            if (closed || charIndex < 0) return@withContext null
            runCatching { textPage(index).textPageGetCharBox(charIndex) }.getOrNull()?.toBox()
        }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Every match for [query] on one page, via PDFium's own search — the same
     * matcher the desktop build uses, so both agree on what counts as a hit.
     */
    suspend fun findOnPage(index: Int, query: String, matchCase: Boolean): List<SearchHit> =
        withContext(PdfEngine.dispatcher) {
            if (closed || query.isEmpty()) return@withContext emptyList()
            val flags = if (matchCase) setOf(FindFlags.MatchCase) else emptySet()
            val out = ArrayList<SearchHit>()
            runCatching {
                textPage(index).findStart(query, flags, 0)?.use { find ->
                    while (find.findNext()) {
                        out.add(SearchHit(index, find.getSchResultIndex(), find.getSchCount()))
                        if (out.size >= MAX_HITS_PER_PAGE) break
                    }
                }
            }
            out
        }

    // ── Links & outline ──────────────────────────────────────────────────────

    suspend fun links(index: Int): List<LinkInfo> = withContext(PdfEngine.dispatcher) {
        if (closed) return@withContext emptyList()
        runCatching {
            page(index).getPageLinks().map {
                LinkInfo(it.bounds.toBox(), it.destPageIdx, it.uri)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Flattened document outline.
     *
     * PDFium exposes a tree; the desktop build learned that walking it with the
     * wrong iterator duplicates every entry, so this recurses through
     * `children` explicitly and records depth for indentation.
     */
    suspend fun toc(): List<TocEntry> = withContext(PdfEngine.dispatcher) {
        if (closed) return@withContext emptyList()
        val out = ArrayList<TocEntry>()
        fun walk(items: List<PdfDocument.Bookmark>, depth: Int) {
            for (b in items) {
                out.add(TocEntry(b.title.orEmpty(), b.pageIdx.toInt(), depth))
                if (depth < MAX_TOC_DEPTH) walk(b.children, depth + 1)
            }
        }
        runCatching { walk(doc.getTableOfContents(), 0) }
        out
    }

    suspend fun title(): String? = withContext(PdfEngine.dispatcher) {
        if (closed) return@withContext null
        runCatching { doc.getDocumentMeta().title?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    suspend fun close() = withContext(PdfEngine.dispatcher) {
        if (closed) return@withContext
        closed = true
        openTextPages.values.forEach { runCatching { it.close() } }
        openTextPages.clear()
        openPages.values.forEach { runCatching { it.close() } }
        openPages.clear()
        runCatching { doc.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val HANDLE_CACHE = 4
        private const val MAX_HITS_PER_PAGE = 500
        private const val MAX_TOC_DEPTH = 8

        /**
         * Measures every page up front so continuous scroll knows the whole
         * document's height. Uses the CropBox — the box PDFium actually renders
         * — falling back to the MediaBox when it is missing or degenerate.
         */
        fun measure(doc: PdfDocument, pfd: ParcelFileDescriptor, source: DocSource): PdfDoc {
            val n = doc.getPageCount()
            val boxes = ArrayList<PageBox>(n)
            for (i in 0 until n) {
                var p: PdfPage? = null
                val measured = try {
                    p = doc.openPage(i)
                    val crop = runCatching { p.getPageCropBox() }.getOrNull()?.takeIf { it.usable() }
                        ?: runCatching { p.getPageMediaBox() }.getOrNull()?.takeIf { it.usable() }
                    val box = crop?.toBox()
                    if (box == null) {
                        // No usable box: fall back to the reported size, which
                        // already has /Rotate applied, so record no rotation.
                        PageBox(
                            widthPt = max(1f, p.getPageWidthPoint().toFloat()),
                            heightPt = max(1f, p.getPageHeightPoint().toFloat()),
                            originXPt = 0f, originYPt = 0f, intrinsic = Rotation.R0,
                        )
                    } else {
                        PageBox.from(box, intrinsicOf(p, box))
                    }
                } catch (t: Throwable) {
                    PageBox(612f, 792f, 0f, 0f) // US Letter, so a broken page still lays out
                } finally {
                    runCatching { p?.close() }
                }
                boxes.add(measured)
            }
            return PdfDoc(doc, pfd, source, boxes)
        }

        /**
         * Resolves a page's `/Rotate`.
         *
         * PDFium's raw value is quarter turns (0..3), but bindings have been
         * known to hand back degrees, so accept both. Then cross-check against
         * the reported page size, which *is* rotation-aware: if the two
         * disagree about whether width and height swap, the size wins, because
         * that is what rendering will actually produce.
         */
        private fun intrinsicOf(p: PdfPage, crop: Box): Rotation {
            val raw = runCatching { p.getPageRotation() }.getOrDefault(0)
            val reported = if (raw in 0..3) Rotation.ofQuarterTurns(raw) else Rotation.ofDegrees(raw)

            val rw = runCatching { p.getPageWidthPoint().toFloat() }.getOrDefault(0f)
            val rh = runCatching { p.getPageHeightPoint().toFloat() }.getOrDefault(0f)
            if (rw <= 0f || rh <= 0f) return reported
            if (abs(crop.width - crop.height) <= 2f) return reported // square: can't tell

            val swapped = abs(rw - crop.height) <= 2f && abs(rh - crop.width) <= 2f
            return when {
                swapped == reported.swaps -> reported
                swapped -> Rotation.R90   // size says rotated, /Rotate didn't
                else -> Rotation.R0       // size says upright, trust the size
            }
        }
    }
}
