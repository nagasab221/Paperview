package com.paperview.mobile.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.util.LruCache

/** One thing drawn on a page. */
sealed interface PageItem {
    /** Lines [fromLine, toLine) of a block's layout, drawn at [y]. */
    class Text(val block: Int, val layout: StaticLayout, val fromLine: Int, val toLine: Int, val y: Float) : PageItem
    class Image(val block: Int, val id: Int, val rect: RectF) : PageItem
    class Rule(val y: Float) : PageItem
}

/** A page, and where in the book it starts (block, character within it). */
class Page(val items: List<PageItem>, val block: Int, val char: Int)

/** Sets a run's typeface (works back to API 26, unlike TypefaceSpan(Typeface)). */
private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = face }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = face }
}

/**
 * Lays a book's blocks out into pages of [width] × [height] pixels. Each block
 * becomes a StaticLayout (real hyphenation and justification), whose lines are
 * then sliced into pages. A heading isn't left alone at the bottom of a page,
 * nor a paragraph's first line.
 */
class Paginator(
    private val context: Context,
    private val content: Content.Text,
    private val style: ReadingStyle,
    val width: Int,
    val height: Int,
) {
    private val density = context.resources.displayMetrics.density
    private val fontScale = context.resources.configuration.fontScale
    val margin = style.margins.dp * density
    val textWidth = (width - 2 * margin).toInt().coerceAtLeast(80)
    /** Shared by every layout; its colour is set at draw time. */
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    init {
        // Not in an apply{}: TextPaint has its own `density` and `style`.
        paint.typeface = style.font.typeface(context, false, false)
        paint.textSize = style.size * style.font.scale * density * fontScale
    }
    /**
     * Line height from the text size, not the face's own metrics: Literata's
     * built-in leading is far taller than Noto's, so a multiple of it isn't
     * the same rhythm from one face to the next.
     */
    private val lineHeight = paint.textSize * style.spacing.mult
    /** What each line gets added to the face's natural spacing. */
    private val lineAdd = lineHeight - paint.fontSpacing

    private val faces = HashMap<Int, Typeface>()

    private fun face(bold: Boolean, italic: Boolean): Typeface =
        faces.getOrPut((if (bold) 1 else 0) + (if (italic) 2 else 0)) { style.font.typeface(context, bold, italic) }

    private fun layoutOf(runs: List<Run>, heading: Int?, indent: Boolean): StaticLayout {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val start = sb.length
            sb.append(r.text)
            val bold = r.bold || heading != null
            if (bold || r.italic) sb.setSpan(FaceSpan(face(bold, r.italic)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (heading != null) {
            val mul = when (heading) { 1 -> 1.45f; 2 -> 1.28f; 3 -> 1.14f; else -> 1.05f }
            sb.setSpan(RelativeSizeSpan(mul), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } else if (indent) {
            sb.setSpan(LeadingMarginSpan.Standard((paint.textSize * 1.4f).toInt(), 0), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val b = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, textWidth)
            .setAlignment(if (heading != null && heading <= 2) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(if (heading != null) lineAdd * 0.5f else lineAdd, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        if (style.justify && heading == null && android.os.Build.VERSION.SDK_INT >= 26) {
            b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return b.build()
    }

    /** An image's size on the page: as large as fits, never upscaled past 2×. */
    private fun imageRect(id: Int, maxH: Float): RectF? {
        val bytes = content.images.getOrNull(id) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val w0 = o.outWidth * density
        val h0 = o.outHeight * density
        val s = minOf(textWidth / w0, maxH / h0, 2f).coerceAtMost(minOf(textWidth / w0, maxH / h0))
        val w = w0 * s
        val h = h0 * s
        val x = margin + (textWidth - w) / 2
        return RectF(x, 0f, x + w, h)
    }

    /**
     * Pages, handed to [emit] in batches as they're made (so a big book opens
     * before it's all laid out); stops early when [cancelled].
     */
    fun run(emit: (List<Page>) -> Unit, cancelled: () -> Boolean) {
        val pages = ArrayList<Page>()
        var batch = ArrayList<Page>()
        var items = ArrayList<PageItem>()
        var y = 0f
        var startBlock = 0
        var startChar = 0
        val h = height.toFloat()
        val spaced = !style.indent

        fun mark(block: Int, char: Int) {
            if (items.isEmpty()) { startBlock = block; startChar = char }
        }
        fun newPage() {
            if (items.isNotEmpty()) {
                val p = Page(items, startBlock, startChar)
                pages.add(p)
                batch.add(p)
                if (batch.size >= 20) { emit(batch); batch = ArrayList() }
            }
            items = ArrayList()
            y = 0f
        }

        var prevPara = false
        for ((i, block) in content.blocks.withIndex()) {
            if (cancelled()) return
            when (block) {
                Block.PageBreak -> { newPage(); prevPara = false }
                Block.Rule -> {
                    if (y + lineHeight > h) newPage()
                    mark(i, 0)
                    items.add(PageItem.Rule(y + lineHeight / 2))
                    y += lineHeight
                    prevPara = false
                }
                is Block.Image -> {
                    val r = imageRect(block.id, h * 0.94f) ?: continue
                    if (y > 0 && y + r.height() > h) newPage()
                    mark(i, 0)
                    items.add(PageItem.Image(i, block.id, RectF(r.left, y, r.right, y + r.height())))
                    y += r.height() + lineHeight * 0.5f
                    prevPara = false
                }
                is Block.Heading, is Block.Para -> {
                    val heading = (block as? Block.Heading)?.level
                    val runs = (block as? Block.Heading)?.runs ?: (block as Block.Para).runs
                    val layout = layoutOf(runs, heading, indent = style.indent && heading == null && prevPara)
                    val count = layout.lineCount
                    if (count == 0) continue
                    val gap = when {
                        y == 0f -> 0f
                        heading != null -> lineHeight * 1.1f
                        spaced -> lineHeight * 0.55f
                        else -> 0f
                    }
                    y += gap
                    // A heading keeps its first lines of text company.
                    val need = if (heading != null) layout.height + lineHeight * 2.5f else 0f
                    if (heading != null && y > 0 && y + need > h) newPage()
                    var line = 0
                    while (line < count) {
                        val top = layout.getLineTop(line)
                        var end = line
                        while (end < count && layout.getLineBottom(end) - top <= h - y + 0.5f) end++
                        // Don't strand a paragraph's first line at the foot of a page.
                        if (heading == null && line == 0 && end == 1 && count > 1 && y > 0) end = 0
                        if (end == line) {
                            if (y > 0) { newPage(); continue }
                            end = line + 1 // a line taller than the page
                        }
                        mark(i, layout.getLineStart(line))
                        items.add(PageItem.Text(i, layout, line, end, y))
                        y += layout.getLineBottom(end - 1) - top
                        line = end
                        if (line < count) newPage()
                    }
                    // A layout's last line gets no extra spacing; give it its share.
                    y += lineAdd
                    if (heading != null) y += lineHeight * 0.45f
                    prevPara = heading == null
                }
            }
        }
        newPage()
        if (batch.isNotEmpty()) emit(batch)
        if (pages.isEmpty()) emit(listOf(Page(emptyList(), 0, 0)))
    }
}

/** Decoded book images, kept for the pages near you. */
class ImageCache(private val images: List<ByteArray>) {
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun get(id: Int, targetWidth: Int): Bitmap? {
        cache.get(id)?.let { return it }
        val bytes = images.getOrNull(id) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(id, bmp)
        return bmp
    }
}
