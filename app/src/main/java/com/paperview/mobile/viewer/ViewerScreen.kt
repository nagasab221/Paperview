package com.paperview.mobile.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.paperview.mobile.pdf.PageCache
import com.paperview.mobile.pdf.Zoom
import com.paperview.mobile.pdf.snapPx
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.theme.PaperTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Actions the viewer hands back to the host activity — things that need an
 * Activity or a system service rather than view state.
 */
data class ViewerActions(
    val onHome: () -> Unit,
    val onPrint: () -> Unit,
    val onShare: () -> Unit,
    val onSaveAs: () -> Unit,
    /** Null while the cloud is off or not set up: the menu item is hidden. */
    val onUploadToCloud: (() -> Unit)?,
    val onOpenSettings: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: ViewerViewModel, actions: ViewerActions) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var chromeVisible by remember { mutableStateOf(true) }
    var pageDialog by remember { mutableStateOf(false) }

    // Leaving the document or backgrounding it should not lose your place.
    // Debounced: the page counter ticks on every frame of a fling, and each
    // write would otherwise hit disk.
    LaunchedEffect(vm.currentPage, vm.zoom, vm.rotation) {
        kotlinx.coroutines.delay(600)
        vm.persistState()
    }

    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            vm.hasSelection -> vm.clearSelection()
            vm.searchOpen -> { vm.searchOpen = false; vm.clearSearch() }
            vm.canGoBack -> vm.goBack()
            else -> actions.onHome()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SidebarSheet(
                vm = vm,
                // A drawer sheet is composed whether or not it is on screen, so
                // without this its thumbnail list starts rendering pages the
                // moment a document opens — on the one thread PDFium is allowed
                // to use, ahead of the page actually being read. It only
                // escaped notice because a document with an outline opens on
                // the Contents tab instead.
                opened = drawerState.targetValue == DrawerValue.Open,
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        // The bars float above the page rather than taking layout space.
        //
        // As a Scaffold's topBar and bottomBar they inset the content, so
        // toggling them resized the reading area: `onSizeChanged` fired,
        // `setViewport` recomputed the fit-to-page scale against the new height
        // and re-clamped the scroll. Tapping to reveal the bars visibly shifted
        // the page, and tapping again shifted it back. Here the reading area is
        // a constant — the system-bar inset, nothing else — so the bars coming
        // and going cannot move the page at all.
        Box(
            Modifier
                .fillMaxSize()
                .background(PaperTheme.colors.background),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                when (val s = vm.state) {
                    is DocState.Loading -> CenteredSpinner()
                    is DocState.Failed -> CenteredMessage(s.message)
                    is DocState.NeedPassword -> PasswordPrompt(
                        wrong = s.wrong,
                        onSubmit = { vm.unlock(context, it) },
                        onCancel = actions.onHome,
                    )
                    else -> PdfViewport(vm, onTapEmptySpace = { chromeVisible = !chromeVisible })
                }

                if (vm.hasSelection) {
                    SelectionBar(
                        vm = vm,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                TopChrome(
                    vm = vm,
                    actions = actions,
                    onOpenContents = { scope.launch { drawerState.open() } },
                )
            }

            AnimatedVisibility(
                visible = chromeVisible && !vm.hasSelection,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomBar(vm, onPageTap = { pageDialog = true })
            }
        }
    }

    if (pageDialog) {
        GoToPageDialog(
            pageCount = vm.pageCount,
            current = vm.currentPage,
            onDismiss = { pageDialog = false },
            onGo = { vm.goToPage(it, pushHistory = true); pageDialog = false },
        )
    }
}

/**
 * Title, navigation and the actions, as one solid band above the page.
 *
 * Opaque on purpose: it sits over the paper rather than beside it, and text
 * showing through would make both the controls and the page harder to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopChrome(
    vm: ViewerViewModel,
    actions: ViewerActions,
    onOpenContents: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = PaperTheme.colors.background) {
        Column {
            TopAppBar(
                title = {
                    Text(
                        vm.title.ifBlank { "PaperView" }.substringBeforeLast('.'),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onHome) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Home")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenContents) {
                        Icon(Icons.AutoMirrored.Filled.List, "Contents")
                    }
                    IconButton(onClick = { vm.searchOpen = !vm.searchOpen }) {
                        Icon(Icons.Default.Search, "Search")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        ViewerMenu(
                            vm = vm,
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            actions = actions,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PaperTheme.colors.background,
                    titleContentColor = PaperTheme.colors.ink,
                    actionIconContentColor = PaperTheme.colors.ink,
                    navigationIconContentColor = PaperTheme.colors.ink,
                ),
            )
            if (vm.searchOpen) SearchBar(vm)
            Hairline()
        }
    }
}

@Composable
private fun SearchBar(vm: ViewerViewModel) {
    var text by remember { mutableStateOf(vm.query) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; vm.search(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Find in document") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.nextHit() }),
                )
                Text(
                    text = if (vm.hits.isEmpty()) {
                        if (vm.query.isBlank()) "" else if (vm.searching) "…" else "0"
                    } else {
                        "${vm.hitIndex + 1}/${vm.hits.size}"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { vm.prevHit() }, enabled = vm.hits.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowUp, "Previous match")
                }
                IconButton(onClick = { vm.nextHit() }, enabled = vm.hits.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowDown, "Next match")
                }
                IconButton(onClick = { vm.searchOpen = false; vm.clearSearch() }) {
                    Icon(Icons.Default.Close, "Close search")
                }
            }
            // Scanning the rest of the document keeps running after the first
            // hit shows up, so surface how far along it is.
            if (vm.searching && vm.pageCount > 0) {
                LinearProgressIndicator(
                    progress = { vm.searchScanned.toFloat() / vm.pageCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BottomBar(vm: ViewerViewModel, onPageTap: () -> Unit) {
    val colors = PaperTheme.colors
    Surface(color = colors.background) {
        Column {
            Hairline()
            Row(
                // Floating free of the Scaffold, the bar has to keep its own
                // controls clear of the navigation bar.
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The page indicator reads as a caption, not a button.
                    TextButton(onClick = onPageTap) {
                        Text(
                            "${vm.currentPage + 1} of ${vm.pageCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.ink,
                        )
                    }
                    if (vm.peekSpot != null) PeekButton(vm)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZoomAction("Fit") { vm.applyZoom(Zoom.FitPage) }
                    ZoomAction("Width") { vm.applyZoom(Zoom.FitWidth) }
                    ZoomAction("−") { vm.pinch(0.8f, vm.viewportW / 2, vm.viewportH / 2) }
                    ZoomAction("+") { vm.pinch(1.25f, vm.viewportW / 2, vm.viewportH / 2) }
                }
            }
        }
    }
}

/**
 * The peek button. A tap jumps to the saved spot and the next tap comes back;
 * holding shows the spot only for as long as the finger stays down, then puts
 * the view back exactly where it was.
 */
@Composable
private fun PeekButton(vm: ViewerViewModel) {
    val colors = PaperTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // When a tap last jumped to the spot. Coming straight back by tapping
    // again is exactly what holding does, so that's when the tip is useful.
    var jumpedAt by remember { mutableStateOf(0L) }

    val onTap by rememberUpdatedState {
        val now = android.os.SystemClock.uptimeMillis()
        if (vm.togglePeekJump()) {
            jumpedAt = now
        } else if (now - jumpedAt < QUICK_RETURN_MS) {
            scope.launch {
                if (vm.takePeekTip(limit = 3)) {
                    android.widget.Toast.makeText(
                        context,
                        "Tip: press and hold the eye to peek. Let go and you're right back.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val active = vm.peeking || vm.peekAway
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (vm.peeking) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .semantics {
                role = Role.Button
                contentDescription = if (vm.peekAway) "Back from peek spot" else "Peek"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    var lifted = false
                    // Up before the hold delay is a tap; still down after it is a peek.
                    val early = withTimeoutOrNull(HOLD_TO_PEEK_MS) {
                        lifted = waitForUpOrCancellation() != null
                        true
                    }
                    if (early != null) {
                        if (lifted) onTap()
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.beginPeek()
                    try {
                        // Follow the finger anywhere, not just over the button:
                        // sliding off it while reading the answer mustn't end the peek.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        vm.endPeek()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (vm.peekAway) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.Visibility,
            contentDescription = null,
            tint = if (active) colors.accent else colors.ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** How long the peek button must be held before it peeks instead of jumping. */
private const val HOLD_TO_PEEK_MS = 220L

/** Tapping back within this long after jumping is a peek done the slow way. */
private const val QUICK_RETURN_MS = 15_000L

@Composable
private fun ZoomAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = PaperTheme.colors.muted,
        )
    }
}

@Composable
private fun SelectionBar(vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                vm.copySelection { ok ->
                    if (ok) android.widget.Toast
                        .makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            TextButton(onClick = { vm.selectAll() }) { Text("Select all") }
            TextButton(onClick = { vm.clearSelection() }) { Text("Done") }
        }
    }
}

@Composable
private fun ViewerMenu(
    vm: ViewerViewModel,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: ViewerActions,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (vm.viewMode == ViewMode.Continuous) "Single page" else "Continuous") },
            onClick = {
                vm.viewMode =
                    if (vm.viewMode == ViewMode.Continuous) ViewMode.Single else ViewMode.Continuous
                onDismiss()
            },
        )
        DropdownMenuItem(text = { Text("Rotate left") }, onClick = { vm.rotateLeft(); onDismiss() })
        DropdownMenuItem(text = { Text("Rotate right") }, onClick = { vm.rotateRight(); onDismiss() })
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (vm.peekSpot == null) "Set peek spot here" else "Move peek spot here") },
            onClick = {
                val first = vm.peekSpot == null
                vm.savePeekSpot()
                onDismiss()
                android.widget.Toast.makeText(
                    context,
                    if (first) "Peek spot saved. Tap the eye to jump there, hold it to peek."
                    else "Peek spot moved here",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            },
        )
        if (vm.peekSpot != null) {
            DropdownMenuItem(
                text = { Text("Remove peek spot") },
                onClick = { vm.removePeekSpot(); onDismiss() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Print") }, onClick = { actions.onPrint(); onDismiss() })
        DropdownMenuItem(text = { Text("Share") }, onClick = { actions.onShare(); onDismiss() })
        DropdownMenuItem(text = { Text("Save a copy") }, onClick = { actions.onSaveAs(); onDismiss() })
        actions.onUploadToCloud?.let { upload ->
            DropdownMenuItem(
                text = { Text("Save to cloud") },
                onClick = { upload(); onDismiss() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = { actions.onOpenSettings(); onDismiss() },
        )
    }
}

/** Thumbnails and the document outline, in one drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarSheet(vm: ViewerViewModel, opened: Boolean, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(if (vm.toc.isEmpty()) 0 else 1) }
    // Latched: once the drawer has been opened the lists stay composed, so
    // closing it doesn't throw away thumbnails that have already been rendered.
    var everOpened by remember { mutableStateOf(false) }
    if (opened) everOpened = true
    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
        // The drawer sits outside the screen's own inset handling, so it gets
        // none of it — without this the tab row hides under the status bar.
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pages") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Contents") },
                    enabled = vm.toc.isNotEmpty(),
                )
            }
            if (!everOpened) return@Column
            if (tab == 0) ThumbnailList(vm, onClose) else TocList(vm, onClose)
        }
    }
}

@Composable
private fun ThumbnailList(vm: ViewerViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val doc = vm.doc ?: return
    val density = LocalDensity.current
    val widthPx = remember(density) { with(density) { 120.dp.toPx() } }
    // A separate, small cache: thumbnails must not evict the pages being read.
    val thumbs = remember(doc) { PageCache(doc, scope, maxBytes = 12 * 1024 * 1024) }
    val version by thumbs.version

    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.pageCount) { i ->
            val box = doc.pages[i]
            val w = snapPx(widthPx)
            val h = snapPx(widthPx * box.heightPt / box.widthPt)
            if (version >= 0) thumbs.request(i, w, h, vm.rotation)
            val bmp = thumbs.exact(i, w, vm.rotation)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.goToPage(i, pushHistory = true); onClose() }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(120.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (bmp != null && !bmp.isRecycled) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page ${i + 1}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = with(density) { h.toDp() }),
                        )
                    }
                }
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == vm.currentPage) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TocList(vm: ViewerViewModel, onClose: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(vm.toc) { _, entry ->
            Text(
                text = entry.title.ifBlank { "Untitled" },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.goToPage(entry.page, pushHistory = true); onClose() }
                    .padding(
                        start = (12 + entry.depth * 14).dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GoToPageDialog(
    pageCount: Int,
    current: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var text by remember { mutableStateOf((current + 1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                singleLine = true,
                label = { Text("1 – $pageCount") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.let { onGo((it - 1).coerceIn(0, pageCount - 1)) } ?: onDismiss()
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
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
                    Spacer(Modifier.size(8.dp))
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
        confirmButton = { TextButton(onClick = { onSubmit(pw) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
    }
}
