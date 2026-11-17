package com.paperview.mobile.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTest {
    @Test fun paragraphsHeadingsAndStyles() {
        val html = """<html><head><title>X</title><style>p{}</style></head><body>
            <h1 id="c1">Chapter&nbsp;One</h1>
            <p>It was a <i>dark</i> and <b>stormy</b>   night &amp; more&#8230;</p>
            <p class="calibre">Second <span style="font-style: italic">line</span></p>
            <hr/><mbp:pagebreak/></body></html>"""
        val blocks = Markup.blocksOf(html, false)
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is Block.Heading)
        assertEquals("Chapter One", blocks[0].text)
        assertEquals("It was a dark and stormy night & more…", blocks[1].text)
        val runs = (blocks[1] as Block.Para).runs
        assertTrue(runs.any { it.text == "dark" && it.italic })
        assertTrue(runs.any { it.text == "stormy" && it.bold })
        assertTrue((blocks[2] as Block.Para).runs.any { it.text == "line" && it.italic })
        assertEquals(Block.Rule, blocks[3])
        assertEquals(Block.PageBreak, blocks[4])
    }

    @Test fun survivesTagSoup() {
        val blocks = Markup.blocksOf("<p>open <b>never closed<p>next < 3 > 2 <!-- c --> end", false)
        assertEquals(2, blocks.size)
        assertEquals("next < 3 > 2 end", blocks[1].text)
    }

    @Test fun fb2TitlesAreHeadings() {
        val fb2 = """<FictionBook><description><title-info><book-title>B</book-title></title-info></description>
            <body><section><title><p>Part 1</p></title><p>Text <emphasis>here</emphasis>.</p>
            <section><title><p>Chapter A</p></title><empty-line/><p>More</p></section></section></body>
            <binary id="x">AAAA</binary></FictionBook>"""
        val kinds = Markup.blocksOf(fb2, true).map { if (it is Block.Heading) "h${it.level}:${it.text}" else it.text }
        assertEquals(listOf("h1:Part 1", "Text here.", "h2:Chapter A", "More"), kinds)
    }

    @Test fun fb2WithCover() {
        val png = java.util.Base64.getEncoder().encodeToString("\u0089PNGfake".toByteArray(Charsets.ISO_8859_1))
        val src = """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns:l="http://www.w3.org/1999/xlink">
            <description><title-info><author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
            <book-title>Kreutzer</book-title><coverpage><image l:href="#c.png"/></coverpage></title-info></description>
            <body><section><title><p>I</p></title><p>It was early spring.</p><image l:href="#c.png"/></section></body>
            <binary id="c.png" content-type="image/png">$png</binary></FictionBook>"""
        val book = Fb2.open(src.toByteArray())
        assertEquals("Kreutzer", book.title)
        assertEquals("Leo Tolstoy", book.author)
        val text = book.content as Content.Text
        assertEquals(3, text.blocks.size)
        assertArrayEquals("\u0089PNGfake".toByteArray(Charsets.ISO_8859_1), text.images.single())
        assertArrayEquals("\u0089PNGfake".toByteArray(Charsets.ISO_8859_1), Fb2.cover(src.toByteArray()))
    }

    @Test fun palmdocAndImageRefs() {
        val pair = 3 shl 3
        val enc = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), (0x80 or (pair shr 8)).toByte(), (pair and 0xFF).toByte(), 0xE1.toByte())
        assertEquals("abcabc a", String(Mobi.palmdoc(enc), Charsets.ISO_8859_1))
        assertEquals(102, Mobi.imageRecord("00003", 100))
        assertEquals(109, Mobi.imageRecord("kindle:embed:000A?mime=image/jpg", 100))
        assertEquals(null, Mobi.imageRecord("0", 100))
    }

    @Test fun epubWithTocAndCover() {
        val f = File.createTempFile("book", ".epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><dc:title>Pride and Prejudice</dc:title><dc:creator>Jane Austen</dc:creator>
                <meta name="cover" content="img"/></metadata><manifest>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch%202.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/cover.png" media-type="image/png"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                </manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/Text/ch1.xhtml", """<html><body><h1>Chapter 1</h1><p>It is a truth universally acknowledged.</p><img src="../Images/cover.png"/></body></html>""")
            put("OEBPS/Text/ch 2.xhtml", """<html><body><h1 id="two">Chapter 2</h1><p>Mr. Bennet was among the earliest.</p></body></html>""")
            put("OEBPS/Images/cover.png", "PNGDATA")
            put("OEBPS/nav.xhtml", """<html><body><nav epub:type="toc"><ol><li><a href="Text/ch1.xhtml">One</a></li>
                <li><a href="Text/ch%202.xhtml#two">Two</a></li></ol></nav></body></html>""")
        }
        val book = Ebooks.open(f, "pp.epub")
        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.author)
        // Chapter 2 starts after chapter 1's three blocks and a page break.
        assertEquals(listOf("One" to 0, "Two" to 4), book.chapters.map { it.title to it.block })
        assertEquals(Block.PageBreak, (book.content as Content.Text).blocks[3])
        val text = book.content as Content.Text
        assertTrue(text.blocks[2] is Block.Image)
        assertEquals("PNGDATA", String(Ebooks.cover(f, "pp.epub")!!))
        f.delete()
    }

    @Test fun comicPagesInNaturalOrder() {
        val f = File.createTempFile("comic", ".cbz")
        ZipOutputStream(f.outputStream()).use { z ->
            for (n in listOf("p10.jpg", "p2.jpg", "p1.jpg", "__MACOSX/p3.jpg", "notes.txt")) {
                z.putNextEntry(ZipEntry(n)); z.write(n.toByteArray()); z.closeEntry()
            }
        }
        val book = Ebooks.open(f, "c.cbz")
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), (book.content as Content.Comic).pages)
        f.delete()
    }

    @Test fun codePagesAndStyleRoundTrip() {
        assertEquals("“café”", decodeText(byteArrayOf(0x93.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), 0x94.toByte())))
        assertEquals("ok ✓", decodeText("ok ✓".toByteArray()))
        val s = ReadingStyle(font = BookFont.Garamond, size = 21f, spacing = Spacing.Relaxed, indent = false, colors = PageColors.Sepia, scroll = true, volumeKeys = true)
        assertEquals(s, ReadingStyle.decode(s.encode()))
        assertTrue(Ebooks.isEbook("x.FB2.zip") && Ebooks.isEbook("b.azw3") && !Ebooks.isEbook("a.pdf"))
    }
}
