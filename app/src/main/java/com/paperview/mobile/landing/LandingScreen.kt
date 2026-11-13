package com.paperview.mobile.landing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.paperview.mobile.cloud.B2Client
import com.paperview.mobile.cloud.CloudViewModel
import com.paperview.mobile.store.RecentEntry
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperEmptyState
import com.paperview.mobile.ui.components.PaperMasthead
import com.paperview.mobile.ui.components.PaperRow
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme
import java.text.DateFormat
import java.util.Date

/**
 * The Library: Recents (your documents as a shelf of first-page covers) and,
 * once a Backblaze bucket is set up and switched on, a Cloud page beside it.
 * The title and tab bar stay put; the pages swipe underneath.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandingScreen(
    recents: List<RecentEntry>,
    cloud: CloudViewModel,
    page: Int,
    onPageChange: (Int) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentEntry) -> Unit,
    onForgetRecent: (RecentEntry) -> Unit,
    onOpenCloudFile: (String, Long) -> Unit,
    onOpenLocalFile: (java.io.File) -> Unit,
    onBrowseFiles: (() -> Unit)?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = PaperTheme.colors
    val showCloud = cloud.active
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(
        initialPage = if (showCloud) page.coerceIn(0, 1) else 0,
        pageCount = { if (showCloud) 2 else 1 },
    )

    LaunchedEffect(cloud.config, showCloud) {
        // Re-read the disk every time the Library appears: a download from
        // another screen, or a file removed, should show without a network call.
        cloud.refreshCached()
        if (showCloud) cloud.listIfReady() else if (pager.currentPage != 0) pager.scrollToPage(0)
    }
    LaunchedEffect(pager.currentPage) { onPageChange(pager.currentPage) }

    val onCloudPage = showCloud && pager.currentPage == 1

    Column(Modifier.fillMaxSize().background(colors.background)) {
        PaperMasthead(
            title = "Library",
            subtitle = when {
                onCloudPage -> cloud.config.bucket
                recents.isEmpty() -> null
                else -> "${recents.size} document${if (recents.size == 1) "" else "s"}"
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            actions = {
                Row {
                    onBrowseFiles?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Outlined.FolderOpen, "Browse files", tint = colors.ink)
                        }
                    }
                    IconButton(onClick = onOpenPicker) {
                        Icon(Icons.Outlined.Add, "Open a PDF", tint = colors.ink)
                    }
                }
            },
        )

        if (showCloud) {
            LibraryTabs(
                selected = pager.currentPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            key = { it },
        ) { index ->
            if (index == 0) {
                RecentsPage(
                    recents = recents,
                    onOpenPicker = onOpenPicker,
                    onOpenRecent = onOpenRecent,
                    onForgetRecent = onForgetRecent,
                    contentPadding = contentPadding,
                )
            } else {
                CloudPage(
                    cloud = cloud,
                    onOpenFile = onOpenCloudFile,
                    onOpenLocal = onOpenLocalFile,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

/** Recents | Cloud, as text tabs over a hairline with an accent underline. */
@Composable
private fun LibraryTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = PaperTheme.colors
    TabRow(
        selectedTabIndex = selected,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        containerColor = colors.background,
        contentColor = colors.accent,
        indicator = { positions ->
            if (selected < positions.size) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[selected]),
                    height = 2.dp,
                    color = colors.accent,
                )
            }
        },
        divider = { Hairline() },
    ) {
        listOf("Recents", "Cloud").forEachIndexed { i, label ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                selectedContentColor = colors.accent,
                unselectedContentColor = colors.muted,
                text = { Text(label, style = MaterialTheme.typography.titleSmall) },
            )
        }
    }
}

/** The shelf of covers. */
@Composable
private fun RecentsPage(
    recents: List<RecentEntry>,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentEntry) -> Unit,
    onForgetRecent: (RecentEntry) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (recents.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PaperEmptyState(
                    title = "Nothing here yet",
                    body = "Open a PDF and it appears on this shelf, showing its first page " +
                        "and the page you left off on.",
                    action = "Open a PDF" to onOpenPicker,
                )
            }
        } else {
            items(recents, key = { it.uri }) { entry ->
                CoverTile(
                    entry = entry,
                    onOpen = { onOpenRecent(entry) },
                    onForget = { onForgetRecent(entry) },
                )
            }
        }
    }
}

/** The Backblaze bucket and whatever of it is already downloaded. */
@Composable
private fun CloudPage(
    cloud: CloudViewModel,
    onOpenFile: (String, Long) -> Unit,
    onOpenLocal: (java.io.File) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            SectionHeading(
                "Saved to cloud",
                trailing = {
                    InlineTextButton("Refresh", enabled = !cloud.busy) { cloud.refresh() }
                },
            )
        }
        item { CloudSection(cloud = cloud, onOpenFile = onOpenFile, onOpenLocal = onOpenLocal) }
    }
}

/** Small caption dividing the cloud list into on-device and bucket-only. */
@Composable
private fun CloudGroupLabel(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = PaperTheme.colors.muted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = PaperTheme.colors.muted,
        )
    }
    Hairline()
}

/**
 * A document already on the device. Backed by the file itself, so it renders
 * and opens with no network whatsoever.
 */
@Composable
private fun LocalFileRow(
    cloud: CloudViewModel,
    doc: com.paperview.mobile.cloud.LocalDoc,
    onOpen: (java.io.File) -> Unit,
) {
    val colors = PaperTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    PaperRow(
        title = doc.name,
        subtitle = "On this device · ${B2Client.formatSize(doc.size)}",
        icon = Icons.Outlined.OfflinePin,
        showChevron = false,
        onClick = { onOpen(doc.file) },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "More",
                        tint = colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text("Remove download", style = MaterialTheme.typography.bodyMedium)
                        },
                        onClick = { menuOpen = false; cloud.removeLocal(doc.file) },
                    )
                }
            }
        },
    )
    Hairline()
}

/**
 * One bucket file, showing which of four states it is in: on the device,
 * waiting, downloading (with its own progress), or cloud-only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudFileRow(
    cloud: CloudViewModel,
    file: com.paperview.mobile.cloud.CloudFile,
    onOpenFile: (String, Long) -> Unit,
) {
    val colors = PaperTheme.colors
    val downloaded = cloud.isDownloaded(file.name)
    val transfer = cloud.transfer?.takeIf { it.name == file.name }
    val queued = cloud.isQueued(file.name)
    val picked = cloud.selected.contains(file.name)
    var menuOpen by remember { mutableStateOf(false) }

    val size = B2Client.formatSize(file.size)
    val date = if (file.uploadedMs > 0) {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.uploadedMs))
    } else null

    val subtitle = when {
        transfer != null -> buildString {
            append("Downloading ${(transfer.fraction * 100).toInt()}%")
            if (transfer.bytesPerSecond > 0) {
                append(" · ${B2Client.formatSize(transfer.bytesPerSecond.toLong())}/s")
            }
        }
        queued -> "Waiting to download…"
        downloaded -> "On this device · $size"
        else -> "Tap to download · $size" + (date?.let { " · $it" } ?: "")
    }

    PaperRow(
        title = file.name,
        subtitle = subtitle,
        icon = when {
            transfer != null || queued -> Icons.Outlined.Downloading
            downloaded -> Icons.Outlined.OfflinePin
            else -> Icons.Outlined.CloudDownload
        },
        showChevron = false,
        onClick = { if (transfer == null && !queued) onOpenFile(file.name, file.size) },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "More",
                        tint = if (picked) colors.accent else colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (downloaded) {
                        DropdownMenuItem(
                            text = { Text("Remove download", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { menuOpen = false; cloud.removeDownload(file.name) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Download", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                menuOpen = false
                                cloud.download(file.name, file.size, openAfter = false)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (picked) "Deselect" else "Select",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = { menuOpen = false; cloud.toggleSelected(file.name) },
                    )
                }
            }
        },
    )
    // A hairline-thin bar directly under the row it belongs to.
    if (transfer != null) {
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.accent,
            trackColor = colors.hairline,
        )
    } else if (queued) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.accent,
            trackColor = colors.hairline,
        )
    } else {
        Hairline()
    }
}

/** One document on the shelf: cover, title, and where you were. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(entry: RecentEntry, onOpen: () -> Unit, onForget: () -> Unit) {
    val colors = PaperTheme.colors
    val density = LocalDensity.current
    val coverWidthPx = remember(density) { with(density) { 148.dp.toPx().toInt() } }
    val cover by rememberCover(entry.uri, coverWidthPx)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .padding(bottom = 20.dp)
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.77f)
                .background(colors.surface)
                // A hairline instead of a shadow: the page edge is the frame.
                .border(1.dp, colors.hairline),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = cover
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                // Rendering, or unreadable — stay quiet rather than flash a spinner.
                Text(
                    entry.name.substringAfterLast('.', "PDF").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from library", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; onForget() },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.name.substringBeforeLast('.').ifBlank { entry.name },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append("page ${entry.page + 1}")
                if (entry.timestamp > 0) {
                    append(" · ")
                    append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.timestamp)))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            maxLines = 1,
        )
    }
}

/** The Backblaze bucket, as a list beneath the shelf. */
@Composable
private fun CloudSection(
    cloud: CloudViewModel,
    onOpenFile: (String, Long) -> Unit,
    onOpenLocal: (java.io.File) -> Unit,
) {
    val colors = PaperTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        val hasLocal = cloud.localFiles.isNotEmpty()
        when {
            // Anything already downloaded is shown even with no credentials or
            // no signal; only the bucket half needs the network.
            hasLocal && (!cloud.config.ready || (cloud.busy && cloud.files.isEmpty())) -> {
                CloudGroupLabel("On this device", cloud.localFiles.size)
                cloud.localFiles.forEach { doc -> LocalFileRow(cloud, doc, onOpenLocal) }
                if (cloud.busy) {
                    Text(
                        "Checking your bucket…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            !cloud.config.ready -> Text(
                "Add your Backblaze key in Settings to keep documents in your own bucket.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            cloud.busy && cloud.files.isEmpty() -> Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    cloud.status.ifBlank { "Connecting…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }

            cloud.files.isEmpty() && cloud.loadedOnce -> Text(
                "Bucket is empty. Use \"Save to cloud\" while reading to upload a document.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            else -> Column {
                if (cloud.selected.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${cloud.selected.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        InlineTextButton("Download") { cloud.downloadSelected() }
                        InlineTextButton(
                            if (confirmDelete) "Confirm delete" else "Delete",
                        ) {
                            // Two taps: these files are the only copy in the bucket.
                            if (confirmDelete) {
                                cloud.deleteSelected(); confirmDelete = false
                            } else confirmDelete = true
                        }
                    }
                    Hairline()
                }

                // Split by where the file actually is. The on-device group is
                // read from disk rather than filtered out of the bucket
                // listing, so downloaded documents stay visible with no
                // network at all.
                val inCloud = cloud.files.filterNot { cloud.isDownloaded(it.name) }

                if (cloud.localFiles.isNotEmpty()) {
                    CloudGroupLabel("On this device", cloud.localFiles.size)
                    cloud.localFiles.forEach { doc -> LocalFileRow(cloud, doc, onOpenLocal) }
                }
                if (inCloud.isNotEmpty()) {
                    CloudGroupLabel("In your bucket", inCloud.size)
                    inCloud.forEach { file -> CloudFileRow(cloud, file, onOpenFile) }
                }
            }
        }

        cloud.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                // With local copies present a failed listing is an
                // inconvenience, not a failure — don't shout about it.
                color = if (hasLocal) colors.muted else MaterialTheme.colorScheme.error,
            )
            if (hasLocal) {
                Text(
                    "The documents above are on this device and open normally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            InlineTextButton("Dismiss") { cloud.clearError() }
        }

        if (cloud.config.ready && cloud.files.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val used = cloud.files.sumOf { it.size }
            Text(
                "${cloud.files.size} files · ${B2Client.formatSize(used)} of 10 GB free tier",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}
