package com.paperview.mobile.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.paperview.mobile.cloud.formatSize
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperButton
import com.paperview.mobile.ui.components.PaperOutlinedButton
import com.paperview.mobile.ui.components.PaperTopBar
import com.paperview.mobile.ui.theme.PaperTheme
import java.io.File

/**
 * Hosts one tool end to end: options, run, result.
 *
 * Every tool shares the same skeleton — pick options, hit the action button,
 * get a result card with Open / Share / Save — so a new tool only has to
 * supply its own options block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolFlowScreen(
    vm: ToolViewModel,
    onBack: () -> Unit,
    onOpenResult: (File) -> Unit,
    onShareResult: (File) -> Unit,
    onSaveResult: (File) -> Unit,
    onAddMoreFiles: () -> Unit,
) {
    val tool = vm.tool ?: return

    Scaffold(
        containerColor = PaperTheme.colors.background,
        topBar = { PaperTopBar(title = tool.title, onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.preparing -> Centered { CircularProgressIndicator() }

                vm.needsPassword -> PasswordDialog(
                    wrong = vm.wrongPassword,
                    onSubmit = vm::submitPassword,
                    onCancel = onBack,
                )

                vm.result != null -> ResultView(
                    result = vm.result!!,
                    onOpen = onOpenResult,
                    onShare = onShareResult,
                    onSave = onSaveResult,
                    onDone = onBack,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    InputSummary(vm, onAddMoreFiles)
                    Hairline()
                    vm.error?.let { message ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            InlineTextButton("Dismiss", onClick = vm::clearError)
                        }
                        Hairline()
                    }
                    Box(Modifier.weight(1f)) {
                        ToolOptions(vm, tool)
                    }
                    if (vm.running) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(vm.statusText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputSummary(vm: ToolViewModel, onAddMore: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        vm.inputs.forEachIndexed { i, input ->
            Text(
                "${i + 1}. ${input.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.pageCount > 0) {
                Text(
                    "${vm.pageCount} pages · " +
                        formatSize(vm.inputs.sumOf { it.file.length() }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (vm.tool?.input == ToolInput.MultiplePdfs || vm.tool?.input == ToolInput.Images) {
                TextButton(onClick = onAddMore) { Text("Add more") }
            }
        }
    }
}

/** Dispatches to the right options block for the selected tool. */
@Composable
private fun ToolOptions(vm: ToolViewModel, tool: Tool) {
    when (tool.id) {
        ToolId.Merge -> MergeOptions(vm)
        ToolId.Compress -> CompressOptions(vm)
        ToolId.Split -> SplitOptions(vm)
        ToolId.DeletePages -> PageSelectionOptions(vm, ToolId.DeletePages)
        ToolId.Rotate -> RotateOptions(vm)
        ToolId.Reorder -> ReorderOptions(vm)
        ToolId.NumberPages -> NumberPagesOptions(vm)
        ToolId.PdfToImage -> PdfToImageOptions(vm)
        ToolId.ImageToPdf -> ImageToPdfOptions(vm)
        ToolId.Watermark -> WatermarkOptions(vm)
        ToolId.Lock -> LockOptions(vm)
        ToolId.ChangePassword -> ChangePasswordOptions(vm)
        ToolId.Unlock -> UnlockOptions(vm)
        ToolId.Sign -> SignOptions(vm)
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun OptionsColumn(
    actionLabel: String,
    actionEnabled: Boolean = true,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) { content() }
        Hairline()
        PaperButton(
            text = actionLabel,
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                if (wrong) {
                    Text(
                        "Wrong password — try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ResultView(
    result: ToolResult,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
    onSave: (File) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(result.summary, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (result.files.size == 1) "1 file ready" else "${result.files.size} files ready",
            style = MaterialTheme.typography.bodySmall,
            color = PaperTheme.colors.muted,
        )
        Spacer(Modifier.height(26.dp))
        Hairline()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f, fill = false),
        ) {
            result.files.forEach { file ->
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        formatSize(file.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperTheme.colors.muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (file.extension.equals("pdf", true)) {
                            PaperOutlinedButton("Open") { onOpen(file) }
                        }
                        PaperOutlinedButton("Save") { onSave(file) }
                        PaperOutlinedButton("Share") { onShare(file) }
                    }
                }
                Hairline()
            }
        }
        Spacer(Modifier.height(16.dp))
        PaperButton("Done", onDone, Modifier.padding(bottom = 20.dp))
    }
}

// ── Per-tool options ─────────────────────────────────────────────────────────

@Composable
private fun MergeOptions(vm: ToolViewModel) {
    OptionsColumn(
        actionLabel = "Merge ${vm.inputs.size} files",
        actionEnabled = vm.inputs.size >= 2 && !vm.running,
        onAction = {
            val out = vm.outputFile("_merged")
            vm.run("Merging…") {
                PdfOps.merge(vm.inputs.map { it.file }, out)
                ToolResult(listOf(out), "${vm.inputs.size} files combined")
            }
        },
    ) {
        Text("Order", style = MaterialTheme.typography.titleSmall)
        Hint("Files are merged top to bottom. Use \"Add more\" to append another PDF.")
        Spacer(Modifier.height(12.dp))
        if (vm.inputs.size < 2) {
            Hint("Add at least one more PDF to merge.")
        }
    }
}

@Composable
private fun CompressOptions(vm: ToolViewModel) {
    var quality by remember { mutableStateOf(0.6f) }
    var maxDim by remember { mutableStateOf(1600f) }
    OptionsColumn(
        actionLabel = "Compress",
        actionEnabled = !vm.running,
        onAction = {
            val out = vm.outputFile("_compressed")
            vm.run("Compressing…") {
                val r = PdfOps.compress(
                    vm.inputs.first().file, out, quality, maxDim.toInt(), vm.password,
                )
                ToolResult(
                    listOf(r.out),
                    "${formatSize(r.beforeBytes)} → " +
                        "${formatSize(r.afterBytes)} (${r.savedPercent}% smaller)",
                )
            }
        },
    ) {
        Text("Image quality: ${(quality * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 0.2f..0.95f)
        Spacer(Modifier.height(8.dp))
        Text("Max image size: ${maxDim.toInt()} px", style = MaterialTheme.typography.titleSmall)
        Slider(value = maxDim, onValueChange = { maxDim = it }, valueRange = 600f..3000f)
        Spacer(Modifier.height(12.dp))
        Hint(
            "Only embedded images are re-encoded — text and vector graphics are left " +
                "untouched, so the result stays sharp and searchable. Scans shrink the most; " +
                "a text-only PDF may barely change.",
        )
    }
}

@Composable
private fun SplitOptions(vm: ToolViewModel) {
    var rangeText by remember { mutableStateOf("") }
    val total = vm.pageCount
    OptionsColumn(
        actionLabel = "Extract",
        actionEnabled = !vm.running && total > 0,
        onAction = {
            val ranges = parseRanges(rangeText, total)
            if (ranges.isEmpty()) return@OptionsColumn
            vm.run("Splitting…") {
                val base = vm.inputs.first().displayName.substringBeforeLast('.')
                val files = PdfOps.splitRanges(
                    vm.inputs.first().file, ranges, vm.outputDir(), base, vm.password,
                )
                ToolResult(files, "${files.size} file(s) created")
            }
        },
    ) {
        Text("Pages to extract", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = rangeText,
            onValueChange = { rangeText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("e.g. 1-3, 5, 8-10") },
        )
        Spacer(Modifier.height(6.dp))
        Hint("Document has $total pages. Each range becomes its own PDF.")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { rangeText = "1-$total" }, label = { Text("All") })
            if (total > 1) {
                AssistChip(
                    onClick = { rangeText = (1..total).joinToString(", ") },
                    label = { Text("Every page separately") },
                )
            }
        }
    }
}

@Composable
private fun PageSelectionOptions(vm: ToolViewModel, id: ToolId) {
    val input = vm.inputs.firstOrNull() ?: return
    val (doc, cache) = rememberThumbnailSource(input.file, vm.password)
    val selected = remember { mutableStateListOf<Int>() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${selected.size} selected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { selected.clear() }) { Text("Clear") }
        }
        Box(Modifier.weight(1f)) {
            PageSelectGrid(
                doc = doc,
                cache = cache,
                selected = selected.toSet(),
                onToggle = { if (!selected.remove(it)) selected.add(it) },
            )
        }
        PaperButton(
            text = "Delete ${selected.size} page(s)",
            onClick = {
                val out = vm.outputFile("_edited")
                val toRemove = selected.toSet()
                vm.run("Removing pages…") {
                    PdfOps.deletePages(input.file, toRemove, out, vm.password)
                    ToolResult(listOf(out), "${toRemove.size} page(s) removed")
                }
            },
            enabled = selected.isNotEmpty() && !vm.running,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun RotateOptions(vm: ToolViewModel) {
    val input = vm.inputs.firstOrNull() ?: return
    val (doc, cache) = rememberThumbnailSource(input.file, vm.password)
    val selected = remember { mutableStateListOf<Int>() }
    var degrees by remember { mutableStateOf(90) }
    var allPages by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90 to "90° right", 180 to "180°", 270 to "90° left").forEach { (deg, label) ->
                    FilterChip(
                        selected = degrees == deg,
                        onClick = { degrees = deg },
                        label = { Text(label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All pages", Modifier.weight(1f))
                Switch(checked = allPages, onCheckedChange = { allPages = it })
            }
        }
        if (!allPages) {
            Box(Modifier.weight(1f)) {
                PageSelectGrid(
                    doc = doc,
                    cache = cache,
                    selected = selected.toSet(),
                    onToggle = { if (!selected.remove(it)) selected.add(it) },
                )
            }
        } else {
            Box(Modifier.weight(1f)) {
                PageSelectGrid(doc = doc, cache = cache, selected = emptySet(), onToggle = {})
            }
        }
        PaperButton(
            text = "Rotate and save",
            onClick = {
                val out = vm.outputFile("_rotated")
                val pages = if (allPages) null else selected.toSet()
                vm.run("Rotating…") {
                    PdfOps.rotate(input.file, degrees, pages, out, vm.password)
                    ToolResult(listOf(out), "Rotated ${if (allPages) "all pages" else "${pages?.size} page(s)"}")
                }
            },
            enabled = !vm.running && (allPages || selected.isNotEmpty()),
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun ReorderOptions(vm: ToolViewModel) {
    val input = vm.inputs.firstOrNull() ?: return
    val (doc, cache) = rememberThumbnailSource(input.file, vm.password)
    val order = remember(vm.pageCount) { mutableStateListOf<Int>().apply { addAll(0 until vm.pageCount) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Hint("Use the arrows to move pages.")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val reversed = order.reversed().toList()
                order.clear(); order.addAll(reversed)
            }) { Text("Reverse") }
        }
        Box(Modifier.weight(1f)) {
            PageReorderList(
                doc = doc,
                cache = cache,
                order = order.toList(),
                onMove = { from, to ->
                    if (to in order.indices) {
                        val item = order.removeAt(from)
                        order.add(to, item)
                    }
                },
            )
        }
        PaperButton(
            text = "Save new order",
            onClick = {
                val out = vm.outputFile("_reordered")
                val finalOrder = order.toList()
                vm.run("Saving new order…") {
                    PdfOps.writePages(input.file, finalOrder, out, vm.password)
                    ToolResult(listOf(out), "${finalOrder.size} pages reordered")
                }
            },
            enabled = !vm.running && order.isNotEmpty(),
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun NumberPagesOptions(vm: ToolViewModel) {
    var anchor by remember { mutableStateOf(PdfOps.Anchor.BottomCentre) }
    var startAt by remember { mutableStateOf("1") }
    var format by remember { mutableStateOf("{n}") }
    var skipFirst by remember { mutableStateOf(false) }

    OptionsColumn(
        actionLabel = "Add page numbers",
        actionEnabled = !vm.running,
        onAction = {
            val out = vm.outputFile("_numbered")
            vm.run("Numbering pages…") {
                PdfOps.numberPages(
                    input = vm.inputs.first().file,
                    out = out,
                    anchor = anchor,
                    startAt = startAt.toIntOrNull() ?: 1,
                    format = format.ifBlank { "{n}" },
                    skipFirst = skipFirst,
                    password = vm.password,
                )
                ToolResult(listOf(out), "Page numbers added")
            }
        },
    ) {
        Text("Position", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Column {
            listOf(
                PdfOps.Anchor.TopLeft to "Top left",
                PdfOps.Anchor.TopCentre to "Top centre",
                PdfOps.Anchor.TopRight to "Top right",
                PdfOps.Anchor.BottomLeft to "Bottom left",
                PdfOps.Anchor.BottomCentre to "Bottom centre",
                PdfOps.Anchor.BottomRight to "Bottom right",
            ).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) ->
                        FilterChip(
                            selected = anchor == value,
                            onClick = { anchor = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = format,
            onValueChange = { format = it },
            label = { Text("Format") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Use {n} for the page number and {total} for the last number — e.g. \"{n} of {total}\".")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = startAt,
            onValueChange = { startAt = it.filter(Char::isDigit).take(5) },
            label = { Text("Start at") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Skip the first page", Modifier.weight(1f))
            Switch(checked = skipFirst, onCheckedChange = { skipFirst = it })
        }
    }
}

@Composable
private fun PdfToImageOptions(vm: ToolViewModel) {
    var png by remember { mutableStateOf(false) }
    var dpi by remember { mutableStateOf(150f) }
    var rangeText by remember { mutableStateOf("") }
    val total = vm.pageCount

    OptionsColumn(
        actionLabel = "Export images",
        actionEnabled = !vm.running && total > 0,
        onAction = {
            val pages = if (rangeText.isBlank()) (0 until total).toList()
            else parseRanges(rangeText, total).flatMap { it.toList() }
            vm.run("Rendering pages…") {
                val base = vm.inputs.first().displayName.substringBeforeLast('.')
                val files = ImageExport.export(
                    context = vm.getApplication(),
                    input = vm.inputs.first().file,
                    password = vm.password,
                    pages = pages,
                    dpi = dpi.toInt(),
                    png = png,
                    outDir = vm.outputDir(),
                    baseName = base,
                )
                ToolResult(files, "${files.size} image(s) exported")
            }
        },
    ) {
        Text("Format", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPG") })
            FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
        }
        Spacer(Modifier.height(12.dp))
        Text("Resolution: ${dpi.toInt()} dpi", style = MaterialTheme.typography.titleSmall)
        Slider(value = dpi, onValueChange = { dpi = it }, valueRange = 72f..400f)
        Hint("Higher dpi means sharper images and much bigger files.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = rangeText,
            onValueChange = { rangeText = it },
            label = { Text("Pages (blank = all)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Document has $total pages.")
    }
}

@Composable
private fun ImageToPdfOptions(vm: ToolViewModel) {
    var fit by remember { mutableStateOf(PdfOps.PageFit.FitImage) }
    var quality by remember { mutableStateOf(0.85f) }
    OptionsColumn(
        actionLabel = "Create PDF",
        actionEnabled = !vm.running && vm.inputs.isNotEmpty(),
        onAction = {
            val out = vm.outputFile("_images")
            vm.run("Building PDF…") {
                PdfOps.imagesToPdf(vm.inputs.map { it.file }, out, fit, quality)
                ToolResult(listOf(out), "${vm.inputs.size} image(s) combined")
            }
        },
    ) {
        Text("Page size", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PdfOps.PageFit.FitImage to "Match image",
                PdfOps.PageFit.A4 to "A4",
                PdfOps.PageFit.Letter to "Letter",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = fit == value,
                    onClick = { fit = value },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Quality: ${(quality * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 0.3f..1f)
        Spacer(Modifier.height(8.dp))
        Hint("Images are added in the order listed above.")
    }
}

@Composable
private fun WatermarkOptions(vm: ToolViewModel) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableStateOf(0.25f) }
    var angle by remember { mutableStateOf(45f) }
    var size by remember { mutableStateOf(48f) }

    OptionsColumn(
        actionLabel = "Add watermark",
        actionEnabled = !vm.running && text.isNotBlank(),
        onAction = {
            val out = vm.outputFile("_watermarked")
            vm.run("Adding watermark…") {
                PdfOps.watermarkText(
                    input = vm.inputs.first().file,
                    text = text,
                    out = out,
                    opacity = opacity,
                    rotationDegrees = angle,
                    fontSize = size,
                    password = vm.password,
                )
                ToolResult(listOf(out), "Watermark applied to every page")
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Watermark text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Opacity: ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.05f..1f)
        Text("Angle: ${angle.toInt()}°", style = MaterialTheme.typography.titleSmall)
        Slider(value = angle, onValueChange = { angle = it }, valueRange = 0f..90f)
        Text("Size: ${size.toInt()} pt", style = MaterialTheme.typography.titleSmall)
        Slider(value = size, onValueChange = { size = it }, valueRange = 12f..120f)
        Spacer(Modifier.height(8.dp))
        Hint("The watermark is drawn over every page and can't be removed afterwards.")
    }
}

@Composable
private fun LockOptions(vm: ToolViewModel) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var allowPrint by remember { mutableStateOf(true) }
    var allowCopy by remember { mutableStateOf(true) }
    val mismatch = confirm.isNotEmpty() && pw != confirm

    OptionsColumn(
        actionLabel = "Lock PDF",
        actionEnabled = !vm.running && pw.isNotBlank() && pw == confirm,
        onAction = {
            val out = vm.outputFile("_locked")
            vm.run("Encrypting…") {
                PdfOps.lock(
                    input = vm.inputs.first().file,
                    userPassword = pw,
                    ownerPassword = pw,
                    out = out,
                    currentPassword = vm.password,
                    allowPrinting = allowPrint,
                    allowCopying = allowCopy,
                )
                ToolResult(listOf(out), "Password protection added")
            }
        },
    ) {
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm password") },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (mismatch) {
            Text(
                "Passwords don't match",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow printing", Modifier.weight(1f))
            Switch(checked = allowPrint, onCheckedChange = { allowPrint = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow copying text", Modifier.weight(1f))
            Switch(checked = allowCopy, onCheckedChange = { allowCopy = it })
        }
        Spacer(Modifier.height(10.dp))
        Hint(
            "There is no way to recover this password — if you forget it, the file " +
                "cannot be opened. Keep the original somewhere safe.",
        )
    }
}

@Composable
private fun ChangePasswordOptions(vm: ToolViewModel) {
    var oldPw by remember { mutableStateOf(vm.password.orEmpty()) }
    var newPw by remember { mutableStateOf("") }
    OptionsColumn(
        actionLabel = "Change password",
        actionEnabled = !vm.running && newPw.isNotBlank(),
        onAction = {
            val out = vm.outputFile("_newpassword")
            vm.run("Re-encrypting…") {
                PdfOps.changePassword(vm.inputs.first().file, oldPw, newPw, out)
                ToolResult(listOf(out), "Password changed")
            }
        },
    ) {
        OutlinedTextField(
            value = oldPw,
            onValueChange = { oldPw = it },
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = newPw,
            onValueChange = { newPw = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UnlockOptions(vm: ToolViewModel) {
    var pw by remember { mutableStateOf(vm.password.orEmpty()) }
    OptionsColumn(
        actionLabel = "Remove password",
        actionEnabled = !vm.running,
        onAction = {
            val out = vm.outputFile("_unlocked")
            vm.run("Removing protection…") {
                PdfOps.unlock(vm.inputs.first().file, pw, out)
                ToolResult(listOf(out), "Password removed")
            }
        },
    ) {
        Hint("You need the current password — this removes protection, it doesn't crack it.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignOptions(vm: ToolViewModel) {
    SignatureFlow(vm)
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Parses "1-3, 5, 8-10" into zero-based ranges, clamped to the document and
 * ignoring anything malformed rather than failing the whole operation.
 */
internal fun parseRanges(text: String, total: Int): List<IntRange> {
    if (total <= 0) return emptyList()
    return text.split(',', ';')
        .mapNotNull { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@mapNotNull null
            val bounds = piece.split('-', '–').map { it.trim() }
            when (bounds.size) {
                1 -> bounds[0].toIntOrNull()?.let { n -> (n - 1)..(n - 1) }
                2 -> {
                    val a = bounds[0].toIntOrNull()
                    val b = bounds[1].toIntOrNull()
                    if (a == null || b == null) null
                    else (minOf(a, b) - 1)..(maxOf(a, b) - 1)
                }
                else -> null
            }
        }
        .mapNotNull { range ->
            val start = range.first.coerceIn(0, total - 1)
            val end = range.last.coerceIn(0, total - 1)
            if (start > end) null else start..end
        }
}
