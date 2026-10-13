package com.paperview.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * PaperView's design system.
 *
 * The look is editorial rather than app-like: paper-toned surfaces, a serif
 * face for headings against a sans body, hairline rules instead of cards and
 * shadows, and colour reserved for things that actually mean something. Any
 * screen that reaches for a drop shadow or a filled colour chip is off-theme.
 */

/** A complete look — not a hue swap. Each defines its own light and dark side. */
enum class Skin(val key: String, val label: String, val blurb: String) {
    Paper("paper", "Paper", "Warm off-white, sepia accent"),
    Slate("slate", "Slate", "Cool grey, steel-blue accent"),
    Ink("ink", "Ink", "High contrast, no hue");

    companion object {
        fun from(key: String?): Skin = entries.firstOrNull { it.key == key } ?: Paper
    }
}

/**
 * The colours the app actually reasons about. Material's scheme carries these
 * too, but naming them plainly keeps screens readable.
 */
data class PaperColors(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    /** Behind the pages in the viewer. */
    val viewport: Color,
    val isDark: Boolean,
)

private val PaperLight = PaperColors(
    background = Color(0xFFFAF8F4),
    surface = Color(0xFFFFFDF9),
    ink = Color(0xFF1B1917),
    muted = Color(0xFF6B6459),
    hairline = Color(0xFFE3DDD2),
    accent = Color(0xFF8A5A2B),
    onAccent = Color(0xFFFFFDF9),
    viewport = Color(0xFFCFC9BE),
    isDark = false,
)

private val PaperDark = PaperColors(
    background = Color(0xFF16140F),
    surface = Color(0xFF1D1A14),
    ink = Color(0xFFEFEAE0),
    muted = Color(0xFF9A9285),
    hairline = Color(0xFF2E2A22),
    accent = Color(0xFFC9915A),
    onAccent = Color(0xFF1B1610),
    viewport = Color(0xFF0F0D0A),
    isDark = true,
)

private val SlateLight = PaperColors(
    background = Color(0xFFF4F6F8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF171A1C),
    muted = Color(0xFF5C666E),
    hairline = Color(0xFFDCE2E8),
    accent = Color(0xFF35678F),
    onAccent = Color(0xFFFFFFFF),
    viewport = Color(0xFFC4CCD3),
    isDark = false,
)

private val SlateDark = PaperColors(
    background = Color(0xFF101416),
    surface = Color(0xFF171C20),
    ink = Color(0xFFE4E9ED),
    muted = Color(0xFF8D979F),
    hairline = Color(0xFF262D33),
    accent = Color(0xFF7FA9CC),
    onAccent = Color(0xFF0B1116),
    viewport = Color(0xFF0A0D0F),
    isDark = true,
)

private val InkLight = PaperColors(
    background = Color(0xFFFBFBF8),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF0B0B0A),
    muted = Color(0xFF55554F),
    hairline = Color(0xFFDEDED6),
    // No hue by design: hierarchy comes from weight and rules, not colour.
    accent = Color(0xFF0B0B0A),
    onAccent = Color(0xFFFBFBF8),
    viewport = Color(0xFFC8C8C2),
    isDark = false,
)

private val InkDark = PaperColors(
    background = Color(0xFF0A0A09),
    surface = Color(0xFF121211),
    ink = Color(0xFFF2F1EA),
    muted = Color(0xFF8B8B84),
    hairline = Color(0xFF1F1F1D),
    accent = Color(0xFFF2F1EA),
    onAccent = Color(0xFF0A0A09),
    viewport = Color(0xFF000000),
    isDark = true,
)

private fun colorsFor(skin: Skin, dark: Boolean): PaperColors = when (skin) {
    Skin.Paper -> if (dark) PaperDark else PaperLight
    Skin.Slate -> if (dark) SlateDark else SlateLight
    Skin.Ink -> if (dark) InkDark else InkLight
}

/**
 * Overlay colours that must stay legible on a white PDF page no matter which
 * skin is active, so they don't take part in theming. Carried over unchanged
 * from the desktop build.
 */
object Marks {
    val selection = Color(0x50468CFF)     // rgba(70,140,255,80)
    val searchHit = Color(0x50FFEB00)     // rgba(255,235,0,80)
    val searchCurrent = Color(0x6EFF8C00)  // rgba(255,140,0,110)
}

/** Kept for the launcher icon and anywhere the brand mark is drawn. */
object Brand {
    val page = Color(0xFFEEF2F6)
    val pageEdge = Color(0xFFB7C0CC)
    val bars = Color(0xFFC2CBD8)
    val blue = Color(0xFF2D7DD2)
    val blueDark = Color(0xFF1F5FA8)
}

val LocalPaperColors = staticCompositionLocalOf { PaperLight }

object PaperTheme {
    val colors: PaperColors
        @Composable @ReadOnlyComposable get() = LocalPaperColors.current
}

/** Light / Dark / System, on top of whichever skin is chosen. */
enum class ThemeChoice { Light, Dark, System;
    companion object {
        fun from(name: String?): ThemeChoice = when (name) {
            "light" -> Light
            "dark" -> Dark
            else -> System
        }
    }

    val key: String get() = name.lowercase()
}

/**
 * Serif for anything that announces itself, sans for anything you read in
 * bulk. Numerals in listings sit in the body face so columns stay even.
 */
private fun typographyFor(colors: PaperColors) = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
        color = colors.ink,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
        color = colors.ink,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        color = colors.ink,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        color = colors.ink,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = colors.ink,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = colors.ink,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = colors.ink,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        color = colors.ink,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = colors.muted,
    ),
    // Section headings: small, letterspaced, uppercase at the call site.
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
        color = colors.muted,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
        color = colors.muted,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp,
        color = colors.muted,
    ),
)

/** Crisp, not bubbly — print doesn't have 28dp corners. */
private val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun PaperViewTheme(
    choice: ThemeChoice = ThemeChoice.System,
    skinKey: String = Skin.Paper.key,
    content: @Composable () -> Unit,
) {
    val dark = when (choice) {
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
        ThemeChoice.System -> isSystemInDarkTheme()
    }
    val colors = colorsFor(Skin.from(skinKey), dark)

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = Color(0xFFE08A7E),
            onError = Color(0xFF2A100C),
            errorContainer = Color(0xFF3A1A15),
            onErrorContainer = Color(0xFFF4C7C0),
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = Color(0xFF9B3226),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF7E2DE),
            onErrorContainer = Color(0xFF5A1B13),
        )
    }

    CompositionLocalProvider(LocalPaperColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(colors),
            shapes = PaperShapes,
            content = content,
        )
    }
}
