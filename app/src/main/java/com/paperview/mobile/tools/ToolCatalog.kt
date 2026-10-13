package com.paperview.mobile.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/** Grouped by what you're trying to do, not by which library does the work. */
enum class ToolCategory(val label: String) {
    Organise("Organise"),
    Convert("Convert"),
    Protect("Protect"),
}

/** What a tool takes as input, which decides which picker to open first. */
enum class ToolInput { SinglePdf, MultiplePdfs, Images }

enum class ToolId {
    Compress, Merge, Split, Rotate, DeletePages, Reorder, NumberPages,
    PdfToImage,
    Watermark, Sign, Lock, ChangePassword, Unlock,
    ImageToPdf,
}

data class Tool(
    val id: ToolId,
    val title: String,
    val subtitle: String,
    val category: ToolCategory,
    val icon: ImageVector,
    val input: ToolInput,
    /** Extra words that should match in search but aren't in the title. */
    val keywords: List<String> = emptyList(),
)

/**
 * Every tool the app offers. Nothing is paywalled and nothing leaves the
 * device, so there are no badges to draw — the list is just the list.
 */
object ToolCatalog {

    val all: List<Tool> = listOf(
        Tool(
            ToolId.Merge, "Merge", "Combine several PDFs into one",
            ToolCategory.Organise, Icons.Outlined.MergeType, ToolInput.MultiplePdfs,
            keywords = listOf("combine", "join", "append"),
        ),
        Tool(
            ToolId.Split, "Split", "Pull pages out into new files",
            ToolCategory.Organise, Icons.AutoMirrored.Outlined.CallSplit, ToolInput.SinglePdf,
            keywords = listOf("extract", "separate", "divide", "range"),
        ),
        Tool(
            ToolId.Reorder, "Reorder", "Rearrange the page order",
            ToolCategory.Organise, Icons.Outlined.SwapVert, ToolInput.SinglePdf,
            keywords = listOf("move", "sort", "order", "rearrange"),
        ),
        Tool(
            ToolId.DeletePages, "Delete pages", "Remove pages you don't need",
            ToolCategory.Organise, Icons.Outlined.Delete, ToolInput.SinglePdf,
            keywords = listOf("remove", "erase", "drop"),
        ),
        Tool(
            ToolId.Rotate, "Rotate", "Turn pages the right way up",
            ToolCategory.Organise, Icons.Outlined.RotateRight, ToolInput.SinglePdf,
            keywords = listOf("turn", "landscape", "portrait", "sideways", "orientation"),
        ),
        Tool(
            ToolId.NumberPages, "Number pages", "Print page numbers on every page",
            ToolCategory.Organise, Icons.Outlined.FormatListNumbered, ToolInput.SinglePdf,
            keywords = listOf("pagination", "folio", "numbering"),
        ),
        Tool(
            ToolId.Compress, "Compress", "Make the file smaller",
            ToolCategory.Convert, Icons.Outlined.Compress, ToolInput.SinglePdf,
            keywords = listOf("shrink", "smaller", "size", "optimise", "optimize", "reduce"),
        ),
        Tool(
            ToolId.PdfToImage, "PDF to image", "Export pages as JPG or PNG",
            ToolCategory.Convert, Icons.Outlined.Image, ToolInput.SinglePdf,
            keywords = listOf("jpg", "jpeg", "png", "export", "picture", "photo"),
        ),
        Tool(
            ToolId.ImageToPdf, "Image to PDF", "Turn photos into a document",
            ToolCategory.Convert, Icons.Outlined.PictureAsPdf, ToolInput.Images,
            keywords = listOf("jpg", "png", "photo", "picture", "scan", "camera"),
        ),
        Tool(
            ToolId.Sign, "Sign", "Draw a signature onto a page",
            ToolCategory.Protect, Icons.Outlined.Draw, ToolInput.SinglePdf,
            keywords = listOf("signature", "autograph", "initial"),
        ),
        Tool(
            ToolId.Watermark, "Watermark", "Stamp text across every page",
            ToolCategory.Protect, Icons.Outlined.WaterDrop, ToolInput.SinglePdf,
            keywords = listOf("stamp", "draft", "confidential", "overlay"),
        ),
        Tool(
            ToolId.Lock, "Lock", "Require a password to open",
            ToolCategory.Protect, Icons.Outlined.Lock, ToolInput.SinglePdf,
            keywords = listOf("encrypt", "protect", "secure", "password"),
        ),
        Tool(
            ToolId.ChangePassword, "Change password", "Replace the existing password",
            ToolCategory.Protect, Icons.Outlined.Key, ToolInput.SinglePdf,
            keywords = listOf("replace password", "new password"),
        ),
        Tool(
            ToolId.Unlock, "Unlock", "Remove password protection",
            ToolCategory.Protect, Icons.Outlined.LockOpen, ToolInput.SinglePdf,
            keywords = listOf("decrypt", "unprotect", "remove password"),
        ),
    )

    fun byId(id: ToolId): Tool = all.first { it.id == id }

    /** Case-insensitive match over title, subtitle and keywords. */
    fun search(query: String): List<Tool> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { tool ->
            tool.title.lowercase().contains(q) ||
                tool.subtitle.lowercase().contains(q) ||
                tool.keywords.any { it.contains(q) }
        }
    }

    fun grouped(query: String): List<Pair<ToolCategory, List<Tool>>> {
        val matches = search(query)
        return ToolCategory.entries
            .map { category -> category to matches.filter { it.category == category } }
            .filter { it.second.isNotEmpty() }
    }
}
