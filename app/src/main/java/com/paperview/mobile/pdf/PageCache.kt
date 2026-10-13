package com.paperview.mobile.pdf

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Rendered-page cache, the mobile counterpart of the desktop build's texture
 * cache. Keyed by (page, pixel width, rotation) so a page re-renders when the
 * zoom lands on a different pixel size — never stretched from a stale size,
 * which is what made fractional zoom look soft.
 *
 * While a pinch is in flight the viewer *does* stretch whatever size is already
 * cached ([nearest]) and only renders at the final size once the gesture
 * settles: re-rendering every frame of a pinch is what makes a viewer stutter.
 */
class PageCache(
    private val doc: PdfDoc,
    private val scope: CoroutineScope,
    maxBytes: Int = defaultBudget(),
) {
    private data class Key(val page: Int, val widthPx: Int, val rot: Rotation)

    /** A tile of a page being shown at [widthPx] wide. */
    private data class TileKey(
        val page: Int,
        val widthPx: Int,
        val rot: Rotation,
        val col: Int,
        val row: Int,
    )

    // Whole-page renders take the larger share: at normal reading zoom they
    // are the whole image, and starving them makes ordinary scrolling stutter.
    // Tiles only matter once zoomed in, where fewer pages are on screen.
    private val bitmaps = object : LruCache<Key, Bitmap>((maxBytes * 0.6f).toInt()) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    private val tiles = object : LruCache<TileKey, Bitmap>((maxBytes * 0.4f).toInt()) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount
    }

    private val inFlight = HashMap<Key, Job>()
    private val tilesInFlight = HashMap<TileKey, Job>()

    /** Bumped whenever a render lands, so Compose repaints. */
    val version: MutableState<Int> = mutableIntStateOf(0)

    /** Exact match for the size we want to paint at, or null. */
    fun exact(page: Int, widthPx: Int, rot: Rotation): Bitmap? =
        bitmaps[Key(page, widthPx, rot)]?.takeIf { !it.isRecycled }

    /**
     * Any cached render of this page at this rotation — used to keep something
     * on screen (stretched) while the right size is still rendering.
     */
    fun nearest(page: Int, widthPx: Int, rot: Rotation): Bitmap? {
        var best: Bitmap? = null
        var bestDelta = Int.MAX_VALUE
        for ((k, v) in bitmaps.snapshot()) {
            if (k.page != page || k.rot != rot || v.isRecycled) continue
            val d = abs(k.widthPx - widthPx)
            if (d < bestDelta) { bestDelta = d; best = v }
        }
        return best
    }

    /** Queues a render if that exact size isn't cached or already running. */
    fun request(page: Int, widthPx: Int, heightPx: Int, rot: Rotation) {
        if (page !in 0 until doc.pageCount) return
        if (widthPx <= 0 || heightPx <= 0) return
        val key = Key(page, widthPx, rot)
        if (bitmaps[key] != null || inFlight.containsKey(key)) return
        inFlight[key] = scope.launch {
            try {
                val bmp = doc.render(page, widthPx, heightPx, rot)
                bitmaps.put(key, bmp)
                version.value++
            } catch (_: Throwable) {
                // A page that won't render shouldn't take the viewer down; the
                // placeholder stays and the user can scroll past it.
            } finally {
                inFlight.remove(key)
            }
        }
    }

    // ── Tiles ────────────────────────────────────────────────────────────────

    fun tile(page: Int, widthPx: Int, rot: Rotation, col: Int, row: Int): Bitmap? =
        tiles[TileKey(page, widthPx, rot, col, row)]?.takeIf { !it.isRecycled }

    /**
     * Queues one tile of page [page], which is being displayed at
     * [fullWidthPx] x [fullHeightPx].
     */
    fun requestTile(
        page: Int,
        fullWidthPx: Int,
        fullHeightPx: Int,
        rot: Rotation,
        col: Int,
        row: Int,
        tileSize: Int,
    ) {
        if (page !in 0 until doc.pageCount) return
        val key = TileKey(page, fullWidthPx, rot, col, row)
        if (tiles[key] != null || tilesInFlight.containsKey(key)) return

        val left = col * tileSize
        val top = row * tileSize
        if (left >= fullWidthPx || top >= fullHeightPx) return
        val width = minOf(tileSize, fullWidthPx - left)
        val height = minOf(tileSize, fullHeightPx - top)
        if (width <= 0 || height <= 0) return

        tilesInFlight[key] = scope.launch {
            try {
                val bmp = doc.renderTile(
                    page, fullWidthPx, fullHeightPx, left, top, width, height, rot,
                )
                tiles.put(key, bmp)
                version.value++
            } catch (_: Throwable) {
                // A tile that won't render just leaves the backdrop showing.
            } finally {
                tilesInFlight.remove(key)
            }
        }
    }

    /**
     * Drops tiles that belong to a different zoom level, rotation or page.
     *
     * [valid] maps each on-screen page to the width it is currently drawn at,
     * rather than assuming one width for the whole document — with mixed page
     * sizes a single width would evict half the tiles on every frame and the
     * viewer would thrash exactly where tiling was supposed to help.
     */
    fun keepTiles(valid: Map<Int, Int>, rot: Rotation) {
        fun keeps(page: Int, widthPx: Int, r: Rotation) =
            r == rot && valid[page] == widthPx

        for ((k, _) in tiles.snapshot()) {
            if (!keeps(k.page, k.widthPx, k.rot)) tiles.remove(k)
        }
        val stale = tilesInFlight.filterKeys { !keeps(it.page, it.widthPx, it.rot) }
        stale.forEach { (k, job) -> job.cancel(); tilesInFlight.remove(k) }
    }

    /** Drops renders for pages far outside the viewport. */
    fun keepOnly(pages: IntRange, rot: Rotation) {
        for ((k, _) in bitmaps.snapshot()) {
            if (k.rot != rot || k.page !in pages) bitmaps.remove(k)
        }
        val stale = inFlight.filterKeys { it.rot != rot || it.page !in pages }
        stale.forEach { (k, job) -> job.cancel(); inFlight.remove(k) }
    }

    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        tilesInFlight.values.forEach { it.cancel() }
        tilesInFlight.clear()
        bitmaps.evictAll()
        tiles.evictAll()
    }

    companion object {
        /**
         * A quarter of the heap, capped. Pages are ARGB_8888, so a full-width
         * page on a 1080p phone is roughly 6 MB — enough headroom for the
         * visible pages plus overscan without risking an OOM on cheap devices.
         */
        fun defaultBudget(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            val quarter = (heap / 4).coerceAtMost(96L * 1024 * 1024)
            return quarter.coerceAtLeast(24L * 1024 * 1024).toInt()
        }
    }
}
