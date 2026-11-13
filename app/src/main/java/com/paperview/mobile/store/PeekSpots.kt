package com.paperview.mobile.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A saved place in a document to peek at — typically the answer key while
 * working through questions.
 *
 * Stored relative to a page rather than as raw pixels, so it still lands on
 * the same spot after the window or zoom changes: [yFrac] is how far down
 * [page] the top of the screen was (0 = page top, 1 = page bottom), [xFrac]
 * the horizontal scroll as a fraction of the content width, and [zoom] the
 * [com.paperview.mobile.pdf.Zoom] key in force when it was saved.
 */
data class PeekSpot(
    val page: Int,
    val yFrac: Float,
    val xFrac: Float,
    val zoom: String,
)

/**
 * One peek spot per document, keyed by the same document key as [Recents].
 * Tab-separated lines in a single preference: `page \t yFrac \t xFrac \t zoom \t uri`,
 * with the uri last so it can never be split by a stray separator.
 */
object PeekSpots {
    private const val LIMIT = 200
    private val lock = Mutex()

    suspend fun get(prefs: Prefs, uri: String): PeekSpot? =
        decode(prefs.peekSpotsRaw.first())[uri]

    suspend fun put(prefs: Prefs, uri: String, spot: PeekSpot) = lock.withLock {
        val all = LinkedHashMap(decode(prefs.peekSpotsRaw.first()))
        all.remove(uri)
        all[uri] = spot
        // Oldest first in the map, so trimming drops the least recently saved.
        while (all.size > LIMIT) all.remove(all.keys.first())
        prefs.setPeekSpotsRaw(encode(all))
    }

    suspend fun remove(prefs: Prefs, uri: String) = lock.withLock {
        val all = LinkedHashMap(decode(prefs.peekSpotsRaw.first()))
        if (all.remove(uri) != null) prefs.setPeekSpotsRaw(encode(all))
    }

    internal fun encode(all: Map<String, PeekSpot>): String =
        all.entries.joinToString("\n") { (uri, s) ->
            "${s.page}\t${s.yFrac}\t${s.xFrac}\t${s.zoom}\t$uri"
        }

    internal fun decode(raw: String): Map<String, PeekSpot> {
        val out = LinkedHashMap<String, PeekSpot>()
        raw.lineSequence().forEach { line ->
            val f = line.split('\t', limit = 5)
            if (f.size < 5 || f[4].isBlank()) return@forEach
            val page = f[0].toIntOrNull() ?: return@forEach
            val y = f[1].toFloatOrNull() ?: return@forEach
            val x = f[2].toFloatOrNull() ?: return@forEach
            out[f[4]] = PeekSpot(page, y, x, f[3])
        }
        return out
    }
}
