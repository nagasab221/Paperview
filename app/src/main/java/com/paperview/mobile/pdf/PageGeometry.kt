package com.paperview.mobile.pdf

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Layout and coordinate math for the viewer — a port of the desktop build's
 * `view.rs`.
 *
 * Deliberately free of Android types so it runs under plain JUnit: getting
 * these transforms wrong is the difference between selection highlights that
 * land on the text and ones that float half a page away.
 */

/** A point in PDF page space (origin bottom-left, y grows up). */
data class Pt(val x: Float, val y: Float)

/** A rectangle in PDF page space. */
data class Box(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

/** A rectangle in view space (px, origin top-left, y grows down). */
data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A page's box in points, its origin, and its own `/Rotate` value.
 *
 * Two separate gotchas are folded in here:
 *
 * 1. The origin. PDFium reports text and link coordinates in page space, but
 *    renders starting at the CropBox. A CropBox that doesn't begin at (0,0)
 *    offsets every overlay unless it's subtracted — the bug the desktop build
 *    hit and fixed.
 * 2. The intrinsic rotation. PDFium *applies* `/Rotate` when rendering but
 *    reports the CropBox and all text coordinates unrotated. So the width and
 *    height here are the unrotated ones, and [intrinsic] is composed with the
 *    user's rotation whenever page space meets the screen.
 */
data class PageBox(
    val widthPt: Float,
    val heightPt: Float,
    val originXPt: Float,
    val originYPt: Float,
    val intrinsic: Rotation = Rotation.R0,
) {
    companion object {
        fun from(box: Box, intrinsic: Rotation = Rotation.R0) = PageBox(
            widthPt = max(box.width, 1f),
            heightPt = max(box.height, 1f),
            originXPt = box.left,
            originYPt = box.bottom,
            intrinsic = intrinsic,
        )
    }
}

enum class Rotation(val degrees: Int) {
    R0(0), R90(90), R180(180), R270(270);

    /** True when the page's width and height swap on screen. */
    val swaps: Boolean get() = this == R90 || this == R270

    fun left(): Rotation = entries[(ordinal + 3) % 4]
    fun right(): Rotation = entries[(ordinal + 1) % 4]

    operator fun plus(other: Rotation): Rotation = entries[(ordinal + other.ordinal) % 4]

    companion object {
        /** Accepts either degrees (0/90/180/270) or PDFium's quarter turns (0..3). */
        fun ofDegrees(d: Int): Rotation = entries[((d / 90) % 4 + 4) % 4]

        fun ofQuarterTurns(q: Int): Rotation = entries[((q % 4) + 4) % 4]
    }
}

/** Total rotation between unrotated page space and the screen. */
fun PageBox.effective(userRot: Rotation): Rotation = intrinsic + userRot

/** On-screen size of a page in points, after both rotations. */
fun PageBox.displayWidthPt(rot: Rotation): Float =
    if (effective(rot).swaps) heightPt else widthPt

fun PageBox.displayHeightPt(rot: Rotation): Float =
    if (effective(rot).swaps) widthPt else heightPt

/**
 * Vertical stack of pages — the continuous-scroll model. All values are px.
 *
 * Pages are centred horizontally on the widest page so a document with mixed
 * page sizes doesn't jitter left and right as you scroll.
 */
class DocLayout(
    private val pages: List<PageBox>,
    val rot: Rotation,
    val scale: Float,
    val gapPx: Float,
) {
    val count: Int get() = pages.size

    private val tops: FloatArray = FloatArray(pages.size)
    val contentHeightPx: Float
    val contentWidthPx: Float

    init {
        var y = 0f
        var widest = 0f
        for (i in pages.indices) {
            tops[i] = y
            y += pageHeightPx(i) + gapPx
            widest = max(widest, pageWidthPx(i))
        }
        contentHeightPx = if (pages.isEmpty()) 0f else y - gapPx
        contentWidthPx = widest
    }

    fun pageWidthPx(i: Int): Float = pages[i].displayWidthPt(rot) * scale

    fun pageHeightPx(i: Int): Float = pages[i].displayHeightPt(rot) * scale

    fun pageTopPx(i: Int): Float = tops[i]

    /** Left edge of page [i] when pages are centred on the widest one. */
    fun pageLeftPx(i: Int): Float = (contentWidthPx - pageWidthPx(i)) / 2f

    /** The page occupying the vertical centre of a viewport at [scrollPx]. */
    fun pageAtViewportCentre(scrollPx: Float, viewportHeightPx: Float): Int {
        val probe = scrollPx + viewportHeightPx / 2f
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= probe) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Indices whose rows intersect the viewport, padded by [overscanPx].
     *
     * Binary search rather than a scan: this runs every frame, and a linear
     * walk turns a thousand-page document into a thousand comparisons per
     * frame for no reason.
     */
    fun visiblePages(scrollPx: Float, viewportHeightPx: Float, overscanPx: Float = 0f): IntRange {
        if (pages.isEmpty()) return IntRange.EMPTY
        val top = scrollPx - overscanPx
        val bottom = scrollPx + viewportHeightPx + overscanPx
        if (bottom < 0f || top > contentHeightPx) return IntRange.EMPTY

        // Last page starting at or before `top`, then walk forward while the
        // rows keep intersecting. Pages are laid out in ascending order, so
        // the first candidate is the only one that can start above `top`.
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= top) lo = mid else hi = mid - 1
        }
        var first = lo
        // That page may end above the viewport; if so the next one starts it.
        if (tops[first] + pageHeightPx(first) < top && first + 1 < pages.size) first++

        var last = first
        while (last + 1 < pages.size && tops[last + 1] <= bottom) last++

        return if (tops[first] > bottom) IntRange.EMPTY else first..last
    }

    /** Scroll offset that puts page [i] at the top of the viewport. */
    fun scrollToPage(i: Int): Float = tops[i.coerceIn(0, max(0, pages.size - 1))]
}

/**
 * Zoom modes. FitPage is the default everywhere, matching the desktop build.
 */
sealed class Zoom {
    data object FitPage : Zoom()
    data object FitWidth : Zoom()
    data class Factor(val value: Float) : Zoom()

    val key: String
        get() = when (this) {
            FitPage -> "fit_page"
            FitWidth -> "fit_width"
            is Factor -> "factor:$value"
        }

    companion object {
        fun from(key: String?): Zoom = when {
            key == "fit_width" -> FitWidth
            key != null && key.startsWith("factor:") ->
                key.removePrefix("factor:").toFloatOrNull()?.let { Factor(it) } ?: FitPage
            else -> FitPage
        }
    }
}

/**
 * Resolves a [Zoom] to a points-to-pixels scale for one page.
 *
 * [viewportWidthPx]/[viewportHeightPx] already exclude any padding.
 */
fun resolveScale(
    zoom: Zoom,
    page: PageBox,
    rot: Rotation,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): Float {
    val wPt = page.displayWidthPt(rot)
    val hPt = page.displayHeightPt(rot)
    if (wPt <= 0f || hPt <= 0f) return 1f
    return when (zoom) {
        Zoom.FitWidth -> viewportWidthPx / wPt
        Zoom.FitPage -> minOf(viewportWidthPx / wPt, viewportHeightPx / hPt)
        is Zoom.Factor -> zoom.value
    }.coerceIn(MIN_SCALE, MAX_SCALE)
}

/** Floor for the raw scale value; the viewer clamps to fit-page on top of it. */
const val MIN_SCALE = 0.05f
const val MAX_SCALE = 12f

/**
 * Beyond this width a page is drawn from tiles rather than one giant bitmap.
 *
 * A page rendered at full zoom is enormous — an A4 page at 3x on a 1440px
 * screen is over 100 MB, more than the whole cache, so it thrashes and the
 * viewer stutters. The whole-page render is capped here and used as a
 * stretched backdrop; sharp detail comes from tiles of the visible region.
 */
const val PREVIEW_MAX_WIDTH_PX = 1600

/** Edge length of a render tile, in device pixels. */
const val TILE_PX = 512

/** An integer pixel rectangle. */
data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Maps a tile rectangle from display space back into the page's unrotated
 * render space.
 *
 * PDFium renders the page before the user's rotation, so a tile has to be
 * asked for in those coordinates and the result turned afterwards. This is the
 * inverse of [pageToView]'s rotation branch; if the two disagree, tiles land in
 * the wrong part of a rotated page.
 */
fun tileToNatural(
    rot: Rotation,
    naturalWidth: Int,
    naturalHeight: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
): PxRect = when (rot) {
    Rotation.R0 -> PxRect(left, top, width, height)
    Rotation.R90 -> PxRect(top, naturalHeight - (left + width), height, width)
    Rotation.R180 -> PxRect(
        naturalWidth - (left + width),
        naturalHeight - (top + height),
        width,
        height,
    )
    Rotation.R270 -> PxRect(naturalWidth - (top + height), left, height, width)
}

/**
 * PDF page point -> pixel offset inside the page's rendered rect.
 *
 * [rot] is the *user's* rotation; the page's own `/Rotate` is composed in here.
 * [wPx]/[hPx] are the page's on-screen size after both.
 */
fun pageToView(p: Pt, box: PageBox, rot: Rotation, wPx: Float, hPx: Float): Pt {
    val eff = box.effective(rot)
    // Unrotated view space first: y flips, origin moves to the crop box corner.
    val s = unrotatedScale(box, eff, wPx, hPx)
    val ux = (p.x - box.originXPt) * s
    val uy = (box.originYPt + box.heightPt - p.y) * s
    val w0 = box.widthPt * s
    val h0 = box.heightPt * s
    return when (eff) {
        Rotation.R0 -> Pt(ux, uy)
        Rotation.R90 -> Pt(h0 - uy, ux)
        Rotation.R180 -> Pt(w0 - ux, h0 - uy)
        Rotation.R270 -> Pt(uy, w0 - ux)
    }
}

/** Inverse of [pageToView]: a tap in page-rect pixels back to PDF points. */
fun viewToPage(v: Pt, box: PageBox, rot: Rotation, wPx: Float, hPx: Float): Pt {
    val eff = box.effective(rot)
    val s = unrotatedScale(box, eff, wPx, hPx)
    val w0 = box.widthPt * s
    val h0 = box.heightPt * s
    val (ux, uy) = when (eff) {
        Rotation.R0 -> v.x to v.y
        Rotation.R90 -> v.y to (h0 - v.x)
        Rotation.R180 -> (w0 - v.x) to (h0 - v.y)
        Rotation.R270 -> (w0 - v.y) to v.x
    }
    return Pt(
        x = ux / s + box.originXPt,
        y = box.originYPt + box.heightPt - uy / s,
    )
}

/** Maps a page-space rect to the page's on-screen rect, normalised. */
fun rectToView(r: Box, box: PageBox, rot: Rotation, wPx: Float, hPx: Float): ViewRect {
    val a = pageToView(Pt(r.left, r.top), box, rot, wPx, hPx)
    val b = pageToView(Pt(r.right, r.bottom), box, rot, wPx, hPx)
    return ViewRect(
        left = minOf(a.x, b.x),
        top = minOf(a.y, b.y),
        right = maxOf(a.x, b.x),
        bottom = maxOf(a.y, b.y),
    )
}

/** px-per-point, derived from whichever screen axis the page's width lands on. */
private fun unrotatedScale(box: PageBox, eff: Rotation, wPx: Float, hPx: Float): Float =
    if (eff.swaps) hPx / box.widthPt else wPx / box.widthPt

/**
 * Snaps a pixel size to whole physical pixels.
 *
 * Rendering a page at a fractional width and stretching it to fit is what made
 * fractional zoom look blurry on the desktop build; rendering at the exact
 * integer size the page is painted at fixes it.
 */
fun snapPx(value: Float): Int = max(1, value.roundToInt())
