package com.paperview.mobile

import com.paperview.mobile.store.RecentEntry
import com.paperview.mobile.store.Recents
import org.junit.Assert.assertEquals
import org.junit.Test

private fun entry(uri: String, name: String, page: Int, ts: Long) =
    RecentEntry(uri = uri, name = name, page = page, zoom = "fit_page", rotation = 0, timestamp = ts)

class RecentsTest {

    @Test
    fun `an unaffected list is left exactly as it was`() {
        val list = listOf(entry("/a.pdf", "A", 3, 300), entry("/b.pdf", "B", 1, 200))
        assertEquals(list, Recents.collapse(list, emptyMap()))
    }

    @Test
    fun `duplicates of one document collapse onto the page last read`() {
        // The reported bug, in list form: the same PDF shared from WhatsApp
        // three times was filed three times, each with its own reading
        // position, so reopening it never resumed where you were.
        val list = listOf(
            entry("/shelf/aaa.pdf", "ENT.pdf", 0, 300),
            entry("/shelf/bbb.pdf", "ENT.pdf", 15, 200),
            entry("/shelf/ccc.pdf", "ENT.pdf", 20, 100),
        )
        val moved = mapOf(
            "/shelf/aaa.pdf" to "/shelf/content.pdf",
            "/shelf/bbb.pdf" to "/shelf/content.pdf",
            "/shelf/ccc.pdf" to "/shelf/content.pdf",
        )

        val result = Recents.collapse(list, moved)

        assertEquals(1, result.size)
        assertEquals("/shelf/content.pdf", result[0].uri)
        // The newest row is page 0 precisely because reopening reset it, so
        // the most recent position actually read is what survives.
        assertEquals(15, result[0].page)
        // It still sorts by when the document was last in front of you.
        assertEquals(300L, result[0].timestamp)
    }

    @Test
    fun `a document only ever opened at page one collapses to page one`() {
        val list = listOf(
            entry("/shelf/aaa.pdf", "Doc", 0, 300),
            entry("/shelf/bbb.pdf", "Doc", 0, 200),
        )
        val moved = mapOf("/shelf/aaa.pdf" to "/shelf/x.pdf", "/shelf/bbb.pdf" to "/shelf/x.pdf")

        val result = Recents.collapse(list, moved)

        assertEquals(1, result.size)
        assertEquals(0, result[0].page)
    }

    @Test
    fun `the real list from the phone collapses the way it should`() {
        // The shape of a real shelf taken off a device, with the names
        // changed: four rows for one PDF shared from WhatsApp four times,
        // two for another, each carrying its own reading position.
        val list = listOf(
            entry("/shelf/cb4cf5.pdf", "Lecture_Notes_A.pdf", 75, 1791523082480),
            entry("/shelf/48cf9a.pdf", "Lecture_Notes_B.pdf", 0, 1791520754349),
            entry("/shelf/9b9de0.pdf", "Lecture_Notes_B.pdf", 15, 1791520697448),
            entry("/shelf/086e7e.pdf", "Lecture_Notes_B.pdf", 20, 1791520695378),
            entry("/shelf/7297d5.pdf", "Seminar_Slides.pdf", 43, 1791520688171),
            entry("/shelf/9fe097.pdf", "Lecture_Notes_B.pdf", 20, 1791479164952),
            entry("/shelf/7c1e10.pdf", "Reference_Chapter.pdf", 1, 1791384843043),
            entry("/shelf/50ff28.pdf", "Lecture_Notes_A.pdf", 46, 1791384840278),
        )
        val ent = "/shelf/notes-b.pdf"
        val ward = "/shelf/notes-a.pdf"
        val moved = mapOf(
            "/shelf/48cf9a.pdf" to ent,
            "/shelf/9b9de0.pdf" to ent,
            "/shelf/086e7e.pdf" to ent,
            "/shelf/9fe097.pdf" to ent,
            "/shelf/cb4cf5.pdf" to ward,
            "/shelf/50ff28.pdf" to ward,
        )

        val result = Recents.collapse(list, moved)

        assertEquals("eight rows for four documents", 4, result.size)
        assertEquals(
            listOf("Lecture_Notes_A.pdf", "Lecture_Notes_B.pdf", "Seminar_Slides.pdf", "Reference_Chapter.pdf"),
            result.map { it.name },
        )
        // Page 75 was the newest and a real position, so it stands.
        assertEquals(75, result[0].page)
        // Page 0 was the newest for this one, so the position it had before
        // the duplicate reset it is restored instead.
        assertEquals(15, result[1].page)
    }

    @Test
    fun `the surviving entry is the newest even when the list is out of order`() {
        val list = listOf(
            entry("/shelf/old.pdf", "Doc", 5, 100),
            entry("/shelf/new.pdf", "Doc", 42, 900),
        )
        val moved = mapOf(
            "/shelf/old.pdf" to "/shelf/same.pdf",
            "/shelf/new.pdf" to "/shelf/same.pdf",
        )

        val result = Recents.collapse(list, moved)

        assertEquals(1, result.size)
        assertEquals(42, result[0].page)
        assertEquals(900L, result[0].timestamp)
    }

    @Test
    fun `different documents are never merged`() {
        val list = listOf(
            entry("/shelf/aaa.pdf", "One.pdf", 1, 300),
            entry("/shelf/bbb.pdf", "Two.pdf", 2, 200),
        )
        val moved = mapOf(
            "/shelf/aaa.pdf" to "/shelf/one-content.pdf",
            "/shelf/bbb.pdf" to "/shelf/two-content.pdf",
        )

        val result = Recents.collapse(list, moved)

        assertEquals(2, result.size)
        assertEquals(listOf("One.pdf", "Two.pdf"), result.map { it.name })
    }

    @Test
    fun `an entry already pointing at the new name still absorbs its duplicate`() {
        // Mid-upgrade state: one copy was already renamed, another wasn't.
        val list = listOf(
            entry("/shelf/content.pdf", "Doc", 7, 300),
            entry("/shelf/legacy.pdf", "Doc", 2, 200),
        )
        val moved = mapOf("/shelf/legacy.pdf" to "/shelf/content.pdf")

        val result = Recents.collapse(list, moved)

        assertEquals(1, result.size)
        assertEquals(7, result[0].page)
    }

    @Test
    fun `the list never grows past its limit`() {
        val list = (1..40).map { entry("/shelf/$it.pdf", "Doc $it", it, it.toLong()) }
        assertEquals(20, Recents.collapse(list, emptyMap()).size)
    }

    @Test
    fun `encoding survives a round trip through the stored format`() {
        val list = listOf(
            entry("/shelf/a.pdf", "Has\ttab and\nnewline", 3, 300),
            entry("content://x/y", "Plain.pdf", 0, 200),
        )
        val decoded = Recents.decode(Recents.encode(list))
        assertEquals(2, decoded.size)
        // Tabs and newlines would break the line format, so they're scrubbed.
        assertEquals("Has tab and newline", decoded[0].name)
        assertEquals("/shelf/a.pdf", decoded[0].uri)
        assertEquals(3, decoded[0].page)
        assertEquals("content://x/y", decoded[1].uri)
    }
}
