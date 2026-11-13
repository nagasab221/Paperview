package com.paperview.mobile

import com.paperview.mobile.store.PeekSpot
import com.paperview.mobile.store.PeekSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeekSpotsTest {

    @Test
    fun roundTripsSpotsPerDocument() {
        val all = linkedMapOf(
            "content://a/doc.pdf" to PeekSpot(41, 0.62f, 0f, "fit_width"),
            "/data/shelf/b.pdf" to PeekSpot(0, -0.01f, 0.25f, "factor:2.5"),
        )
        assertEquals(all, PeekSpots.decode(PeekSpots.encode(all)))
    }

    @Test
    fun uriWithTabsSurvivesBecauseItIsLast() {
        val all = mapOf("content://x/a\tb.pdf" to PeekSpot(3, 0.5f, 0f, "fit_page"))
        assertEquals(all, PeekSpots.decode(PeekSpots.encode(all)))
    }

    @Test
    fun skipsBrokenLines() {
        val raw = "garbage\n1\tnot-a-number\t0\tfit_page\turi\n2\t0.5\t0\tfit_page\tgood"
        val out = PeekSpots.decode(raw)
        assertEquals(setOf("good"), out.keys)
        assertTrue(PeekSpots.decode("").isEmpty())
    }
}
