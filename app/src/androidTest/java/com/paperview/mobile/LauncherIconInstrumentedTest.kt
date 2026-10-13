package com.paperview.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/**
 * Guards the themed-icon layer, the one a launcher uses when it tints every app
 * to a single colour.
 *
 * The system discards that drawable's own colours and paints every opaque pixel
 * in the theme's colour, so a layer built from large filled shapes collapses
 * into one featureless blob. That is exactly what happened while `<monochrome>`
 * pointed at the colour foreground. These checks fail on that drawable and pass
 * on a line-art one, so the mistake can't come back unnoticed.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconInstrumentedTest {

    /** The 108dp icon viewport, rendered 4x for sub-unit accuracy. */
    private val scale = 4
    private val side = 108 * scale

    private fun renderMonochrome(): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drawable = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_launcher_monochrome,
            null,
        )
        assertNotNull("the monochrome drawable should exist", drawable)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawable!!.setBounds(0, 0, side, side)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** True where the layer puts ink; only alpha matters, colour is replaced. */
    private fun Bitmap.inked(x: Int, y: Int): Boolean = (getPixel(x, y) ushr 24) > 8

    @Test
    fun theThemedLayerIsDeclaredSeparately() {
        // Without its own layer the launcher derives one from the colour icon,
        // which is the blob this whole file is about.
        if (Build.VERSION.SDK_INT < 33) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, null)
        assertTrue("the launcher icon should be adaptive", icon is AdaptiveIconDrawable)
        assertNotNull(
            "the adaptive icon should declare a monochrome layer",
            (icon as AdaptiveIconDrawable).monochrome,
        )
    }

    @Test
    fun thePageInteriorIsMostlyEmpty() {
        // The heart of the bug: tinted, a filled page body is a solid slab and
        // every detail drawn over it disappears. An outlined page leaves its
        // middle open, so the fold and the text hints still read.
        val bitmap = renderMonochrome()
        var inked = 0
        var total = 0
        // Well inside the page's own outline, so the stroke isn't counted.
        for (y in 36 * scale until 72 * scale) {
            for (x in 40 * scale until 68 * scale) {
                total++
                if (bitmap.inked(x, y)) inked++
            }
        }
        val fraction = inked.toFloat() / total
        assertTrue(
            "the page interior is $%.0f%% ink — that reads as a blob, not an icon"
                .format(fraction * 100),
            fraction < 0.5f,
        )
        // And it must not be empty either, or there's no icon at all.
        assertTrue("the page interior has no detail in it", fraction > 0.05f)
    }

    @Test
    fun theArtworkStaysInsideTheThemedSafeZone() {
        // A themed icon is scaled up more than the colour one: the spec asks for
        // the artwork to sit inside a 66dp circle, not the 72dp adaptive zone.
        val bitmap = renderMonochrome()
        val centre = 54f * scale
        val radius = 33f * scale
        var worst = 0f
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (!bitmap.inked(x, y)) continue
                val d = hypot(x + 0.5f - centre, y + 0.5f - centre)
                if (d > worst) worst = d
            }
        }
        assertTrue(
            "ink reaches %.1fdp from centre, past the %.0fdp safe radius"
                .format(worst / scale, radius / scale),
            worst <= radius,
        )
    }

    @Test
    fun theArtworkIsCentred() {
        val bitmap = renderMonochrome()
        var minX = side; var maxX = -1; var minY = side; var maxY = -1
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (!bitmap.inked(x, y)) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        assertTrue("nothing was drawn at all", maxX > 0)
        val cx = (minX + maxX) / 2f / scale
        val cy = (minY + maxY) / 2f / scale
        assertTrue("artwork centred at x=%.1f, should be 54".format(cx), kotlin.math.abs(cx - 54f) < 2f)
        assertTrue("artwork centred at y=%.1f, should be 54".format(cy), kotlin.math.abs(cy - 54f) < 2f)
    }

    /**
     * Not an assertion — writes the layer out so it can actually be looked at.
     * Numbers can tell you a drawable isn't a blob; they can't tell you it looks
     * like a document.
     */
    @Test
    fun dumpTheThemedLayerForInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = renderMonochrome()
        // Tinted the way a launcher would, on its own flat background.
        val shown = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(shown).apply {
            drawColor(0xFFD3E3FD.toInt())
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(
                    0xFF0B1F33.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_IN,
                )
            }
            drawBitmap(bitmap, 0f, 0f, paint)
        }
        val out = File(context.cacheDir, "themed_icon.png")
        out.outputStream().use { shown.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
    }
}
