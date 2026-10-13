package com.paperview.mobile

import com.paperview.mobile.pdf.Box
import com.paperview.mobile.pdf.DocLayout
import com.paperview.mobile.pdf.PageBox
import com.paperview.mobile.pdf.Pt
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.Zoom
import com.paperview.mobile.pdf.displayHeightPt
import com.paperview.mobile.pdf.displayWidthPt
import com.paperview.mobile.pdf.pageToView
import com.paperview.mobile.pdf.rectToView
import com.paperview.mobile.pdf.resolveScale
import com.paperview.mobile.pdf.tileToNatural
import com.paperview.mobile.pdf.viewToPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 0.01f

/** US Letter with a CropBox that does not start at the origin. */
private val offsetPage = PageBox(
    widthPt = 612f, heightPt = 792f,
    originXPt = 10f, originYPt = 20f,
)

private val letter = PageBox(612f, 792f, 0f, 0f)

class PageGeometryTest {

    @Test
    fun `pages stack with gaps and report total height`() {
        val layout = DocLayout(listOf(letter, letter, letter), Rotation.R0, scale = 1f, gapPx = 8f)
        assertEquals(0f, layout.pageTopPx(0), EPS)
        assertEquals(800f, layout.pageTopPx(1), EPS)   // 792 + 8
        assertEquals(1600f, layout.pageTopPx(2), EPS)
        // Trailing gap is not counted after the last page.
        assertEquals(792f * 3 + 8f * 2, layout.contentHeightPx, EPS)
    }

    @Test
    fun `mixed page widths centre on the widest page`() {
        val wide = PageBox(1000f, 500f, 0f, 0f)
        val layout = DocLayout(listOf(letter, wide), Rotation.R0, scale = 1f, gapPx = 0f)
        assertEquals(1000f, layout.contentWidthPx, EPS)
        assertEquals(194f, layout.pageLeftPx(0), EPS)  // (1000 - 612) / 2
        assertEquals(0f, layout.pageLeftPx(1), EPS)
    }

    @Test
    fun `viewport centre picks the page under it`() {
        val layout = DocLayout(List(4) { letter }, Rotation.R0, scale = 1f, gapPx = 0f)
        // Viewport 400 tall: centre at 200 -> page 0.
        assertEquals(0, layout.pageAtViewportCentre(scrollPx = 0f, viewportHeightPx = 400f))
        // Centre at 1000 -> page 1 (spans 792..1584).
        assertEquals(1, layout.pageAtViewportCentre(scrollPx = 800f, viewportHeightPx = 400f))
    }

    @Test
    fun `visible pages cover the viewport and nothing else`() {
        val layout = DocLayout(List(10) { letter }, Rotation.R0, scale = 1f, gapPx = 0f)
        val range = layout.visiblePages(scrollPx = 0f, viewportHeightPx = 800f)
        assertEquals(0, range.first)
        assertEquals(1, range.last)  // page 1 starts at 792, just inside
    }

    @Test
    fun `visible pages match a brute-force scan at every scroll offset`() {
        val docs = listOf(
            List(10) { letter },
            List(25) { i -> if (i % 3 == 0) PageBox(612f, 400f, 0f, 0f) else letter },
            listOf(letter),
        )
        for (pages in docs) {
            val layout = DocLayout(pages, Rotation.R0, scale = 1f, gapPx = 8f)
            var scroll = -200f
            while (scroll < layout.contentHeightPx + 200f) {
                for (overscan in listOf(0f, 300f)) {
                    val vh = 900f
                    val got = layout.visiblePages(scroll, vh, overscan)
                    val top = scroll - overscan
                    val bottom = scroll + vh + overscan
                    val expected = pages.indices.filter { i ->
                        val pt = layout.pageTopPx(i)
                        val pb = pt + layout.pageHeightPx(i)
                        pb >= top && pt <= bottom
                    }
                    if (expected.isEmpty()) {
                        assertTrue("expected empty at $scroll", got.isEmpty())
                    } else {
                        assertEquals("first at scroll=$scroll", expected.first(), got.first)
                        assertEquals("last at scroll=$scroll", expected.last(), got.last)
                    }
                }
                scroll += 97f  // deliberately not a page multiple
            }
        }
    }

    @Test
    fun `scrolling past the end yields no pages`() {
        val layout = DocLayout(List(4) { letter }, Rotation.R0, scale = 1f, gapPx = 0f)
        assertTrue(layout.visiblePages(layout.contentHeightPx + 500f, 800f).isEmpty())
    }

    @Test
    fun `crop box origin is subtracted so page corners map to rect corners`() {
        val w = 612f
        val h = 792f
        // Bottom-left of the crop box -> bottom-left of the on-screen rect.
        val bl = pageToView(Pt(10f, 20f), offsetPage, Rotation.R0, w, h)
        assertEquals(0f, bl.x, EPS)
        assertEquals(h, bl.y, EPS)
        // Top-left of the crop box -> top-left of the rect.
        val tl = pageToView(Pt(10f, 812f), offsetPage, Rotation.R0, w, h)
        assertEquals(0f, tl.x, EPS)
        assertEquals(0f, tl.y, EPS)
    }

    @Test
    fun `page and view transforms round trip at every rotation`() {
        val probes = listOf(Pt(10f, 20f), Pt(300f, 400f), Pt(622f, 812f), Pt(120f, 700f))
        for (rot in Rotation.entries) {
            val w = offsetPage.displayWidthPt(rot)
            val h = offsetPage.displayHeightPt(rot)
            for (p in probes) {
                val v = pageToView(p, offsetPage, rot, w, h)
                val back = viewToPage(v, offsetPage, rot, w, h)
                assertEquals("x at $rot", p.x, back.x, EPS)
                assertEquals("y at $rot", p.y, back.y, EPS)
            }
        }
    }

    @Test
    fun `rotating 90 sends the page's top-left corner to the top-right`() {
        val w = letter.displayWidthPt(Rotation.R90)  // 792
        val h = letter.displayHeightPt(Rotation.R90) // 612
        assertEquals(792f, w, EPS)
        assertEquals(612f, h, EPS)
        val topLeft = pageToView(Pt(0f, 792f), letter, Rotation.R90, w, h)
        assertEquals(w, topLeft.x, EPS)
        assertEquals(0f, topLeft.y, EPS)
    }

    @Test
    fun `intrinsic page rotation composes with the user's rotation`() {
        val landscape = PageBox(612f, 792f, 0f, 0f, intrinsic = Rotation.R90)
        // The page already renders landscape, so with no user rotation the
        // display size is swapped.
        assertEquals(792f, landscape.displayWidthPt(Rotation.R0), EPS)
        assertEquals(612f, landscape.displayHeightPt(Rotation.R0), EPS)
        // Another 90 from the user puts it back upright (180 total).
        assertEquals(612f, landscape.displayWidthPt(Rotation.R90), EPS)
        assertEquals(792f, landscape.displayHeightPt(Rotation.R90), EPS)
    }

    @Test
    fun `intrinsic rotation moves overlays too`() {
        val landscape = PageBox(612f, 792f, 0f, 0f, intrinsic = Rotation.R90)
        val w = landscape.displayWidthPt(Rotation.R0)
        val h = landscape.displayHeightPt(Rotation.R0)
        // Page-space top-left lands top-right, exactly as an explicit R90 would.
        val p = pageToView(Pt(0f, 792f), landscape, Rotation.R0, w, h)
        assertEquals(w, p.x, EPS)
        assertEquals(0f, p.y, EPS)
    }

    @Test
    fun `rect mapping is normalised regardless of corner order`() {
        val r = rectToView(Box(100f, 200f, 300f, 400f), letter, Rotation.R0, 612f, 792f)
        assertTrue(r.left < r.right)
        assertTrue(r.top < r.bottom)
        assertEquals(100f, r.left, EPS)
        assertEquals(300f, r.right, EPS)
        // y flips: page y=400 is nearer the top of the screen.
        assertEquals(792f - 400f, r.top, EPS)
        assertEquals(792f - 200f, r.bottom, EPS)
    }

    @Test
    fun `fit page never overflows either axis and fit width fills it`() {
        val vw = 1080f
        val vh = 1920f
        val fitPage = resolveScale(Zoom.FitPage, letter, Rotation.R0, vw, vh)
        assertTrue(letter.widthPt * fitPage <= vw + EPS)
        assertTrue(letter.heightPt * fitPage <= vh + EPS)

        val fitWidth = resolveScale(Zoom.FitWidth, letter, Rotation.R0, vw, vh)
        assertEquals(vw, letter.widthPt * fitWidth, 0.1f)
    }

    @Test
    fun `tiles map into unrotated space and stay inside the page`() {
        val displayW = 1000
        val displayH = 1400
        for (rot in Rotation.entries) {
            val natW = if (rot.swaps) displayH else displayW
            val natH = if (rot.swaps) displayW else displayH
            var col = 0
            while (col * 512 < displayW) {
                var row = 0
                while (row * 512 < displayH) {
                    val left = col * 512
                    val top = row * 512
                    val w = minOf(512, displayW - left)
                    val h = minOf(512, displayH - top)
                    val nat = tileToNatural(rot, natW, natH, left, top, w, h)

                    assertTrue("$rot tile x in range", nat.x >= 0 && nat.x + nat.w <= natW)
                    assertTrue("$rot tile y in range", nat.y >= 0 && nat.y + nat.h <= natH)
                    // A quarter turn swaps the tile's own dimensions too.
                    if (rot.swaps) {
                        assertEquals("$rot tile w", h, nat.w)
                        assertEquals("$rot tile h", w, nat.h)
                    } else {
                        assertEquals("$rot tile w", w, nat.w)
                        assertEquals("$rot tile h", h, nat.h)
                    }
                    row++
                }
                col++
            }
        }
    }

    @Test
    fun `tiles cover the page exactly once`() {
        val displayW = 1000
        val displayH = 1400
        // Total mapped area must equal the page area: no gaps, no overlap.
        for (rot in Rotation.entries) {
            val natW = if (rot.swaps) displayH else displayW
            val natH = if (rot.swaps) displayW else displayH
            var area = 0L
            val seen = HashSet<Pair<Int, Int>>()
            var col = 0
            while (col * 512 < displayW) {
                var row = 0
                while (row * 512 < displayH) {
                    val left = col * 512
                    val top = row * 512
                    val w = minOf(512, displayW - left)
                    val h = minOf(512, displayH - top)
                    val nat = tileToNatural(rot, natW, natH, left, top, w, h)
                    area += nat.w.toLong() * nat.h
                    assertTrue("$rot duplicate tile origin", seen.add(nat.x to nat.y))
                    row++
                }
                col++
            }
            assertEquals("$rot covered area", displayW.toLong() * displayH, area)
        }
    }

    @Test
    fun `the top-left tile lands where the rotation sends it`() {
        // Display top-left of a 90-degree page comes from the natural
        // bottom-left corner, mirroring pageToView's R90 branch.
        val nat = tileToNatural(Rotation.R90, naturalWidth = 1400, naturalHeight = 1000, 0, 0, 512, 512)
        assertEquals(0, nat.x)
        assertEquals(1000 - 512, nat.y)
        assertEquals(512, nat.w)
        assertEquals(512, nat.h)
    }

    @Test
    fun `zoom keys survive a round trip through storage`() {
        for (z in listOf(Zoom.FitPage, Zoom.FitWidth, Zoom.Factor(1.75f))) {
            assertEquals(z, Zoom.from(z.key))
        }
        // Unknown values fall back to FitPage, the desktop's default.
        assertEquals(Zoom.FitPage, Zoom.from("nonsense"))
        assertEquals(Zoom.FitPage, Zoom.from(null))
    }
}
