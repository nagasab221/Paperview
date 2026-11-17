package com.paperview.mobile.book

import java.io.File
import java.util.zip.ZipFile

/** Raw result of a format reader, before titles and chapters are filled in. */
internal class Parsed(val title: String, val author: String?, val content: Content, val chapters: List<Chapter>)

private fun Parsed.asBook() = Book(title, author, content, chapters)

private fun ZipFile.bytes(name: String): ByteArray? =
    getEntry(name)?.let { e -> getInputStream(e).use { it.readBytes() } }

private fun ZipFile.text(name: String): String? = bytes(name)?.let(::decodeText)

private fun percentDecode(s: String): String {
    val out = java.io.ByteArrayOutputStream(s.length)
    val b = s.toByteArray()
    var i = 0
    while (i < b.size) {
        if (b[i] == '%'.code.toByte() && i + 2 < b.size) {
            val v = String(b, i + 1, 2).toIntOrNull(16)
            if (v != null) { out.write(v); i += 3; continue }
        }
        out.write(b[i].toInt())
        i++
    }
    return out.toString("UTF-8")
}

// ── EPUB ─────────────────────────────────────────────────────────────────────

/** EPUB 2 and 3: XHTML chapters, a package (OPF) and a table of contents. */
internal object Epub {
    /** `dir/../x.html#a` relative to [base] (a file inside the zip), normalised. */
    fun resolve(base: String, href: String): String {
        val h = percentDecode(href.substringBefore('#'))
        if (h.isEmpty()) return base
        val parts = if (base.contains('/')) base.substringBeforeLast('/').split('/').toMutableList() else mutableListOf()
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun fragment(href: String): String? = href.substringAfter('#', "").ifEmpty { null }

    private class Package(
        val title: String,
        val author: String?,
        /** id → (href inside the zip, media type, properties) */
        val manifest: Map<String, Triple<String, String, String>>,
        val spine: List<String>,
        val ncx: String?,
        val coverId: String?,
    )

    private fun pkg(zip: ZipFile): Package {
        val container = zip.text("META-INF/container.xml") ?: throw BookException("This EPUB is missing its container file")
        val opfPath = Markup.tokenize(container).firstNotNullOfOrNull {
            (it as? Token.Start)?.takeIf { s -> s.name == "rootfile" }?.attr("full-path")
        } ?: throw BookException("This EPUB doesn't say where its contents are")
        val opf = zip.text(opfPath) ?: throw BookException("This EPUB's package file is missing")
        var title = ""
        var author: String? = null
        val manifest = HashMap<String, Triple<String, String, String>>()
        val spine = ArrayList<String>()
        var ncx: String? = null
        var coverId: String? = null
        var inTag: String? = null
        for (t in Markup.tokenize(opf)) {
            when (t) {
                is Token.Start -> when (t.name) {
                    "title" -> if (title.isEmpty()) inTag = "title"
                    "creator" -> if (author == null) inTag = "creator"
                    "item" -> {
                        val id = t.attr("id")
                        val href = t.attr("href")
                        if (id != null && href != null) {
                            val props = t.attr("properties").orEmpty()
                            if (props.split(' ').contains("cover-image")) coverId = id
                            manifest[id] = Triple(resolve(opfPath, href), t.attr("media-type").orEmpty(), props)
                        }
                    }
                    "itemref" -> if (t.attr("linear") != "no") t.attr("idref")?.let(spine::add)
                    "spine" -> ncx = t.attr("toc")
                    "meta" -> if (t.attr("name") == "cover" && coverId == null) coverId = t.attr("content")
                }
                is Token.Text -> when (inTag) {
                    "title" -> title += t.text.trim()
                    "creator" -> author = t.text.trim()
                }
                is Token.End -> inTag = null
            }
        }
        if (spine.isEmpty()) throw BookException("This EPUB has no chapters")
        return Package(title, author, manifest, spine, ncx, coverId)
    }

    /** (title, href inside the zip incl. #fragment) */
    private fun toc(zip: ZipFile, p: Package): List<Pair<String, String>> {
        p.manifest.values.firstOrNull { it.third.contains("nav") }?.let { (href, _, _) ->
            val src = zip.text(href) ?: return@let
            val out = ArrayList<Pair<String, String>>()
            var inToc = false
            var depth = 0
            var current: Pair<StringBuilder, String>? = null
            for (t in Markup.tokenize(src)) {
                if (t is Token.Start && t.name == "nav" && !t.closed) {
                    val kind = t.attr("epub:type") ?: t.attr("type") ?: ""
                    if (kind.contains("toc") || (!inToc && out.isEmpty())) { inToc = true; depth = 1 } else if (inToc) depth++
                } else if (t is Token.End && t.name == "nav" && inToc) {
                    depth--
                    if (depth == 0) break
                } else if (t is Token.Start && inToc && t.name == "a") {
                    t.attr("href")?.let { h ->
                        val frag = fragment(h)?.let { "#$it" } ?: ""
                        current = StringBuilder() to resolve(href, h) + frag
                    }
                } else if (t is Token.Text && current != null) {
                    current!!.first.append(t.text)
                } else if (t is Token.End && t.name == "a") {
                    current?.let { (title, h) ->
                        val clean = title.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                        if (clean.isNotEmpty()) out.add(clean to h)
                    }
                    current = null
                }
            }
            if (out.isNotEmpty()) return out
        }
        val ncxHref = (p.ncx?.let { p.manifest[it] } ?: p.manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" })
            ?.first ?: return emptyList()
        val src = zip.text(ncxHref) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val label = StringBuilder()
        var inText = false
        for (t in Markup.tokenize(src)) {
            when {
                t is Token.Start && t.name == "navlabel" -> label.clear()
                t is Token.Start && t.name == "text" -> inText = true
                t is Token.End && t.name == "text" -> inText = false
                t is Token.Text && inText -> label.append(t.text)
                t is Token.Start && t.name == "content" -> t.attr("src")?.let { s ->
                    val frag = fragment(s)?.let { "#$it" } ?: ""
                    val title = label.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    if (title.isNotEmpty()) out.add(title to resolve(ncxHref, s) + frag)
                }
            }
        }
        return out
    }

    fun open(file: File): Book = ZipFile(file).use { zip ->
        val p = pkg(zip)
        val images = ArrayList<ByteArray>()
        val imageIds = HashMap<String, Int>()
        val all = ArrayList<Block>()
        val anchors = HashMap<String, Int>()
        for (idref in p.spine) {
            val (href, media, _) = p.manifest[idref] ?: continue
            if (!(media.contains("html") || media.contains("xml") || href.endsWith("htm") || href.endsWith("html"))) continue
            val src = zip.text(href) ?: continue
            val b = BlockBuilder(false) { r ->
                val target = resolve(href, r)
                imageIds[target] ?: zip.bytes(target)?.let { bytes ->
                    images.add(bytes)
                    imageIds[target] = images.size - 1
                    images.size - 1
                }
            }
            b.anchor(href)
            b.feed(Markup.tokenize(src))
            val (blocks, local) = b.finish()
            if (blocks.isEmpty()) continue
            // Each file of an EPUB (a chapter, usually) starts a new page.
            if (all.isNotEmpty()) all.add(Block.PageBreak)
            val offset = all.size
            local.forEach { (id, at) ->
                anchors.putIfAbsent(if (id == href) href else "$href#$id", offset + at)
            }
            all.addAll(blocks)
        }
        val chapters = toc(zip, p).mapNotNull { (title, href) ->
            val block = anchors[href] ?: anchors[href.substringBefore('#')] ?: return@mapNotNull null
            Chapter(title, block)
        }
        Parsed(p.title, p.author, Content.Text(all, images), chapters).asBook()
    }

    fun cover(file: File): ByteArray? = ZipFile(file).use { zip ->
        val p = pkg(zip)
        p.coverId?.let { p.manifest[it] }?.let { zip.bytes(it.first) }?.let { return@use it }
        val imgs = p.manifest.values.filter { it.second.startsWith("image/") }.map { it.first }
        val pick = imgs.firstOrNull { it.lowercase().contains("cover") } ?: imgs.firstOrNull() ?: return@use null
        zip.bytes(pick)
    }
}

// ── MOBI / AZW3 ──────────────────────────────────────────────────────────────

/** Kindle books: a Palm database of PalmDOC-compressed HTML records. */
internal object Mobi {
    private class Pdb(val data: ByteArray, val offsets: IntArray) {
        fun record(i: Int): ByteArray? {
            if (i < 0 || i >= offsets.size) return null
            val start = offsets[i]
            val end = if (i + 1 < offsets.size) offsets[i + 1] else data.size
            if (start > data.size) return null
            return data.copyOfRange(start, maxOf(start, minOf(end, data.size)))
        }
    }

    private fun be16(b: ByteArray, at: Int): Int? =
        if (at + 2 <= b.size) ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF) else null

    private fun be32(b: ByteArray, at: Int): Long? =
        if (at + 4 <= b.size) {
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        } else null

    private fun load(file: File): Pdb {
        val data = file.readBytes()
        if (data.size < 78) throw BookException("This file is too short to be a Kindle book")
        val n = be16(data, 76) ?: 0
        val offsets = ArrayList<Int>()
        for (i in 0 until n) {
            val at = 78 + i * 8
            if (at + 4 > data.size) break
            offsets.add(be32(data, at)!!.toInt())
        }
        if (offsets.isEmpty()) throw BookException("This Kindle book has no records")
        return Pdb(data, offsets.toIntArray())
    }

    private class Header(
        val compression: Int,
        val textRecords: Int,
        val utf8: Boolean,
        val firstImage: Int?,
        val extraFlags: Int,
        val title: String,
        val author: String?,
        val coverOffset: Int?,
    )

    private fun decode(b: ByteArray, utf8: Boolean) = if (utf8) String(b, Charsets.UTF_8) else decodeCp1252(b)

    private fun header(pdb: Pdb): Header {
        val r0 = pdb.record(0) ?: throw BookException("This Kindle book has no header")
        val compression = be16(r0, 0) ?: 1
        val textRecords = be16(r0, 8) ?: 0
        if ((be16(r0, 12) ?: 0) != 0) {
            throw BookException("This Kindle book is DRM-protected, so it can't be opened outside the Kindle app.")
        }
        var utf8 = false
        var firstImage: Int? = null
        var extra = 0
        var title = ""
        var author: String? = null
        var cover: Int? = null
        if (r0.size >= 20 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
            val hlen = (be32(r0, 20) ?: 0).toInt()
            utf8 = be32(r0, 28) == 65001L
            firstImage = be32(r0, 108)?.takeIf { it != 0xFFFFFFFFL && it != 0L }?.toInt()
            if (hlen >= 0xE4) extra = be16(r0, 242) ?: 0
            val nameOff = (be32(r0, 84) ?: 0).toInt()
            val nameLen = (be32(r0, 88) ?: 0).toInt()
            if (nameOff >= 0 && nameLen > 0 && nameOff + nameLen <= r0.size) title = decode(r0.copyOfRange(nameOff, nameOff + nameLen), utf8)
            if (((be32(r0, 128) ?: 0) and 0x40) != 0L) {
                val at = 16 + hlen
                if (at + 12 <= r0.size && String(r0, at, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val count = (be32(r0, at + 8) ?: 0).toInt()
                    var p = at + 12
                    repeat(count) {
                        val kind = be32(r0, p) ?: return@repeat
                        val len = (be32(r0, p + 4) ?: return@repeat).toInt()
                        if (len < 8 || p + len > r0.size) return@repeat
                        val v = r0.copyOfRange(p + 8, p + len)
                        when (kind) {
                            100L -> if (author == null) author = decode(v, utf8)
                            503L -> title = decode(v, utf8)
                            201L -> cover = be32(v, 0)?.toInt()
                        }
                        p += len
                    }
                }
            }
        }
        return Header(compression, textRecords, utf8, firstImage, extra, title, author, cover)
    }

    /** PalmDOC (LZ77-style) decompression. */
    fun palmdoc(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.size * 2)
        var buf = ByteArray(input.size * 2 + 16)
        var len = 0
        fun push(v: Int) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = v.toByte()
        }
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            when (c) {
                in 0x01..0x08 -> {
                    val end = minOf(i + c, input.size)
                    for (k in i until end) push(input[k].toInt())
                    i = end
                }
                0x00, in 0x09..0x7F -> push(c)
                in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val pair = ((c shl 8) or c2) and 0x3FFF
                    val dist = pair shr 3
                    val n = (pair and 7) + 3
                    if (dist == 0 || dist > len) continue
                    repeat(n) { push(buf[len - dist].toInt()) }
                }
                else -> { push(' '.code); push(c xor 0x80) }
            }
        }
        out.write(buf, 0, len)
        return out.toByteArray()
    }

    private fun trailingSize(data: ByteArray, flags: Int): Int {
        val size = data.size
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0 && size > num) {
                var end = size - num
                var value = 0
                var shift = 0
                while (end > 0) {
                    val v = data[end - 1].toInt() and 0xFF
                    value = value or ((v and 0x7F) shl shift)
                    shift += 7
                    end--
                    if (v and 0x80 != 0 || shift >= 28) break
                }
                num += value
            }
            f = f shr 1
        }
        if (flags and 1 != 0 && size > num) num += (data[size - num - 1].toInt() and 0x3) + 1
        return minOf(num, size)
    }

    fun imageRecord(reference: String, firstImage: Int): Int? {
        val r = reference.trim()
        val n = if (r.startsWith("kindle:embed:")) {
            r.removePrefix("kindle:embed:").takeWhile { it.isLetterOrDigit() }.uppercase().toIntOrNull(32)
        } else {
            r.trimStart('0').toIntOrNull()
        } ?: return null
        if (n < 1) return null
        return firstImage + n - 1
    }

    fun open(file: File): Book {
        val pdb = load(file)
        val h = header(pdb)
        if (h.textRecords == 0) throw BookException("This Kindle book has no text")
        if (h.compression == 17480) {
            throw BookException("This Kindle book uses HUFF/CDIC compression, which PaperView can't read yet.")
        }
        val raw = java.io.ByteArrayOutputStream()
        for (i in 1..h.textRecords) {
            val rec = pdb.record(i) ?: break
            val cut = trailingSize(rec, h.extraFlags)
            val body = rec.copyOfRange(0, maxOf(0, rec.size - cut))
            raw.write(if (h.compression == 2) palmdoc(body) else body)
        }
        val html = decode(raw.toByteArray(), h.utf8)
        val images = ArrayList<ByteArray>()
        val seen = HashMap<Int, Int>()
        val b = BlockBuilder(false) { r ->
            val first = h.firstImage ?: return@BlockBuilder null
            val rec = imageRecord(r, first) ?: return@BlockBuilder null
            seen[rec] ?: pdb.record(rec)?.let { bytes ->
                images.add(bytes)
                seen[rec] = images.size - 1
                images.size - 1
            }
        }
        b.feed(Markup.tokenize(html))
        val (blocks, _) = b.finish()
        if (blocks.isEmpty()) throw BookException("Couldn't find any text in this Kindle book")
        return Parsed(h.title, h.author, Content.Text(blocks, images), emptyList()).asBook()
    }

    fun cover(file: File): ByteArray? {
        val pdb = load(file)
        val h = header(pdb)
        return pdb.record((h.firstImage ?: return null) + (h.coverOffset ?: 0))
    }
}

// ── FB2 ──────────────────────────────────────────────────────────────────────

/** FictionBook: one XML file, images as base64 `<binary>` elements. */
internal object Fb2 {
    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1).lowercase()
        return when {
            head.contains("windows-1251") || head.contains("cp1251") -> decodeCp1251(bytes)
            head.contains("windows-1252") || head.contains("iso-8859-1") -> decodeCp1252(bytes)
            else -> decodeText(bytes)
        }
    }

    fun firstInZip(file: File): ByteArray = ZipFile(file).use { zip ->
        val e = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
            ?: throw BookException("There's no .fb2 file inside this archive")
        zip.getInputStream(e).use { it.readBytes() }
    }

    private fun binaries(tokens: List<Token>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var current: String? = null
        for (t in tokens) {
            when {
                t is Token.Start && t.name == "binary" -> current = t.attr("id")
                t is Token.Text && current != null -> {
                    val clean = t.text.filterNot { it.isWhitespace() }
                    runCatching { java.util.Base64.getDecoder().decode(clean) }.getOrNull()?.let { out[current!!] = it }
                    current = null
                }
                t is Token.End && t.name == "binary" -> current = null
            }
        }
        return out
    }

    private fun description(tokens: List<Token>): Triple<String, String?, String?> {
        var title = ""
        var first = ""
        var last = ""
        var cover: String? = null
        var inInfo = false
        var field: String? = null
        var authorDone = false
        for (t in tokens) {
            when (t) {
                is Token.Start -> when {
                    t.name == "title-info" -> inInfo = true
                    inInfo && t.name in setOf("book-title", "first-name", "last-name") -> field = t.name
                    inInfo && t.name == "image" -> cover = t.attrs.firstOrNull { it.first.endsWith("href") }?.second?.trimStart('#')
                }
                is Token.End -> {
                    if (t.name == "title-info") break
                    if (t.name == "author" && (first.isNotEmpty() || last.isNotEmpty())) authorDone = true
                    field = null
                }
                is Token.Text -> when (field) {
                    "book-title" -> title += t.text.trim()
                    "first-name" -> if (!authorDone) first += t.text.trim()
                    "last-name" -> if (!authorDone) last += t.text.trim()
                }
            }
        }
        val author = "$first $last".trim()
        return Triple(title, author.ifEmpty { null }, cover)
    }

    fun open(bytes: ByteArray): Book {
        val tokens = Markup.tokenize(decode(bytes))
        val bins = binaries(tokens)
        val (title, author, _) = description(tokens)
        val images = ArrayList<ByteArray>()
        val ids = HashMap<String, Int>()
        val b = BlockBuilder(true) { r ->
            val key = r.trimStart('#')
            ids[key] ?: bins[key]?.let { images.add(it); ids[key] = images.size - 1; images.size - 1 }
        }
        b.feed(tokens)
        val (blocks, _) = b.finish()
        if (blocks.isEmpty()) throw BookException("Couldn't find any text in this FB2 book")
        return Parsed(title, author, Content.Text(blocks, images), emptyList()).asBook()
    }

    fun cover(bytes: ByteArray): ByteArray? {
        val tokens = Markup.tokenize(decode(bytes))
        val key = description(tokens).third ?: return null
        return binaries(tokens)[key]
    }
}

// ── Comics ───────────────────────────────────────────────────────────────────

/** CBZ: a zip of page images, read in natural order (p2 before p10). */
internal object Comic {
    private val EXTS = listOf("jpg", "jpeg", "png", "gif", "webp")

    private fun naturalKey(s: String): String =
        Regex("\\d+").replace(s.lowercase()) { it.value.padStart(12, '0') }

    private fun pages(file: File): List<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { n -> val l = n.lowercase(); !l.contains("__macosx") && EXTS.any { l.endsWith(".$it") } }
            .sortedBy(::naturalKey)
            .toList()
    }

    fun open(file: File): Book {
        val p = runCatching { pages(file) }.getOrElse { throw BookException("This doesn't look like a comic archive") }
        if (p.isEmpty()) throw BookException("There are no pages in this comic")
        return Parsed("", null, Content.Comic(p), emptyList()).asBook()
    }

    fun page(file: File, name: String): ByteArray? = runCatching { ZipFile(file).use { it.bytes(name) } }.getOrNull()

    fun cover(file: File): ByteArray? = pages(file).firstOrNull()?.let { page(file, it) }
}

// ── TXT and HTML ─────────────────────────────────────────────────────────────

internal object Text {
    fun openTxt(file: File): Book {
        val text = decodeText(file.readBytes()).replace("\r\n", "\n")
        val blocks = ArrayList<Block>()
        for (para in text.split("\n\n")) {
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val joined = lines.joinToString(" ")
            val words = joined.split(Regex("\\s+")).size
            val hasLetters = joined.any { it.isLetter() }
            val shouty = hasLetters && joined.none { it.isLowerCase() }
            val lower = joined.lowercase()
            val chapterish = lower.startsWith("chapter ") || lower.startsWith("book ") || lower.startsWith("part ")
            val run = listOf(Run(joined))
            blocks.add(if (lines.size == 1 && words <= 8 && (shouty || chapterish)) Block.Heading(2, run) else Block.Para(run))
        }
        if (blocks.isEmpty()) throw BookException("This text file is empty")
        return Parsed("", null, Content.Text(blocks, emptyList()), emptyList()).asBook()
    }

    fun openHtml(file: File): Book {
        val src = decodeText(file.readBytes())
        val dir = file.parentFile
        val images = ArrayList<ByteArray>()
        val b = BlockBuilder(false) { r ->
            if (r.contains("://") || dir == null) return@BlockBuilder null // never fetch from the network
            val f = File(dir, r.substringBefore('#'))
            if (!f.isFile) return@BlockBuilder null
            images.add(f.readBytes())
            images.size - 1
        }
        val tokens = Markup.tokenize(src)
        var title = ""
        var inTitle = false
        for (t in tokens) {
            if (t is Token.Start && t.name == "title") inTitle = true
            else if (t is Token.End && t.name == "title") break
            else if (t is Token.Text && inTitle) title += t.text.trim()
        }
        b.feed(tokens)
        val (blocks, _) = b.finish()
        if (blocks.isEmpty()) throw BookException("Couldn't find any text in this file")
        return Parsed(title, null, Content.Text(blocks, images), emptyList()).asBook()
    }
}
