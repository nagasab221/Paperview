package com.paperview.mobile.book

/**
 * A forgiving reader for the HTML and XML inside ebooks. Book markup is often
 * sloppy — Kindle books are tag soup, not XML — so this never fails: it
 * tokenises whatever it's given and turns it into blocks of styled text,
 * ignoring what it doesn't understand. A port of the desktop's markup.rs.
 */
sealed interface Token {
    data class Start(val name: String, val attrs: List<Pair<String, String>>, val closed: Boolean) : Token {
        fun attr(n: String): String? = attrs.firstOrNull { it.first == n }?.second
    }
    data class End(val name: String) : Token
    data class Text(val text: String) : Token
}

object Markup {
    /** Splits markup into tags and text. Comments, doctypes and PIs dropped; CDATA becomes text. */
    fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var textStart = 0
        val n = src.length
        fun flush(from: Int, to: Int) {
            if (to > from) out.add(Token.Text(decodeEntities(src.substring(from, to))))
        }
        while (i < n) {
            if (src[i] != '<') { i++; continue }
            when {
                src.startsWith("<!--", i) -> {
                    flush(textStart, i)
                    val e = src.indexOf("-->", i)
                    i = if (e < 0) n else e + 3
                    textStart = i
                }
                src.startsWith("<![CDATA[", i) -> {
                    flush(textStart, i)
                    val e = src.indexOf("]]>", i).let { if (it < 0) n else it }
                    out.add(Token.Text(src.substring(i + 9, maxOf(e, i + 9))))
                    i = minOf(e + 3, n)
                    textStart = i
                }
                src.startsWith("<!", i) || src.startsWith("<?", i) -> {
                    flush(textStart, i)
                    val e = src.indexOf('>', i)
                    i = if (e < 0) n else e + 1
                    textStart = i
                }
                i + 1 < n && (src[i + 1] == '/' || src[i + 1].isLetter()) -> {
                    flush(textStart, i)
                    val end = tagEnd(src, i + 1)
                    val inner = src.substring(i + 1, minOf(end, n))
                    if (inner.startsWith("/")) {
                        out.add(Token.End(tagName(inner.substring(1))))
                    } else {
                        val trimmed = inner.trimEnd()
                        val closed = trimmed.endsWith("/")
                        val body = trimmed.trimEnd('/')
                        val name = tagName(body)
                        val rawNameLen = body.takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.length
                        out.add(Token.Start(name, parseAttrs(body.substring(minOf(rawNameLen, body.length))), closed))
                    }
                    i = minOf(end + 1, n)
                    textStart = i
                }
                else -> i++ // a stray "<"
            }
        }
        flush(textStart, n)
        return out
    }

    /** The `>` that ends a tag, skipping any inside quoted attribute values. */
    private fun tagEnd(s: String, start: Int): Int {
        var quote = 0.toChar()
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') return i
            i++
        }
        return s.length
    }

    /** Lower-case local name; "mbp:pagebreak" stays recognisable. */
    private fun tagName(s: String): String {
        val raw = s.takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.lowercase()
        if (raw == "mbp:pagebreak") return raw
        return raw.substringAfterLast(':')
    }

    private fun parseAttrs(s: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=') i++
            if (start == i) { i++; continue }
            val name = s.substring(start, i).lowercase()
            while (i < s.length && s[i].isWhitespace()) i++
            var value = ""
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val vs = i + 1
                    i = vs
                    while (i < s.length && s[i] != q) i++
                    value = decodeEntities(s.substring(vs, minOf(i, s.length)))
                    i++
                } else {
                    val vs = i
                    while (i < s.length && !s[i].isWhitespace()) i++
                    value = decodeEntities(s.substring(vs, i))
                }
            }
            out.add(name to value)
        }
        return out
    }

    private val NAMED = mapOf(
        "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'', "nbsp" to ' ',
        "mdash" to '—', "ndash" to '–', "hellip" to '…', "lsquo" to '‘', "rsquo" to '’',
        "ldquo" to '“', "rdquo" to '”', "laquo" to '«', "raquo" to '»', "copy" to '©',
        "reg" to '®', "trade" to '™', "shy" to '­', "eacute" to 'é', "egrave" to 'è',
        "agrave" to 'à', "ccedil" to 'ç', "uuml" to 'ü', "ouml" to 'ö', "auml" to 'ä',
        "szlig" to 'ß', "deg" to '°', "middot" to '·', "bull" to '•', "times" to '×',
        "sect" to '§', "para" to '¶', "pound" to '£', "euro" to '€',
    )

    /** `&amp;`, `&#8212;`, `&#x2014;` and the common named entities. */
    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 11)) {
                    val name = s.substring(i + 1, semi)
                    val decoded: String? = when {
                        name.startsWith("#x") || name.startsWith("#X") ->
                            name.substring(2).toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                        name.startsWith("#") ->
                            name.substring(1).toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                        else -> NAMED[name]?.toString()
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Markup in, blocks out, for formats without images or anchors to resolve. */
    fun blocksOf(src: String, fb2: Boolean): List<Block> {
        val b = BlockBuilder(fb2) { null }
        b.feed(tokenize(src))
        return b.finish().first
    }
}

/**
 * Turns tokens into blocks. [image] resolves an image reference to an index
 * in the book's image list. Element ids are recorded with the block they
 * start at, so tables of contents can point into the text.
 */
class BlockBuilder(private val fb2: Boolean, private val image: (String) -> Int?) {
    private val blocks = ArrayList<Block>()
    private val anchors = HashMap<String, Int>()
    private val runs = ArrayList<Run>()
    private var bold = 0
    private var italic = 0
    private var heading: Int? = null
    private var skip = 0
    private var sectionDepth = 0
    private val pendingIds = ArrayList<String>()
    /** span/p/div opened with an italic or bold style: (tag, bold, italic). */
    private val styled = ArrayList<Triple<String, Boolean, Boolean>>()

    /** Marks a point (a file in an EPUB, say) as starting at the next block. */
    fun anchor(name: String) { pendingIds.add(name) }

    private fun pushText(text: String) {
        val t = StringBuilder(text.length)
        var space = false
        for (c in text) {
            if (c.isWhitespace() && c != ' ') {
                space = true
            } else {
                if (space && !(t.isEmpty() && runs.isEmpty())) t.append(' ')
                space = false
                if (c != '­') t.append(c)
            }
        }
        if (space && (t.isNotEmpty() || runs.isNotEmpty())) t.append(' ')
        if (t.isEmpty()) return
        val b = bold > 0
        val it = italic > 0
        val last = runs.lastOrNull()
        if (last != null && last.bold == b && last.italic == it) {
            val add = if (last.text.endsWith(' ') && t.startsWith(' ')) t.substring(1) else t.toString()
            runs[runs.size - 1] = last.copy(text = last.text + add)
        } else {
            runs.add(Run(t.toString(), b, it))
        }
    }

    private fun lineBreak() {
        val last = runs.lastOrNull() ?: return
        runs[runs.size - 1] = last.copy(text = last.text.trimEnd() + "\n")
    }

    /** Ends the paragraph (or heading) being collected, if it has any text. */
    private fun flush() {
        val r = ArrayList(runs)
        runs.clear()
        while (r.isNotEmpty() && r.first().text.isBlank()) r.removeAt(0)
        while (r.isNotEmpty() && r.last().text.isBlank()) r.removeAt(r.size - 1)
        if (r.isEmpty()) return
        r[0] = r[0].copy(text = r[0].text.trimStart())
        r[r.size - 1] = r[r.size - 1].copy(text = r[r.size - 1].text.trimEnd())
        takeAnchors()
        blocks.add(heading?.let { Block.Heading(it, r) } ?: Block.Para(r))
    }

    private fun takeAnchors() {
        val at = blocks.size
        pendingIds.forEach { anchors.putIfAbsent(it, at) }
        pendingIds.clear()
    }

    private fun pushBlock(block: Block) {
        flush()
        takeAnchors()
        blocks.add(block)
    }

    fun feed(tokens: List<Token>) {
        for (tok in tokens) {
            when (tok) {
                is Token.Text -> if (skip == 0) pushText(tok.text)
                is Token.Start -> {
                    if (skip > 0) {
                        if (!tok.closed && isSkipped(tok.name)) skip++
                        continue
                    }
                    (tok.attr("id") ?: tok.attr("name"))?.takeIf { it.isNotEmpty() }?.let { pendingIds.add(it) }
                    if (isSkipped(tok.name)) {
                        if (!tok.closed) skip++
                        continue
                    }
                    start(tok)
                }
                is Token.End -> {
                    if (skip > 0) {
                        if (isSkipped(tok.name)) skip--
                        continue
                    }
                    end(tok.name)
                }
            }
        }
    }

    private fun start(t: Token.Start) {
        val name = t.name
        val open = if (t.closed) 0 else 1
        when (name) {
            "b", "strong" -> bold += open
            "i", "em", "emphasis", "cite", "dfn", "var" -> italic += open
            "br" -> lineBreak()
            "hr" -> pushBlock(Block.Rule)
            "mbp:pagebreak" -> pushBlock(Block.PageBreak)
            "empty-line" -> flush()
            "img", "image" -> {
                val src = listOf("src", "recindex", "href", "xlink:href", "l:href").firstNotNullOfOrNull { t.attr(it) }
                src?.let(image)?.let { pushBlock(Block.Image(it)) }
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                heading = name[1] - '0'
            }
            else -> when {
                fb2 && name == "section" -> { flush(); sectionDepth++ }
                fb2 && name == "title" -> { flush(); heading = sectionDepth.coerceIn(1, 4) }
                fb2 && name == "subtitle" -> { flush(); heading = 4 }
                isBlock(name) -> flush()
            }
        }
        if (!t.closed && (name == "span" || name == "p" || name == "div")) {
            val style = t.attrs.filter { it.first == "style" || it.first == "class" }
                .joinToString("") { it.second.lowercase() }
            when {
                style.contains("italic") -> { italic++; styled.add(Triple(name, false, true)) }
                style.contains("bold") -> { bold++; styled.add(Triple(name, true, false)) }
                else -> styled.add(Triple(name, false, false))
            }
        }
    }

    private fun end(name: String) {
        when (name) {
            "b", "strong" -> bold = maxOf(0, bold - 1)
            "i", "em", "emphasis", "cite", "dfn", "var" -> italic = maxOf(0, italic - 1)
            "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); heading = null }
            else -> when {
                fb2 && (name == "title" || name == "subtitle") -> { flush(); heading = null }
                fb2 && name == "section" -> { flush(); sectionDepth = maxOf(0, sectionDepth - 1) }
                isBlock(name) -> flush()
            }
        }
        if (name == "span" || name == "p" || name == "div") {
            val pos = styled.indexOfLast { it.first == name }
            if (pos >= 0) {
                val (_, b, i) = styled.removeAt(pos)
                if (b) bold = maxOf(0, bold - 1)
                if (i) italic = maxOf(0, italic - 1)
            }
        }
    }

    /** Finishes off: the last paragraph, and any anchors at the very end. */
    fun finish(): Pair<List<Block>, Map<String, Int>> {
        flush()
        val at = maxOf(0, blocks.size - 1)
        pendingIds.forEach { anchors.putIfAbsent(it, at) }
        pendingIds.clear()
        return blocks to anchors
    }

    private fun isSkipped(name: String): Boolean = when (name) {
        "script", "style", "head", "svg", "noscript", "math" -> true
        "description", "binary" -> fb2
        "title" -> !fb2
        else -> false
    }

    private fun isBlock(name: String): Boolean = name in BLOCKS

    companion object {
        private val BLOCKS = setOf(
            "p", "div", "li", "dt", "dd", "blockquote", "pre", "section", "article", "aside",
            "header", "footer", "figure", "figcaption", "tr", "table", "ul", "ol", "body",
            "center", "v", "stanza", "poem", "epigraph", "cite", "text-author", "annotation",
        )
    }
}
