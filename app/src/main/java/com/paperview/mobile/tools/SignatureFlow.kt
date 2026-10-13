package com.paperview.mobile.tools

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Draw a signature, choose where it goes, stamp it in.
 *
 * This is the "sign a form" case, not a cryptographic signature — the drawing
 * is flattened into the page as artwork.
 */
@Composable
fun SignatureFlow(vm: ToolViewModel) {
    val input = vm.inputs.firstOrNull() ?: return
    var drawn by remember { mutableStateOf<Bitmap?>(null) }
    var page by remember { mutableStateOf(0) }
    var xFraction by remember { mutableStateOf(0.55f) }
    var yFraction by remember { mutableStateOf(0.8f) }
    var widthFraction by remember { mutableStateOf(0.3f) }

    val signature = drawn
    if (signature == null) {
        SignaturePad(onDone = { drawn = it })
        return
    }

    val (doc, cache) = rememberThumbnailSource(input.file, vm.password)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tap the page to place it", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { drawn = null }) { Text("Redraw") }
        }

        Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            SignaturePlacementPreview(
                vm = vm,
                doc = doc,
                cache = cache,
                page = page,
                signature = signature,
                xFraction = xFraction,
                yFraction = yFraction,
                widthFraction = widthFraction,
                onPlace = { x, y -> xFraction = x; yFraction = y },
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Size: ${(widthFraction * 100).toInt()}% of page width")
            Slider(
                value = widthFraction,
                onValueChange = { widthFraction = it },
                valueRange = 0.1f..0.8f,
            )
            if (vm.pageCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Page ${page + 1} of ${vm.pageCount}", Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { page = (page - 1).coerceAtLeast(0) },
                        enabled = page > 0,
                    ) { Text("Prev") }
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(
                        onClick = { page = (page + 1).coerceAtMost(vm.pageCount - 1) },
                        enabled = page < vm.pageCount - 1,
                    ) { Text("Next") }
                }
            }
        }

        Button(
            onClick = {
                val out = vm.outputFile("_signed")
                vm.run("Adding signature…") {
                    PdfOps.stampImage(
                        input = input.file,
                        bitmap = signature,
                        pageIndex = page,
                        xFraction = xFraction,
                        yFraction = yFraction,
                        widthFraction = widthFraction,
                        out = out,
                        password = vm.password,
                    )
                    ToolResult(listOf(out), "Signature added to page ${page + 1}")
                }
            },
            enabled = !vm.running,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Sign and save") }
    }
}

/** Page preview with the signature overlaid where it will land. */
@Composable
private fun SignaturePlacementPreview(
    vm: ToolViewModel,
    doc: com.paperview.mobile.pdf.PdfDoc?,
    cache: com.paperview.mobile.pdf.PageCache?,
    page: Int,
    signature: Bitmap,
    xFraction: Float,
    yFraction: Float,
    widthFraction: Float,
    onPlace: (Float, Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .pointerInput(page) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        onPlace(
                            (offset.x / size.width).coerceIn(0f, 0.95f),
                            (offset.y / size.height).coerceIn(0.02f, 1f),
                        )
                    }
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        PagePreview(doc, cache, page)
        if (size.width > 0) {
            val sigWidth = size.width * widthFraction
            val sigHeight = sigWidth * signature.height / max(1, signature.width)
            Image(
                bitmap = signature.asImageBitmap(),
                contentDescription = "Signature",
                modifier = Modifier
                    .padding(
                        start = (xFraction * size.width / density()).dp,
                        top = ((yFraction * size.height - sigHeight) / density()).dp,
                    )
                    .height((sigHeight / density()).dp),
            )
        }
    }
}

@Composable
private fun density(): Float = androidx.compose.ui.platform.LocalDensity.current.density

@Composable
private fun PagePreview(
    doc: com.paperview.mobile.pdf.PdfDoc?,
    cache: com.paperview.mobile.pdf.PageCache?,
    page: Int,
) {
    if (doc == null || cache == null) return
    val box = doc.pages.getOrNull(page) ?: return
    val version by cache.version
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 320.dp.toPx() }
    val w = com.paperview.mobile.pdf.snapPx(widthPx)
    val h = com.paperview.mobile.pdf.snapPx(widthPx * box.heightPt / box.widthPt)
    androidx.compose.runtime.LaunchedEffect(page, version) {
        cache.request(page, w, h, com.paperview.mobile.pdf.Rotation.R0)
    }
    val bmp = cache.exact(page, w, com.paperview.mobile.pdf.Rotation.R0)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${page + 1}",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Finger-painting surface that hands back a transparent-background bitmap. */
@Composable
private fun SignaturePad(onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Draw your signature", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use your finger. This is stamped onto the page as an image, not a " +
                "certificate-backed digital signature.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            current.clear()
                            current.add(offset)
                        },
                        onDrag = { change, _ ->
                            current.add(change.position)
                            change.consume()
                        },
                        onDragEnd = {
                            if (current.size > 1) strokes.add(current.toList())
                            current.clear()
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val all = strokes + listOf(current.toList())
                for (stroke in all) {
                    if (stroke.size < 2) continue
                    val path = Path().apply {
                        moveTo(stroke.first().x, stroke.first().y)
                        stroke.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = Color.Black,
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { strokes.clear(); current.clear() }) { Text("Clear") }
            Button(
                onClick = {
                    val bitmap = renderSignature(strokes, canvasSize)
                    if (bitmap != null) onDone(bitmap)
                },
                enabled = strokes.isNotEmpty(),
            ) { Text("Use this signature") }
        }
    }
}

/**
 * Rasterises the strokes, cropped to their bounds with a small margin, so the
 * stamped signature isn't mostly empty space.
 */
private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    if (strokes.isEmpty() || size.width <= 0 || size.height <= 0) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE
    strokes.forEach { stroke ->
        stroke.forEach { p ->
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
    }
    val margin = 12f
    minX = (minX - margin).coerceAtLeast(0f)
    minY = (minY - margin).coerceAtLeast(0f)
    maxX = (maxX + margin).coerceAtMost(size.width.toFloat())
    maxY = (maxY + margin).coerceAtMost(size.height.toFloat())
    val w = max(1, (maxX - minX).toInt())
    val h = max(1, (maxY - minY).toInt())

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        val path = AndroidPath()
        path.moveTo(stroke.first().x - minX, stroke.first().y - minY)
        stroke.drop(1).forEach { path.lineTo(it.x - minX, it.y - minY) }
        canvas.drawPath(path, paint)
    }
    return bitmap
}
