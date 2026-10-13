package com.paperview.mobile.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paperview.mobile.pdf.DocSource
import com.paperview.mobile.pdf.PageCache
import com.paperview.mobile.pdf.PdfDoc
import com.paperview.mobile.pdf.PdfEngine
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.snapPx
import kotlinx.coroutines.launch
import java.io.File

/**
 * Opens [file] for thumbnailing and closes it when the composable leaves.
 *
 * Tools render page previews through the same PDFium path the viewer uses, so
 * what you select looks exactly like what you'll get.
 */
@Composable
fun rememberThumbnailSource(file: File, password: String?): Pair<PdfDoc?, PageCache?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by produceState<PdfDoc?>(initialValue = null, file, password) {
        value = runCatching {
            PdfEngine.open(context, DocSource.Local(file), password)
        }.getOrNull()
    }
    val cache = remember(doc) { doc?.let { PageCache(it, scope, maxBytes = 16 * 1024 * 1024) } }
    DisposableEffect(doc) {
        onDispose {
            cache?.clear()
            doc?.let { d -> scope.launch { d.close() } }
        }
    }
    return doc to cache
}

/** One page thumbnail, rendered on demand. */
@Composable
private fun PageThumb(
    doc: PdfDoc,
    cache: PageCache,
    index: Int,
    widthDp: Int,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = with(density) { widthDp.dp.toPx() }
    val box = doc.pages.getOrNull(index)
    val version by cache.version

    val w = snapPx(widthPx)
    val h = if (box != null) snapPx(widthPx * box.heightPt / box.widthPt) else w
    LaunchedEffect(index, w, version) { cache.request(index, w, h, Rotation.R0) }
    val bmp = cache.exact(index, w, Rotation.R0)

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .aspectRatio(if (box != null) box.widthPt / box.heightPt else 0.77f),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Grid of pages with tap-to-toggle selection — used by Delete, Extract and
 * per-page Rotate.
 */
@Composable
fun PageSelectGrid(
    doc: PdfDoc?,
    cache: PageCache?,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (doc == null || cache == null) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(doc.pageCount) { i ->
            val isSelected = i in selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    PageThumb(
                        doc, cache, i, widthDp = 96,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onToggle(i) },
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Reorderable page list.
 *
 * Up/down controls rather than drag-and-drop: dragging a thumbnail inside a
 * scrolling list on a phone is fiddly and easy to get wrong, and precise moves
 * are what people actually want when fixing a page order.
 */
@Composable
fun PageReorderList(
    doc: PdfDoc?,
    cache: PageCache?,
    order: List<Int>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (doc == null || cache == null) return
    LazyColumn(modifier.fillMaxSize()) {
        itemsIndexed(order, key = { _, page -> page }) { position, page ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageThumb(
                    doc, cache, page, widthDp = 56,
                    modifier = Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Position ${position + 1}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Originally page ${page + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onMove(position, position - 1) },
                    enabled = position > 0,
                ) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(
                    onClick = { onMove(position, position + 1) },
                    enabled = position < order.size - 1,
                ) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
            }
        }
    }
}
