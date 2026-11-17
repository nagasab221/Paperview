package com.paperview.mobile.book

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.paperview.mobile.ui.theme.PaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Volume-key page turns: set by the reader while it's open. */
object BookKeys {
    /** Called with true for "next"; returns whether the key was used. */
    var handler: ((Boolean) -> Boolean)? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(vm: BookViewModel, dark: Boolean, onClose: () -> Unit) {
    val style = vm.style
    val colors = style.colors.resolve(dark)
    val bg = Color(colors.bg)
    val ink = Color(colors.ink)
    val muted = Color(colors.muted)
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var goTo by remember { mutableStateOf(false) }
    val book = vm.book

    ReaderWindow(style, menu || sheet != null)
    BackHandler { if (sheet != null) sheet = null else if (menu) menu = false else onClose() }

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            vm.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                Text("Couldn't open this book", style = MaterialTheme.typography.titleLarge, color = ink)
                Spacer(Modifier.height(8.dp))
                Text(vm.error!!, color = muted)
                TextButton(onClick = onClose) { Text("Back to Library") }
            }
            book == null -> Loading(muted, "Opening the book…")
            book.isComic -> ComicPages(vm, bg, muted, onTapMiddle = { menu = !menu })
            else -> TextPages(vm, ink, muted, onTapMiddle = { menu = !menu })
        }

        AnimatedVisibility(menu && book != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(vm, bg, ink, muted, onClose = onClose, onContents = { sheet = "contents" }, onStyle = { sheet = "style" })
        }
        AnimatedVisibility(menu && book != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomBar(vm, bg, ink, muted, onGoTo = { goTo = true })
        }
    }

    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PaperTheme.colors.surface,
        ) {
            when (sheet) {
                "contents" -> ContentsSheet(vm) { sheet = null; menu = false }
                else -> StyleSheet(vm)
            }
        }
    }
    if (goTo) GoToDialog(vm) { goTo = false }
}

/** Keeps the screen on and hides the system bars while reading, as asked. */
@Composable
private fun ReaderWindow(style: ReadingStyle, showBars: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(style.keepAwake) {
        view.keepScreenOn = style.keepAwake
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(style.fullScreen, showBars) {
        val window = activity?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (style.fullScreen && !showBars) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
}

@Composable
private fun Loading(muted: Color, text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = muted)
        Spacer(Modifier.height(10.dp))
        Text(text, color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Left third back, right third forward, the middle shows the menu. */
private fun Modifier.tapZones(onPrev: () -> Unit, onNext: () -> Unit, onMiddle: () -> Unit) =
    pointerInput(Unit) {
        detectTapGestures { o ->
            val w = size.width
            when {
                o.x < w * 0.3f -> onPrev()
                o.x > w * 0.7f -> onNext()
                else -> onMiddle()
            }
        }
    }

@Composable
private fun TextPages(vm: BookViewModel, ink: Color, muted: Color, onTapMiddle: () -> Unit) {
    val style = vm.style
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val landscape = maxWidth > maxHeight
        val spread = landscape && style.spreads && !style.scroll
        val topPad = with(density) { 22.dp.toPx() }
        val footer = with(density) { 30.dp.toPx() }
        val fullW = with(density) { maxWidth.toPx() }
        val fullH = with(density) { maxHeight.toPx() }
        val pageW = if (spread) (fullW / 2).toInt() else fullW.toInt()
        val pageH = (fullH - topPad - footer).toInt()
        LaunchedEffect(pageW, pageH, vm.book, style.layoutKey()) { vm.layout(pageW, pageH) }

        val p = vm.paginator
        if (vm.seeking || p == null || vm.pages.isEmpty()) {
            Loading(muted, "Setting the pages…")
            return@BoxWithConstraints
        }
        val per = if (spread) 2 else 1

        if (style.scroll) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = vm.current)
            LaunchedEffect(list) {
                snapshotFlow { list.firstVisibleItemIndex }.collect { if (it != vm.current) vm.goTo(it) }
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().tapZones(onTapMiddle, onTapMiddle, onTapMiddle)) {
                itemsIndexed(vm.pages) { _, page ->
                    PageCanvas(page, p, vm.images, ink, muted, Modifier.fillMaxWidth().height(with(density) { pageH.toDp() }), 0f)
                }
            }
            Footer(vm, vm.current, muted, Modifier.align(Alignment.BottomCenter))
            return@BoxWithConstraints
        }

        val count = (vm.pages.size + per - 1) / per
        val pager = rememberPagerState(initialPage = vm.current / per) { count }
        LaunchedEffect(vm.current) {
            val want = vm.current / per
            if (pager.currentPage != want) pager.scrollToPage(want)
        }
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { sp ->
                if (vm.current / per != sp) vm.goTo(sp * per)
            }
        }
        DisposableEffect(style.volumeKeys, per) {
            BookKeys.handler = if (style.volumeKeys) { next -> vm.goTo((vm.current / per + if (next) 1 else -1) * per); true } else null
            onDispose { BookKeys.handler = null }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().tapZones(
                onPrev = { vm.goTo((vm.current / per - 1) * per) },
                onNext = { vm.goTo((vm.current / per + 1) * per) },
                onMiddle = onTapMiddle,
            ),
            beyondViewportPageCount = 1,
        ) { sp ->
            Row(Modifier.fillMaxSize()) {
                for (k in 0 until per) {
                    val index = sp * per + k
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        vm.pages.getOrNull(index)?.let { page ->
                            PageCanvas(page, p, vm.images, ink, muted, Modifier.fillMaxSize(), topPad)
                            Footer(vm, index, muted, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }
}

/** Draws one page's lines, images and rules. */
@Composable
private fun PageCanvas(page: Page, p: Paginator, images: ImageCache?, ink: Color, muted: Color, modifier: Modifier, top: Float) {
    Canvas(modifier) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            p.paint.color = ink.toArgb()
            for (item in page.items) {
                when (item) {
                    is PageItem.Text -> {
                        val l = item.layout
                        val lineTop = l.getLineTop(item.fromLine).toFloat()
                        val lineBottom = l.getLineBottom(item.toLine - 1).toFloat()
                        nc.save()
                        nc.translate(p.margin, top + item.y - lineTop)
                        nc.clipRect(-p.margin, lineTop, l.width + p.margin, lineBottom)
                        l.draw(nc)
                        nc.restore()
                    }
                    is PageItem.Image -> images?.get(item.id, item.rect.width().toInt())?.let { bmp ->
                        val r = android.graphics.RectF(item.rect.left, item.rect.top + top, item.rect.right, item.rect.bottom + top)
                        nc.drawBitmap(bmp, null, r, null)
                    }
                    is PageItem.Rule -> {
                        val cx = size.width / 2
                        drawLine(muted, androidx.compose.ui.geometry.Offset(cx - size.width * 0.15f, top + item.y), androidx.compose.ui.geometry.Offset(cx + size.width * 0.15f, top + item.y), 1.dp.toPx())
                    }
                }
            }
        }
    }
}

/** Chapter, pages left in it, and a thin progress line. */
@Composable
private fun Footer(vm: BookViewModel, index: Int, muted: Color, modifier: Modifier) {
    val chapter = vm.chapterAt(index)?.title
    val left = vm.pagesLeftInChapter(index)
    val progress = vm.progressAt(index)
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter ?: (vm.book?.title ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                buildString {
                    when (left) {
                        null -> {}
                        0 -> append("Last page in chapter · ")
                        1 -> append("1 page left in chapter · ")
                        else -> append("$left pages left in chapter · ")
                    }
                    append("${(progress * 100).toInt()}%")
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(muted.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(muted))
        }
    }
}

@Composable
private fun ComicPages(vm: BookViewModel, bg: Color, muted: Color, onTapMiddle: () -> Unit) {
    val comic = vm.book?.content as? Content.Comic ?: return
    val file = vm.file ?: return
    val pager = rememberPagerState(initialPage = vm.current) { comic.pages.size }
    LaunchedEffect(vm.current) { if (pager.currentPage != vm.current) pager.scrollToPage(vm.current) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { if (it != vm.current) vm.goTo(it) } }
    DisposableEffect(vm.style.volumeKeys) {
        BookKeys.handler = if (vm.style.volumeKeys) { next -> vm.goTo(vm.current + if (next) 1 else -1); true } else null
        onDispose { BookKeys.handler = null }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().tapZones({ vm.goTo(vm.current - 1) }, { vm.goTo(vm.current + 1) }, onTapMiddle),
            beyondViewportPageCount = 1,
        ) { i ->
            val bmp by produceState<android.graphics.Bitmap?>(null, i, widthPx) {
                value = withContext(Dispatchers.IO) {
                    Ebooks.comicPage(file, comic.pages[i])?.let { bytes ->
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= widthPx) s *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = muted)
            }
        }
    }
}

@Composable
private fun TopBar(
    vm: BookViewModel,
    bg: Color,
    ink: Color,
    muted: Color,
    onClose: () -> Unit,
    onContents: () -> Unit,
    onStyle: () -> Unit,
) {
    val marked = vm.bookmarkOn(vm.current) != null
    Column(Modifier.fillMaxWidth().background(bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Library", tint = ink) }
            Column(Modifier.weight(1f)) {
                Text(vm.book?.title ?: "", color = ink, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                vm.book?.author?.let { Text(it, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            IconButton(onClick = { vm.toggleBookmark(vm.current) }) {
                Icon(if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (marked) "Remove bookmark" else "Bookmark this page", tint = ink)
            }
            IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Outlined.List, "Contents and bookmarks", tint = ink) }
            if (vm.book?.isComic != true) {
                IconButton(onClick = onStyle) { Icon(Icons.Outlined.TextFields, "Reading options", tint = ink) }
            }
        }
    }
}

@Composable
private fun BottomBar(vm: BookViewModel, bg: Color, ink: Color, muted: Color, onGoTo: () -> Unit) {
    val n = vm.pageCount
    Column(Modifier.fillMaxWidth().background(bg).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 18.dp, vertical = 8.dp)) {
        var drag by remember(vm.current) { mutableFloatStateOf(vm.current.toFloat()) }
        if (n > 1) {
            Slider(
                value = drag.coerceIn(0f, (n - 1).toFloat()),
                onValueChange = { drag = it },
                onValueChangeFinished = { vm.goTo(drag.toInt()) },
                valueRange = 0f..(n - 1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = muted.copy(alpha = 0.3f)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("Page ${drag.toInt() + 1}")
                    if (vm.laidOut || vm.book?.isComic == true) append(" of $n") else append(" of …")
                    append(" · ${(vm.progressAt(drag.toInt()) * 100).toInt()}%")
                },
                color = ink,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGoTo) { Text("Go to page", color = ink) }
        }
        vm.chapterAt(drag.toInt())?.let { Text(it.title, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun GoToDialog(vm: BookViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = vm.pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '%' }.take(6) },
                    singleLine = true,
                    label = { Text("1 – $n, or a percentage like 40%") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = text.trim()
                if (t.endsWith("%")) {
                    t.dropLast(1).toIntOrNull()?.let { pct -> vm.goTo(((n - 1) * pct.coerceIn(0, 100) / 100f).toInt()) }
                } else {
                    t.toIntOrNull()?.let { vm.goTo(it - 1) }
                }
                onDismiss()
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ContentsSheet(vm: BookViewModel, onJump: () -> Unit) {
    val book = vm.book ?: return
    var tab by remember { mutableIntStateOf(if (book.chapters.isEmpty()) 1 else 0) }
    val colors = PaperTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        TabRow(selectedTabIndex = tab, containerColor = colors.surface, contentColor = colors.accent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Contents") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks (${vm.entry?.bookmarks?.size ?: 0})") })
        }
        val here = vm.chapterAt(vm.current)
        LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
            if (tab == 0) {
                if (book.chapters.isEmpty()) item { Text("This book has no table of contents.", Modifier.padding(20.dp), color = colors.muted) }
                itemsIndexed(book.chapters) { _, c ->
                    Text(
                        c.title,
                        Modifier.fillMaxWidth().clickable { vm.goToChapter(c); onJump() }.padding(horizontal = 20.dp, vertical = 13.dp),
                        color = if (c == here) colors.accent else colors.ink,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val marks = vm.entry?.bookmarks.orEmpty()
                if (marks.isEmpty()) {
                    item {
                        Text(
                            "No bookmarks yet. Tap the middle of a page, then the bookmark icon, to mark it.",
                            Modifier.padding(20.dp),
                            color = colors.muted,
                        )
                    }
                }
                itemsIndexed(marks) { _, b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.goToSpot(b.spot); onJump() }.padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            book.chapterAt(b.spot.block)?.let { Text(it.title, style = MaterialTheme.typography.labelMedium, color = colors.muted, maxLines = 1) }
                            Text(b.label, style = MaterialTheme.typography.bodyMedium, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { vm.removeBookmark(b) }) { Text("Remove", color = colors.muted) }
                    }
                }
            }
        }
    }
}

/** Aa: how the book looks and reads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSheet(vm: BookViewModel) {
    val s = vm.style
    val colors = PaperTheme.colors
    val context = LocalContext.current
    val activity = context as? Activity
    fun set(n: ReadingStyle) = vm.updateStyle(n)

    @Composable
    fun label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = colors.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

    @Composable
    fun chip(text: String, on: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .padding(end = 8.dp, bottom = 8.dp)
                .background(if (on) colors.accent else Color.Transparent, MaterialTheme.shapes.small)
                .then(if (on) Modifier else Modifier.border(1.dp, colors.hairline, MaterialTheme.shapes.small))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(text, color = if (on) colors.onAccent else colors.ink, style = MaterialTheme.typography.titleSmall) }
    }

    @Composable
    fun toggle(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Switch(on, change, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent))
        }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(560.dp)) {
        item {
            label("Text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                chip("A−", false) { set(s.copy(size = (s.size - 1).coerceAtLeast(ReadingStyle.MIN_SIZE))) }
                Text("${s.size.toInt()}", Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), color = colors.ink, style = MaterialTheme.typography.titleMedium)
                chip("A+", false) { set(s.copy(size = (s.size + 1).coerceAtMost(ReadingStyle.MAX_SIZE))) }
            }
            label("Page colour")
            FlowRow {
                PageColors.entries.forEach { c ->
                    val shown = if (c == PageColors.Auto) null else c
                    Box(
                        Modifier
                            .padding(end = 10.dp, bottom = 8.dp)
                            .size(width = 58.dp, height = 40.dp)
                            .background(shown?.let { Color(it.bg) } ?: colors.background, MaterialTheme.shapes.small)
                            .border(if (s.colors == c) 2.dp else 1.dp, if (s.colors == c) colors.accent else colors.hairline, MaterialTheme.shapes.small)
                            .clickable { set(s.copy(colors = c)) },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.label, color = shown?.let { Color(it.ink) } ?: colors.ink, style = MaterialTheme.typography.labelMedium) }
                }
            }
            label("Brightness")
            var bright by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: -1f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = if (bright < 0) 0.5f else bright,
                    onValueChange = { v ->
                        bright = v
                        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) } }
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
                )
                TextButton(onClick = {
                    bright = -1f
                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
                }) { Text(if (bright < 0) "System" else "Use system", color = if (bright < 0) colors.accent else colors.muted) }
            }
            label("Typeface")
        }
        itemsIndexed(BookFont.entries) { _, f ->
            val face = remember(f) { FontFamily(f.typeface(context, false, false)) }
            Row(
                Modifier.fillMaxWidth().clickable { set(s.copy(font = f)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, fontFamily = face, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(f.blurb, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                if (s.font == f) Text("Selected", color = colors.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            label("Line spacing")
            FlowRow { Spacing.entries.forEach { sp -> chip(sp.label, s.spacing == sp) { set(s.copy(spacing = sp)) } } }
            label("Margins")
            FlowRow { Margins.entries.forEach { m -> chip(m.label, s.margins == m) { set(s.copy(margins = m)) } } }
            label("Paragraphs")
            FlowRow {
                chip("Indented", s.indent) { set(s.copy(indent = true)) }
                chip("Spaced", !s.indent) { set(s.copy(indent = false)) }
                chip("Justified", s.justify) { set(s.copy(justify = true)) }
                chip("Ragged right", !s.justify) { set(s.copy(justify = false)) }
            }
            label("Turning pages")
            FlowRow {
                chip("Pages", !s.scroll) { set(s.copy(scroll = false)) }
                chip("Scroll", s.scroll) { set(s.copy(scroll = true)) }
            }
            toggle("Two pages side by side", "When the phone is on its side", s.spreads) { set(s.copy(spreads = it)) }
            toggle("Volume keys turn pages", "Volume down = next page, up = back", s.volumeKeys) { set(s.copy(volumeKeys = it)) }
            toggle("Keep the screen on", "While a book is open", s.keepAwake) { set(s.copy(keepAwake = it)) }
            toggle("Full screen", "Hide the status and navigation bars while reading", s.fullScreen) { set(s.copy(fullScreen = it)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A cover for the shelf: the book's own, or its title set in type. */
@Composable
fun BookCover(entry: BookEntry, modifier: Modifier) {
    val context = LocalContext.current
    val colors = PaperTheme.colors
    val cover by produceState<android.graphics.Bitmap?>(null, entry.path) {
        value = withContext(Dispatchers.IO) { runCatching { BookStore.cover(context, entry, 360) }.getOrNull() }
    }
    Box(modifier.background(colors.surface).border(1.dp, colors.hairline), contentAlignment = Alignment.Center) {
        val bmp = cover
        if (bmp != null) {
            Image(bmp.asImageBitmap(), entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.title.ifBlank { File(entry.name).nameWithoutExtension }, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                entry.author?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2)
                }
            }
        }
    }
}
