package com.paperview.mobile.book

import android.content.Context
import android.graphics.Typeface

/** Reading typefaces — the same nine as the desktop. */
enum class BookFont(
    val key: String,
    val label: String,
    val blurb: String,
    /** Asset file stem (null: the system's Noto). */
    private val stem: String?,
    private val ext: String = "ttf",
    /** Faces differ in how big they look at one size; this evens them out. */
    val scale: Float = 1f,
    val sans: Boolean = false,
) {
    NotoSerif("noto-serif", "Noto Serif", "PaperView's serif: calm and even", null),
    Literata("literata", "Literata", "Drawn for long reading on screens", "Literata"),
    Merriweather("merriweather", "Merriweather", "Sturdy and open, clear when small", "Merriweather", scale = 0.94f),
    Garamond("garamond", "EB Garamond", "The classic printed-book face", "EBGaramond", scale = 1.13f),
    Lora("lora", "Lora", "Warm, with a calligraphic touch", "Lora"),
    SourceSerif("source-serif", "Source Serif", "Crisp and modern", "SourceSerif4"),
    NotoSans("noto-sans", "Noto Sans", "Plain sans-serif", null, sans = true),
    Atkinson("atkinson", "Atkinson Hyperlegible", "Letters made hard to confuse", "AtkinsonHyperlegible", scale = 1.02f, sans = true),
    OpenDyslexic("opendyslexic", "OpenDyslexic", "Weighted letters, for dyslexia", "OpenDyslexic", "otf", 0.88f, true);

    fun typeface(context: Context, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val s = stem ?: return Typeface.create(if (sans) Typeface.SANS_SERIF else Typeface.SERIF, style)
        val cut = when {
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        }
        val path = "fonts/reading/$s-$cut.$ext"
        return cache.getOrPut(path) {
            runCatching { Typeface.createFromAsset(context.assets, path) }
                .getOrDefault(Typeface.create(Typeface.SERIF, style))
        }
    }

    companion object {
        private val cache = HashMap<String, Typeface>()
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: NotoSerif
    }
}

/** Line height as a multiple of the text size (the same rhythm in every face). */
enum class Spacing(val label: String, val mult: Float) {
    Compact("Compact", 1.35f), Normal("Normal", 1.55f), Relaxed("Relaxed", 1.8f)
}

enum class Margins(val label: String, val dp: Float) {
    Narrow("Narrow", 14f), Normal("Normal", 26f), Wide("Wide", 42f)
}

/** Page colours, independent of the app's theme. */
enum class PageColors(val key: String, val label: String, val bg: Long, val ink: Long, val muted: Long) {
    Auto("auto", "Auto", 0, 0, 0),
    Paper("paper", "Paper", 0xFFFAF8F4, 0xFF2B2621, 0xFF8A8278),
    Sepia("sepia", "Sepia", 0xFFF3E7CF, 0xFF4E3B2A, 0xFF8C7660),
    Dark("dark", "Dark", 0xFF1D1C1A, 0xFFD8D2C6, 0xFF8F887C),
    Black("black", "Black", 0xFF000000, 0xFFB9B3A8, 0xFF6F6A62);

    /** Auto follows the app: Paper by day, Dark by night. */
    fun resolve(dark: Boolean): PageColors = if (this == Auto) (if (dark) Dark else Paper) else this

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** How a book is typeset and read. Saved as one string, like the desktop's. */
data class ReadingStyle(
    val font: BookFont = BookFont.NotoSerif,
    /** Text size in sp, before the face's own correction. */
    val size: Float = 18f,
    val spacing: Spacing = Spacing.Normal,
    val margins: Margins = Margins.Normal,
    /** Paragraphs as in a printed book (first line indented) rather than spaced apart. */
    val indent: Boolean = true,
    val justify: Boolean = true,
    val colors: PageColors = PageColors.Auto,
    /** Swipe/tap through pages, or scroll down continuously. */
    val scroll: Boolean = false,
    /** Two pages side by side when the phone is on its side. */
    val spreads: Boolean = true,
    val volumeKeys: Boolean = false,
    val keepAwake: Boolean = true,
    val fullScreen: Boolean = true,
) {
    fun encode(): String = listOf(
        "font=${font.key}", "size=%.1f".format(java.util.Locale.US, size), "spacing=${spacing.name}",
        "margins=${margins.name}", "para=${if (indent) "indent" else "spaced"}", "justify=$justify",
        "colors=${colors.key}", "scroll=$scroll", "spreads=$spreads", "volume=$volumeKeys",
        "awake=$keepAwake", "full=$fullScreen",
    ).joinToString(";")

    /** The style that changes how pages are laid out (not just drawn). */
    fun layoutKey() = listOf(font, size, spacing, margins, indent, justify)

    companion object {
        const val MIN_SIZE = 12f
        const val MAX_SIZE = 34f

        fun decode(raw: String): ReadingStyle {
            var s = ReadingStyle()
            for (pair in raw.split(';')) {
                val k = pair.substringBefore('=', "")
                val v = pair.substringAfter('=', "")
                s = when (k) {
                    "font" -> s.copy(font = BookFont.from(v))
                    "size" -> s.copy(size = (v.toFloatOrNull() ?: 18f).coerceIn(MIN_SIZE, MAX_SIZE))
                    "spacing" -> s.copy(spacing = Spacing.entries.firstOrNull { it.name == v } ?: Spacing.Normal)
                    "margins" -> s.copy(margins = Margins.entries.firstOrNull { it.name == v } ?: Margins.Normal)
                    "para" -> s.copy(indent = v != "spaced")
                    "justify" -> s.copy(justify = v != "false")
                    "colors" -> s.copy(colors = PageColors.from(v))
                    "scroll" -> s.copy(scroll = v == "true")
                    "spreads" -> s.copy(spreads = v != "false")
                    "volume" -> s.copy(volumeKeys = v == "true")
                    "awake" -> s.copy(keepAwake = v != "false")
                    "full" -> s.copy(fullScreen = v != "false")
                    else -> s
                }
            }
            return s
        }
    }
}
