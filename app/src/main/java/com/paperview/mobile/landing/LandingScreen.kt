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
 * once a cloud account is set up and switched on, a Cloud page beside it.
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
    onOpenLocalFile: (java.io.File) -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onOpenSettings: () -> Unit,
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

    LaunchedEffect(cloud.currentId, showCloud) {
        // Re-read the disk every time the Library appears: a download from
        // another screen, or a file removed, should show without a network call.
        cloud.refreshLocal()
        if (showCloud) {
            if (!cloud.listed && !cloud.busy && cloud.error == null) cloud.list()
        } else if (pager.currentPage != 0) pager.scrollToPage(0)
    }
    LaunchedEffect(pager.currentPage) { onPageChange(pager.currentPage) }

    val onCloudPage = showCloud && pager.currentPage == 1

    Column(Modifier.fillMaxSize().background(colors.background)) {
        PaperMasthead(
            title = "Library",
            subtitle = when {
                onCloudPage -> cloud.current?.summary
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
                    onOpenLocal = onOpenLocalFile,
                    onUploadFiles = onUploadFiles,
                    onUploadFolder = onUploadFolder,
                    onOpenSettings = onOpenSettings,
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
