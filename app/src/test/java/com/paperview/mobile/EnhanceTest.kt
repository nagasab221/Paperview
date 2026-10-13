package com.paperview.mobile

import com.paperview.mobile.scan.Enhance
import com.paperview.mobile.scan.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

private const val W = 300
private const val H = 400

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
private fun grey(v: Int) = argb(v, v, v)

/**
 * A photographed page: white paper lit unevenly, with a shadow falling across
 * one corner, and lines of dark text on it.
 */
private fun photographedPage(
    paper: Int = 235,
    shadow: Boolean = true,
    ink: Int = 60,
    colour: Boolean = false,
): IntArray {
    val pixels = IntArray(W * H)
    for (y in 0 until H) {
        for (x in 0 until W) {
            // Light falls off to the right, and a corner is shadowed hard.
            var level = paper - (x * 60 / W)
            if (shadow && x > W * 2 / 3 && y > H * 2 / 3) level -= 70
            pixels[y * W + x] = if (colour) argb(level, (level * 0.9f).toInt(), (level * 0.7f).toInt())
            else grey(level.coerceIn(0, 255))
        }
    }
    // Text: eight dark bands across the middle of the page, full width.
    for (band in 0 until 8) {
        val top = 60 + band * 34
        for (y in top until top + 12) {
            for (x in 30 until W - 30) {
                val shade = if (shadow && x > W * 2 / 3 && y > H * 2 / 3) ink - 25 else ink
                pixels[y * W + x] = grey(shade.coerceIn(0, 255))
            }
        }
    }
    return pixels
}

/** Luma of the pixels that are paper, i.e. outside the text bands. */
private fun paperPixels(pixels: IntArray): List<Int> {
    val out = ArrayList<Int>()
    for (y in 0 until H) {
        val isText = (60 until 60 + 8 * 34).contains(y) && ((y - 60) % 34) < 12
        if (isText) continue
        for (x in 0 until W) out.add(Enhance.luma(pixels[y * W + x]))
    }
    return out
}

private fun inkPixels(pixels: IntArray): List<Int> {
    val out = ArrayList<Int>()
    for (band in 0 until 8) {
        val top = 60 + band * 34
        for (y in top until top + 12) for (x in 40 until W - 40) out.add(Enhance.luma(pixels[y * W + x]))
    }
    return out
}

private fun spread(values: List<Int>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) }.toDouble() / values.size)
}

class EnhanceTest {

    @Test
    fun `the original filter changes nothing at all`() {
        val pixels = photographedPage()
        val before = pixels.copyOf()
        Enhance.apply(pixels, W, H, ScanFilter.Original)
        assertTrue(before.contentEquals(pixels))
    }

    @Test
    fun `uneven lighting is flattened out`() {
        // The point of the whole exercise: paper lit unevenly should come out
        // one shade, so the page reads as scanned rather than photographed.
        val pixels = photographedPage()
        val spreadBefore = spread(paperPixels(pixels))
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val spreadAfter = spread(paperPixels(pixels))

        assertTrue(
            "paper varied by %.1f before and %.1f after — it should be far flatter"
                .format(spreadBefore, spreadAfter),
            spreadAfter < spreadBefore / 3,
        )
    }

    @Test
    fun `paper ends up near white`() {
        val pixels = photographedPage()
        assertTrue("starts off grey", paperPixels(pixels).average() < 215)
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val after = paperPixels(pixels).average()
        assertTrue("paper came out at %.0f, expected near white".format(after), after > 245)
    }

    /** Mean luma of a rectangle, for comparing one patch of paper to another. */
    private fun patch(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { sum += Enhance.luma(pixels[y * W + x]); n++ }
        return sum / n
    }

    @Test
    fun `the shadowed corner is lifted to match the rest`() {
        val pixels = photographedPage()
        val before = abs(patch(pixels, 10, W / 2, 350, 390) - patch(pixels, W * 2 / 3 + 25, W - 10, 350, 390))
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val after = abs(patch(pixels, 10, W / 2, 350, 390) - patch(pixels, W * 2 / 3 + 25, W - 10, 350, 390))
        assertTrue(
            "the shadow was %.0f levels deep and is still %.0f".format(before, after),
            after < 8,
        )
    }

    @Test
    fun `a hard shadow edge leaves a seam, and this is how big it is`() {
        // Dividing by a blurred background assumes the light varies smoothly.
        // A synthetic shadow with a one-pixel edge breaks that assumption, and
        // the estimate is necessarily wrong where it does. Recorded rather than
        // hidden: real shadows have soft edges and never look like this, but if
        // this number starts growing, the estimate has got too coarse.
        val pixels = photographedPage()
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val straddling = abs(
            patch(pixels, 10, W / 2, 350, 390) - patch(pixels, W * 2 / 3, W, 350, 390),
        )
        assertTrue("the seam has grown to %.0f levels".format(straddling), straddling < 18)
    }

    @Test
    fun `text stays dark and does not wash out`() {
        // The failure mode of a naive background estimate: dense text drags the
        // average down, dividing by it brightens the text, and the page comes
        // out blank where it mattered most.
        val pixels = photographedPage()
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val ink = inkPixels(pixels).average()
        assertTrue("ink came out at %.0f — it has been washed out".format(ink), ink < 110)
    }

    @Test
    fun `contrast between ink and paper widens`() {
        val pixels = photographedPage()
        val before = paperPixels(pixels).average() - inkPixels(pixels).average()
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val after = paperPixels(pixels).average() - inkPixels(pixels).average()
        assertTrue("contrast went from %.0f to %.0f".format(before, after), after > before)
    }

    @Test
    fun `greyscale leaves no colour behind`() {
        val pixels = photographedPage(colour = true)
        Enhance.apply(pixels, W, H, ScanFilter.Greyscale)
        for (p in pixels) {
            val r = p ushr 16 and 0xFF
            val g = p ushr 8 and 0xFF
            val b = p and 0xFF
            assertEquals("channels should match", r, g)
            assertEquals("channels should match", g, b)
        }
    }

    @Test
    fun `document mode keeps colour`() {
        val pixels = photographedPage(colour = true)
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        val coloured = pixels.count { (it ushr 16 and 0xFF) != (it and 0xFF) }
        assertTrue("the page came out monochrome", coloured > pixels.size / 10)
    }

    @Test
    fun `ink mode produces only black and white`() {
        val pixels = photographedPage()
        Enhance.apply(pixels, W, H, ScanFilter.Ink)
        val values = pixels.map { Enhance.luma(it) }.toSet()
        assertEquals("expected two tones, got $values", setOf(0, 255), values)
    }

    @Test
    fun `ink mode puts the text in black and the paper in white`() {
        val pixels = photographedPage()
        Enhance.apply(pixels, W, H, ScanFilter.Ink)
        assertTrue("text should be black", inkPixels(pixels).average() < 40)
        assertTrue("paper should be white", paperPixels(pixels).average() > 240)
    }

    @Test
    fun `a large dark figure is not bleached into paper`() {
        // The risk of tracking the light too closely: a diagram or a photo on
        // the page is a big dark region, and a background estimate that
        // follows it will decide that is what paper looks like there and
        // brighten the whole thing away.
        val pixels = photographedPage(shadow = false)
        for (y in 200 until 330) for (x in 60 until 240) pixels[y * W + x] = grey(70)

        Enhance.apply(pixels, W, H, ScanFilter.Document)

        var sum = 0.0
        var n = 0
        for (y in 230 until 300) for (x in 90 until 210) { sum += Enhance.luma(pixels[y * W + x]); n++ }
        val figure = sum / n
        assertTrue("the figure came out at %.0f — it has been bleached".format(figure), figure < 140)
    }

    @Test
    fun `a page with no shadow at all survives unharmed`() {
        // Flattening an already-flat page must not invent texture.
        val pixels = photographedPage(paper = 250, shadow = false)
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        assertTrue("paper should stay flat", spread(paperPixels(pixels)) < 6)
        assertTrue("text should stay dark", inkPixels(pixels).average() < 110)
    }

    @Test
    fun `a blank page does not dissolve into noise`() {
        val pixels = IntArray(W * H) { grey(180) }
        Enhance.apply(pixels, W, H, ScanFilter.Document)
        assertTrue("a blank page should come out blank", spread(paperPixels(pixels)) < 3)
    }

    @Test
    fun `degenerate sizes are refused rather than crashing`() {
        Enhance.apply(IntArray(0), 0, 0, ScanFilter.Document)
        Enhance.apply(IntArray(4), 2, 2, ScanFilter.Ink)
        // Array smaller than the dimensions claim: must not read past the end.
        Enhance.apply(IntArray(3), 10, 10, ScanFilter.Document)
    }

    @Test
    fun `a one pixel image is handled`() {
        val one = intArrayOf(grey(100))
        Enhance.apply(one, 1, 1, ScanFilter.Document)
        assertNotEquals(0, one[0])
    }

    // ── The pieces, on their own ────────────────────────────────────────────

    @Test
    fun `the box blur averages without drifting`() {
        val flat = IntArray(50 * 50) { 128 }
        val blurred = Enhance.boxBlur(flat, 50, 50, 4)
        assertTrue("a flat field must stay flat", blurred.all { it == 128 })
    }

    @Test
    fun `the box blur smooths a step`() {
        val step = IntArray(50 * 50) { if ((it % 50) < 25) 0 else 255 }
        val blurred = Enhance.boxBlur(step, 50, 50, 5)
        val middle = blurred[25 * 50 + 25]
        assertTrue("the edge should be graded, got $middle", middle in 60..200)
    }

    @Test
    fun `the background follows the brightest pixels, not the average`() {
        // Half the page is covered in dense text; the paper estimate there must
        // still read as paper, or dividing by it would erase the text.
        val pixels = IntArray(W * H) { grey(240) }
        for (y in 0 until H) for (x in 0 until W / 2) {
            if ((x / 3) % 2 == 0) pixels[y * W + x] = grey(20)
        }
        val sw = Enhance.smallWidth(W, H)
        val sh = Enhance.smallHeight(W, H)
        val bg = Enhance.background(pixels, W, H, sw, sh)
        val dense = Enhance.sample(bg, sw, sh, W / 4, H / 2, W, H)
        assertTrue("estimate sagged to $dense over the text", dense > 200)
    }

    @Test
    fun `percentiles ignore outliers`() {
        val histogram = IntArray(256)
        histogram[0] = 5          // a few black specks
        histogram[200] = 990
        histogram[255] = 5        // a few blown highlights
        assertEquals(200, Enhance.percentile(histogram, 1000, 0.02f))
        assertEquals(200, Enhance.percentile(histogram, 1000, 0.9f))
    }

    @Test
    fun `sampling the thumbnail stays inside it`() {
        val small = IntArray(4 * 4) { it * 16 }
        // Corners and beyond must clamp rather than read out of bounds.
        Enhance.sample(small, 4, 4, 0, 0, W, H)
        Enhance.sample(small, 4, 4, W - 1, H - 1, W, H)
        Enhance.sample(small, 4, 4, W * 2, H * 2, W, H)
    }
}
