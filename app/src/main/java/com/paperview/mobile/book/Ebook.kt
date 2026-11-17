package com.paperview.mobile.book

import java.io.File

/**
 * Ebooks. Every format is read into one simple model — headings, paragraphs
 * of styled runs, images, page breaks and a chapter list — which the reader
 * lays out into pages. Comics (CBZ) are a list of page images. A port of the
 * desktop build's `src/ebook`, so a book reads the same on both.
 *
 * Supported: EPUB, MOBI / AZW / AZW3 / PRC (without DRM), FB2 and FBZ, CBZ,
 * TXT and single HTML files.
 */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false)

sealed interface Block {
    data class Heading(val level: Int, val runs: List<Run>) : Block
    data class Para(val runs: List<Run>) : Block
    /** Index into [Content.Text.images]. */
    data class Image(val id: Int) : Block
    data object PageBreak : Block
    data object Rule : Block

    val text: String
        get() = when (this) {
            is Heading -> runs.joinToString("") { it.text }
            is Para -> runs.joinToString("") { it.text }
            else -> ""
        }
}

data class Chapter(val title: String, val block: Int)

sealed interface Content {
    /** Reflowable text, laid out into pages by the reader. */
    class Text(val blocks: List<Block>, val images: List<ByteArray>) : Content
    /** One image per page (comics), read on demand from the archive. */
    class Comic(val pages: List<String>) : Content
}

class Book(
    val title: String,
    val author: String?,
    val content: Content,
    val chapters: List<Chapter>,
) {
    val isComic: Boolean get() = content is Content.Comic

    /** The chapter a block belongs to. */
    fun chapterAt(block: Int): Chapter? = chapters.lastOrNull { it.block <= block }
}

class BookException(message: String) : Exception(message)

object Ebooks {
    /** Extensions PaperView reads as books. */
    val EXTENSIONS = listOf(
        "epub", "mobi", "azw", "azw3", "prc", "fb2", "fbz", "cbz", "txt", "html", "htm", "xhtml",
    )

    /** MIME types for the system picker and "open with". */
    val MIME_TYPES = arrayOf(
        "application/epub+zip",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook",
        "application/x-fictionbook+xml",
        "application/x-fictionbook",
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        "text/plain",
        "text/html",
    )

    fun isEbook(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fb2.zip") || EXTENSIONS.any { n.endsWith(".$it") }
    }

    private fun kind(name: String): String {
        val n = name.lowercase()
        if (n.endsWith(".fb2.zip")) return "fbz"
        return n.substringAfterLast('.', "")
    }

    /** Reads a whole book. Slow for big ones: call it off the main thread. */
    fun open(file: File, name: String = file.name): Book {
        val book = when (kind(name)) {
            "epub" -> Epub.open(file)
            "mobi", "azw", "azw3", "prc" -> Mobi.open(file)
            "fb2" -> Fb2.open(file.readBytes())
            "fbz" -> Fb2.open(Fb2.firstInZip(file))
            "cbz" -> Comic.open(file)
            "html", "htm", "xhtml" -> Text.openHtml(file)
            else -> Text.openTxt(file)
        }
        val title = book.title.trim().ifEmpty { name.substringBeforeLast('.').ifEmpty { "Untitled" } }
        val chapters = book.chapters.ifEmpty { chaptersFromHeadings(book.content) }
        return Book(title, book.author?.trim()?.ifEmpty { null }, book.content, chapters)
    }

    /** The cover image's bytes, if the book has one. */
    fun cover(file: File, name: String = file.name): ByteArray? = runCatching {
        when (kind(name)) {
            "epub" -> Epub.cover(file)
            "mobi", "azw", "azw3", "prc" -> Mobi.cover(file)
            "fb2" -> Fb2.cover(file.readBytes())
            "fbz" -> Fb2.cover(Fb2.firstInZip(file))
            "cbz" -> Comic.cover(file)
            else -> null
        }
    }.getOrNull()

    fun comicPage(file: File, entry: String): ByteArray? = Comic.page(file, entry)

    /** When a book has no table of contents, its headings make one. */
    private fun chaptersFromHeadings(content: Content): List<Chapter> {
        val text = content as? Content.Text ?: return emptyList()
        return text.blocks.mapIndexedNotNull { i, b ->
            if (b is Block.Heading && b.level <= 2) {
                b.text.trim().takeIf { it.isNotEmpty() }?.let { Chapter(it, i) }
            } else null
        }
    }
}

// ── Code pages ───────────────────────────────────────────────────────────────

private val CP1252_HIGH = charArrayOf(
    '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '\u008d', 'Ž',
    '\u008f', '\u0090', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '\u009d',
    'ž', 'Ÿ',
)

/** Windows-1252 bytes to text (old Kindle books and stray files). */
fun decodeCp1252(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v.toChar())
    }
    return sb.toString()
}

/** Windows-1251 (Cyrillic) — common in FB2 books. */
fun decodeCp1251(bytes: ByteArray): String = String(bytes, charset("windows-1251"))

/** UTF-8 if it is, otherwise Windows-1252. */
fun decodeText(bytes: ByteArray): String {
    val b = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        bytes.copyOfRange(3, bytes.size)
    } else bytes
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        decodeCp1252(b)
    }
}
