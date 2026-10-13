package com.paperview.mobile.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.PaperButton
import com.paperview.mobile.ui.components.PaperChoice
import com.paperview.mobile.ui.components.PaperOutlinedButton
import com.paperview.mobile.ui.components.PaperTopBar
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme

/**
 * What the scanner hands back, before it is committed to a document.
 *
 * ML Kit's own filters are buried a tap deep in its editor and reset for every
 * capture, which is why scans kept coming out as plain photographs. Choosing
 * here, with the result on screen and the choice remembered, is the whole point
 * of this screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanReviewScreen(
    pages: List<Uri>,
    filter: ScanFilter,
    onFilterChange: (ScanFilter) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    saving: Boolean,
    savedPages: Int,
) {
    val colors = PaperTheme.colors
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }

    BackHandler(enabled = !saving) { onDiscard() }

    // Re-render whenever the choice changes. The preview is small on purpose:
    // tapping through four filters has to feel like switching a light on.
    LaunchedEffect(pages.firstOrNull(), filter) {
        val first = pages.firstOrNull() ?: return@LaunchedEffect
        rendering = true
        val next = ScanPipeline.preview(context, first, filter)
        preview?.recycle()
        preview = next
        rendering = false
    }
    DisposableEffect(Unit) {
        onDispose { preview?.recycle() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        PaperTopBar(
            title = if (pages.size == 1) "1 page scanned" else "${pages.size} pages scanned",
            onBack = if (saving) null else onDiscard,
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(colors.surface)
                    .border(1.dp, colors.hairline),
                contentAlignment = Alignment.Center,
            ) {
                val shown = preview
                if (shown != null && !shown.isRecycled) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "First page, ${filter.label}",
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else if (rendering) {
                    CircularProgressIndicator(color = colors.muted)
                }
            }

            if (pages.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Showing page 1. The filter applies to all ${pages.size}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }

            SectionHeading("Filter")
            Spacer(Modifier.height(4.dp))
            FlowRow(Modifier.fillMaxWidth()) {
                ScanFilter.entries.forEach { option ->
                    PaperChoice(
                        text = option.label,
                        selected = option == filter,
                        onClick = { if (!saving) onFilterChange(option) },
                    )
                }
            }
            Text(
                filter.note,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            Spacer(Modifier.height(20.dp))
        }

        Hairline()
        Column(
            Modifier
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (saving) {
                Text(
                    if (pages.size == 1) "Preparing the page…"
                    else "Preparing page $savedPages of ${pages.size}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { savedPages.toFloat() / pages.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperOutlinedButton("Discard", onClick = onDiscard)
                    Box(Modifier.weight(1f)) { PaperButton("Save to Library", onSave) }
                }
            }
        }
    }
}
