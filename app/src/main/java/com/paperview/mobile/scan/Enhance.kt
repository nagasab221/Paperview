package com.paperview.mobile.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a captured page is cleaned up on its way into a PDF. */
enum class ScanFilter(val key: String, val label: String, val note: String) {
    Original("original", "Original", "The photograph exactly as taken"),
    Document("document", "Document", "Flattens shadows and whitens the paper"),
    Greyscale("greyscale", "Greyscale", "Flattened, then neutral grey"),
    Ink("ink", "Ink", "Black on white, for text you will print"),
    ;

    companion object {
        fun from(key: String?): ScanFilter = entries.firstOrNull { it.key == key } ?: Document
    }
}

/**
 * Turning a photograph of a page into something that looks scanned.
 *
 * ML Kit finds the page and corrects the perspective, which is the hard part,
 * but it hands back the photograph — grey where the light fell off, darker
 * still where your hand shadowed it. That is the whole difference between a
 * scan and a snapshot, and it is fixed by one idea: **divide the image by its
 * own background.** Estimate how bright the paper is at each point, then scale
 * every pixel so that paper becomes white. Uneven lighting is a smooth,
 * low-frequency thing, so the estimate can live on a thumbnail and still be
 * right to within a shade.
 *
 * Everything here works on a plain ARGB `IntArray`, with no Android types, so
 * the arithmetic can be tested on the desktop rather than guessed at.
 */
object Enhance {

    /** Long edge of the thumbnails the slow-varying estimates are computed on. */
    internal const val SMALL_EDGE = 384

    /** Sauvola's sensitivity, and the dynamic range it measures deviation against. */
    private const val SAUVOLA_K = 0.2f
    private const val SAUVOLA_R = 128f

    /** Enough to keep a highlighter visible, not enough to look lurid. */
    private const val SATURATION = 1.18f

    /**
     * How far below the page's own paper level a local estimate is still
     * believed to be paper.
     *
     * Dividing by the background assumes everything *is* paper, so a diagram,
     * a photograph or a filled table on the page — a large dark region the
     * estimate follows faithfully — gets divided by itself and comes out pure
     * white. Lecture notes lose their figures entirely. Anything darker than
     * this fraction of the page's paper is taken to be an object rather than
     * shadow, and the correction there is capped instead of completed.
     */
    private const val MIN_PAPER_RATIO = 0.5f

    /** Ink darker than the darkest 2% is clipped to black, paper above 99.5% to white. */
    private const val BLACK_PERCENTILE = 0.02f
    private const val WHITE_PERCENTILE = 0.995f

    /**
     * Cleans [pixels] in place. Dimensions are in pixels; the array is ARGB,
     * row-major, as `Bitmap.getPixels` produces.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, filter: ScanFilter) {
        if (filter == ScanFilter.Original) return
        if (width <= 0 || height <= 0 || pixels.size < width * height) return

        val sw = smallWidth(width, height)
        val sh = smallHeight(width, height)

        val floor = paperLevel(pixels, width, height) * MIN_PAPER_RATIO
        flatten(pixels, width, height, background(pixels, width, height, sw, sh), sw, sh, floor)
        applyLevels(pixels, width, height, levels(pixels, width, height), grey = filter != ScanFilter.Document)

        when (filter) {
            ScanFilter.Document -> saturate(pixels, width, height, SATURATION)
            ScanFilter.Ink -> binarise(pixels, width, height, sw, sh)
            else -> Unit
        }
    }

    // ── Estimating the paper ────────────────────────────────────────────────

    internal fun smallWidth(w: Int, h: Int): Int = max(1, (w / shrink(w, h)).roundToInt())
    internal fun smallHeight(w: Int, h: Int): Int = max(1, (h / shrink(w, h)).roundToInt())

    private fun shrink(w: Int, h: Int): Float = max(1f, max(w, h).toFloat() / SMALL_EDGE)

    /**
     * How bright the paper is across the page, as a thumbnail.
     *
     * Each cell takes the **brightest** pixel of its block rather than the
     * average, because paper is the brightest thing in any small patch of a
     * page. Averaging would let a dense paragraph drag the estimate down, and
     * dividing by that would wash the paragraph out — the text would fade
     * exactly where there is most of it. Blurring afterwards turns the maxima
     * into the smooth surface the light actually formed.
     */
    internal fun background(pixels: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray {
        val small = IntArray(sw * sh)
        for (y in 0 until h) {
            val row = y * w
            val base = (y * sh / h) * sw
            for (x in 0 until w) {
                val i = base + x * sw / w
                val l = luma(pixels[row + x])
                if (l > small[i]) small[i] = l
            }
        }
        // Widen the bright areas before smoothing. Taking the maximum of a
        // block only finds paper if the block is big enough to contain some;
        // on a page of dense text, or whenever the image is small enough that
        // a block is barely a pixel, whole cells can be nothing but ink. A
        // dilation first lets the surrounding paper spill over those cells, so
        // the estimate stays an estimate of paper at any size.
        return boxBlur(dilate(small, sw, sh, max(2, sw / 128)), sw, sh, max(2, sw / 20))
    }

    /** Separable maximum filter: each cell takes the brightest within reach. */
    internal fun dilate(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        val mid = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var best = 0
                for (i in x - radius..x + radius) {
                    val v = src[row + i.coerceIn(0, w - 1)]
                    if (v > best) best = v
                }
                mid[row + x] = best
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var best = 0
                for (i in y - radius..y + radius) {
                    val v = mid[i.coerceIn(0, h - 1) * w + x]
                    if (v > best) best = v
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    /** Separable box blur with a running sum, so cost doesn't grow with radius. */
    internal fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        val window = radius * 2 + 1
        val mid = IntArray(w * h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -radius..radius) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                mid[row + x] = sum / window
                sum += src[row + (x + radius + 1).coerceIn(0, w - 1)] -
                    src[row + (x - radius).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -radius..radius) sum += mid[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / window
                sum += mid[(y + radius + 1).coerceIn(0, h - 1) * w + x] -
                    mid[(y - radius).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    /** Reads the thumbnail at a full-resolution position, interpolating between cells. */
    internal fun sample(small: IntArray, sw: Int, sh: Int, x: Int, y: Int, w: Int, h: Int): Float {
        if (sw == 1 && sh == 1) return small[0].toFloat()
        val fx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
        val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, sw - 1)
        val y1 = min(y0 + 1, sh - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = small[y0 * sw + x0] * (1 - tx) + small[y0 * sw + x1] * tx
        val bottom = small[y1 * sw + x0] * (1 - tx) + small[y1 * sw + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    // ── The passes ──────────────────────────────────────────────────────────

    /** How bright the paper is on this page, as a whole: a high percentile. */
    internal fun paperLevel(pixels: IntArray, w: Int, h: Int): Float {
        val histogram = IntArray(256)
        for (i in 0 until w * h) histogram[luma(pixels[i])]++
        return percentile(histogram, w * h, 0.9f).toFloat().coerceAtLeast(1f)
    }

    /**
     * Scales every pixel so the paper under it becomes white.
     *
     * [floor] stops the correction running away where the "background" is too
     * dark to be paper at all — see [MIN_PAPER_RATIO].
     */
    internal fun flatten(pixels: IntArray, w: Int, h: Int, bg: IntArray, sw: Int, sh: Int, floor: Float = 1f) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val gain = 255f / sample(bg, sw, sh, x, y, w, h).coerceAtLeast(max(1f, floor))
                val p = pixels[row + x]
                pixels[row + x] = argb(
                    scale(p ushr 16 and 0xFF, gain),
                    scale(p ushr 8 and 0xFF, gain),
                    scale(p and 0xFF, gain),
                )
            }
        }
    }

    /**
     * A 256-entry curve pulling the darkest ink to black and the paper to white.
     *
     * Percentiles rather than the extremes, so one dark speck or one blown
     * highlight can't decide the whole page's contrast.
     */
    internal fun levels(pixels: IntArray, w: Int, h: Int): IntArray {
        val histogram = IntArray(256)
        for (i in 0 until w * h) histogram[luma(pixels[i])]++
        val total = w * h
        val low = percentile(histogram, total, BLACK_PERCENTILE)
        val high = percentile(histogram, total, WHITE_PERCENTILE)
        val lut = IntArray(256)
        if (high <= low) {
            for (v in 0..255) lut[v] = v
            return lut
        }
        val span = (high - low).toFloat()
        for (v in 0..255) lut[v] = (((v - low) / span) * 255f).roundToInt().coerceIn(0, 255)
        return lut
    }

    internal fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt().coerceIn(0, total)
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen >= target) return v
        }
        return 255
    }

    private fun applyLevels(pixels: IntArray, w: Int, h: Int, lut: IntArray, grey: Boolean) {
        for (i in 0 until w * h) {
            val p = pixels[i]
            pixels[i] = if (grey) {
                val v = lut[luma(p)]
                argb(v, v, v)
            } else {
                argb(lut[p ushr 16 and 0xFF], lut[p ushr 8 and 0xFF], lut[p and 0xFF])
            }
        }
    }

    /** Pushes colour away from grey, so highlighter and red pen survive. */
    internal fun saturate(pixels: IntArray, w: Int, h: Int, factor: Float) {
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = p ushr 16 and 0xFF
            val g = p ushr 8 and 0xFF
            val b = p and 0xFF
            val grey = luma(p)
            pixels[i] = argb(
                (grey + (r - grey) * factor).roundToInt().coerceIn(0, 255),
                (grey + (g - grey) * factor).roundToInt().coerceIn(0, 255),
                (grey + (b - grey) * factor).roundToInt().coerceIn(0, 255),
            )
        }
    }

    /**
     * Sauvola's local threshold: ink or paper, decided per pixel.
     *
     * A single cutoff for the whole page always loses something — faint pencil
     * in one corner, or a grey cast in another. Sauvola compares each pixel to
     * its neighbourhood's mean, pulled down where that neighbourhood is flat
     * (blank paper, so keep it white) and relaxed where it varies (text, so let
     * the strokes through). The statistics are gathered on the thumbnail: the
     * threshold surface is meant to be smooth, and the window that implies at
     * full size is about the width of a few characters, which is the scale it
     * wants anyway.
     */
    internal fun binarise(pixels: IntArray, w: Int, h: Int, sw: Int, sh: Int) {
        val sum = IntArray(sw * sh)
        val sumSquares = IntArray(sw * sh)
        val count = IntArray(sw * sh)
        for (y in 0 until h) {
            val row = y * w
            val base = (y * sh / h) * sw
            for (x in 0 until w) {
                val i = base + x * sw / w
                val l = luma(pixels[row + x])
                sum[i] += l
                sumSquares[i] += l * l
                count[i]++
            }
        }
        val mean = IntArray(sw * sh)
        val meanSquares = IntArray(sw * sh)
        for (i in 0 until sw * sh) {
            val n = max(1, count[i])
            mean[i] = sum[i] / n
            meanSquares[i] = sumSquares[i] / n
        }
        val radius = max(1, sw / 48)
        val meanBlur = boxBlur(mean, sw, sh, radius)
        val squareBlur = boxBlur(meanSquares, sw, sh, radius)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val m = sample(meanBlur, sw, sh, x, y, w, h)
                val ms = sample(squareBlur, sw, sh, x, y, w, h)
                val deviation = sqrt(max(0f, ms - m * m))
                val threshold = m * (1 + SAUVOLA_K * (deviation / SAUVOLA_R - 1))
                val v = if (luma(pixels[row + x]) <= threshold) 0 else 255
                pixels[row + x] = argb(v, v, v)
            }
        }
    }

    // ── Pixel helpers ───────────────────────────────────────────────────────

    /** Rec. 601 luma, in integer arithmetic. */
    internal fun luma(pixel: Int): Int =
        ((pixel ushr 16 and 0xFF) * 77 + (pixel ushr 8 and 0xFF) * 150 + (pixel and 0xFF) * 29) shr 8

    private fun scale(channel: Int, gain: Float): Int =
        (channel * gain).toInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
