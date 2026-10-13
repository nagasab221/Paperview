package com.paperview.mobile.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.paperview.mobile.pdf.Box as PdfBox
import com.paperview.mobile.pdf.PREVIEW_MAX_WIDTH_PX
import com.paperview.mobile.pdf.Rotation
import com.paperview.mobile.pdf.TILE_PX
import com.paperview.mobile.pdf.rectToView
import com.paperview.mobile.pdf.snapPx
import com.paperview.mobile.ui.theme.Marks
import com.paperview.mobile.ui.theme.PaperTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The page surface: pan, pinch, fling, tap-to-follow-links, long-press to
 * select. Pages, selection and search highlights are all painted into one
 * canvas so they share a single transform and can never drift apart.
 */
@Composable
fun PdfViewport(
    vm: ViewerViewModel,
    onTapEmptySpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density = density) }
    // Held in a state holder rather than a local: the gesture lambdas outlive
    // any single composition and must all see the same in-flight fling.
    val flingJob = remember { mutableStateOf<Job?>(null) }

    // Search highlights for the pages currently on screen, refreshed when the
    // hit list or the current hit changes.
    val searchRects = remember { mutableStateMapOf<Int, List<Pair<PdfBox, Boolean>>>() }

    val doc = vm.doc
    val cache = vm.cache
    val handleColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    // remember must run unconditionally, so keep a fallback and pick after.
    val fallbackVersion = remember { mutableIntStateOf(0) }
    val version by (cache?.version ?: fallbackVersion)

    // Navigation asked for a jump — apply it once and clear the request.
    LaunchedEffect(vm.pendingScroll) {
        vm.pendingScroll?.let {
            vm.scrollY = it
            vm.clampScroll()
            vm.pendingScroll = null
        }
    }

    // A new search, or a move to a different match, invalidates every cached
    // highlight; scrolling only needs the pages that just came into view.
    LaunchedEffect(vm.hits.size, vm.hitIndex, doc) {
        searchRects.clear()
    }
    LaunchedEffect(vm.hits.size, vm.hitIndex, vm.currentPage, doc) {
        if (doc == null || vm.hits.isEmpty()) return@LaunchedEffect
        val range = vm.layout.visiblePages(vm.scrollY, vm.viewportH, vm.viewportH)
        for (p in range) {
            if (!searchRects.containsKey(p)) searchRects[p] = vm.searchOverlay(p)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PaperTheme.colors.viewport)
            .onSizeChanged { vm.setViewport(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(doc) {
                detectTapGestures(
                    onTap = { pos ->
                        scope.launch {
                            val handled = followLinkAt(vm, pos, uriHandler::openUri)
                            if (!handled) {
                                if (vm.hasSelection) vm.clearSelection() else onTapEmptySpace()
                            }
                        }
                    },
                    onDoubleTap = { pos -> vm.toggleZoom(pos.x, pos.y) },
                    onLongPress = { pos ->
                        scope.launch {
                            val hit = charAt(vm, pos, snapVertical = false) ?: return@launch
                            vm.selectWordAt(hit.first, hit.second)
                        }
                    },
                )
            }
            .pointerInput(doc) {
                viewportGestures(
                    onGestureStart = { flingJob.value?.cancel() },
                    // A drag that starts on a selection handle adjusts the
                    // selection; anything else pans. Deciding once, on the
                    // initial touch, is what keeps the two from fighting.
                    grabHandle = { pos -> handleUnder(vm, pos) },
                    onHandleDrag = { which, pos ->
                        scope.launch {
                            val hit = charAt(vm, pos, snapVertical = true) ?: return@launch
                            vm.dragHandle(which, hit.first, hit.second)
                        }
                    },
                    onPanZoom = { pan, zoom, centroid ->
                        if (zoom != 1f) {
                            vm.pinch(zoom, centroid.x, centroid.y)
                        }
                        if (pan != Offset.Zero) {
                            vm.scrollX -= pan.x
                            val wanted = vm.scrollY - pan.y
                            val bounds = vm.scrollBounds()
                            val overscroll = when {
                                wanted > bounds.endInclusive -> wanted - bounds.endInclusive
                                wanted < bounds.start -> wanted - bounds.start
                                else -> 0f
                            }
                            vm.scrollY = wanted
                            vm.clampScroll()
                            if (overscroll != 0f) vm.flipIfPastEdge(overscroll)
                            vm.syncCurrentPageToScroll()
                        }
                    },
                    onFling = { velocityY ->
                        flingJob.value?.cancel()
                        flingJob.value = scope.launch {
                            val state =
                                AnimationState(initialValue = vm.scrollY, initialVelocity = -velocityY)
                            state.animateDecay(decay) {
                                val bounds = vm.scrollBounds()
                                vm.scrollY = value
                                vm.clampScroll()
                                vm.syncCurrentPageToScroll()
                                // Stop cleanly once we're pinned at an edge.
                                if (vm.scrollY <= bounds.start || vm.scrollY >= bounds.endInclusive) {
                                    cancelAnimation()
                                }
                            }
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading `version` here is what schedules a repaint when a page
            // render lands; the comparison itself never fires.
            if (version < 0) return@Canvas
            val d = doc ?: return@Canvas
            val c = cache ?: return@Canvas
            val l = vm.layout
            val rot = vm.rotation
            if (l.count == 0) return@Canvas
            // Centres the block when it is narrower/shorter than the screen.
            val originX = vm.contentOriginX()
            val originY = vm.contentOriginY()

            val overscan = size.height * OVERSCAN
            val visible = if (vm.viewMode == ViewMode.Single) {
                // Single-page mode shows exactly one page, even when a short
                // page would otherwise leave the next one peeking in.
                vm.currentPage..vm.currentPage
            } else {
                l.visiblePages(vm.scrollY, size.height, overscan)
            }
            if (visible.isEmpty()) return@Canvas
            c.keepOnly(
                (visible.first - KEEP_PAGES).coerceAtLeast(0)..
                    (visible.last + KEEP_PAGES).coerceAtMost(l.count - 1),
                rot,
            )

            // Tiles are keyed by the page's full display width, so they only
            // survive while the zoom level holds.
            c.keepTiles(visible.associateWith { snapPx(l.pageWidthPx(it)) }, rot)

            for (i in visible) {
                val wPx = snapPx(l.pageWidthPx(i))
                val hPx = snapPx(l.pageHeightPx(i))
                val left = originX + l.pageLeftPx(i) - vm.scrollX
                val top = originY + l.pageTopPx(i) - vm.scrollY

                // Backdrop: a whole-page render, capped so it stays cheap.
                val previewW = minOf(wPx, PREVIEW_MAX_WIDTH_PX)
                val previewH = snapPx(previewW * hPx.toFloat() / wPx)
                c.request(i, previewW, previewH, rot)
                val bmp = c.exact(i, previewW, rot) ?: c.nearest(i, previewW, rot)

                if (bmp != null && !bmp.isRecycled) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(wPx, hPx),
                    )
                } else {
                    // Blank paper while the render is in flight — better than a
                    // hole, and the page never jumps when the bitmap arrives.
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(wPx.toFloat(), hPx.toFloat()),
                    )
                }

                // Past the backdrop's resolution, overlay sharp tiles for the
                // part of the page actually on screen.
                if (wPx > previewW) {
                    val visLeft = (-left).coerceIn(0f, wPx.toFloat())
                    val visTop = (-top).coerceIn(0f, hPx.toFloat())
                    val visRight = (size.width - left).coerceIn(0f, wPx.toFloat())
                    val visBottom = (size.height - top).coerceIn(0f, hPx.toFloat())
                    if (visRight > visLeft && visBottom > visTop) {
                        val colFrom = (visLeft / TILE_PX).toInt()
                        val colTo = ((visRight - 1) / TILE_PX).toInt()
                        val rowFrom = (visTop / TILE_PX).toInt()
                        val rowTo = ((visBottom - 1) / TILE_PX).toInt()
                        for (col in colFrom..colTo) {
                            for (row in rowFrom..rowTo) {
                                c.requestTile(i, wPx, hPx, rot, col, row, TILE_PX)
                                val t = c.tile(i, wPx, rot, col, row) ?: continue
                                if (t.isRecycled) continue
                                drawImage(
                                    image = t.asImageBitmap(),
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(t.width, t.height),
                                    dstOffset = IntOffset(
                                        (left + col * TILE_PX).roundToInt(),
                                        (top + row * TILE_PX).roundToInt(),
                                    ),
                                    dstSize = IntSize(t.width, t.height),
                                )
                            }
                        }
                    }
                }

                // Search highlights: every match in yellow, the current one orange.
                searchRects[i]?.forEach { (box, isCurrent) ->
                    val r = rectToView(box, d.pages[i], rot, wPx.toFloat(), hPx.toFloat())
                    drawRect(
                        color = if (isCurrent) Marks.searchCurrent else Marks.searchHit,
                        topLeft = Offset(left + r.left, top + r.top),
                        size = Size(max(1f, r.width), max(1f, r.height)),
                    )
                }

                // Selection.
                vm.selectionRects.firstOrNull { it.page == i }?.rects?.forEach { box ->
                    val r = rectToView(box, d.pages[i], rot, wPx.toFloat(), hPx.toFloat())
                    drawRect(
                        color = Marks.selection,
                        topLeft = Offset(left + r.left, top + r.top),
                        size = Size(max(1f, r.width), max(1f, r.height)),
                    )
                }
            }

            // Selection handles last, so they sit above the pages.
            if (vm.hasSelection) {
                listOf(
                    handlePosition(vm, vm.startHandle, atStart = true),
                    handlePosition(vm, vm.endHandle, atStart = false),
                ).forEach { p ->
                    p ?: return@forEach
                    drawCircle(color = handleColor, radius = HANDLE_RADIUS_PX, center = p)
                    drawCircle(
                        color = Color.White,
                        radius = HANDLE_RADIUS_PX * 0.45f,
                        center = p,
                    )
                }
            }
        }

        FastScroll(vm)
    }
}

private const val OVERSCAN = 0.6f
private const val KEEP_PAGES = 3

/** Page index and page-local offset for a viewport touch, or null if off-page. */
private fun pageAt(vm: ViewerViewModel, pos: Offset): Triple<Int, Float, Float>? {
    val l = vm.layout
    if (l.count == 0) return null
    val contentY = vm.scrollY + pos.y - vm.contentOriginY()
    val contentX = vm.scrollX + pos.x - vm.contentOriginX()
    for (i in 0 until l.count) {
        val top = l.pageTopPx(i)
        val bottom = top + l.pageHeightPx(i)
        if (contentY in top..bottom) {
            val left = l.pageLeftPx(i)
            val right = left + l.pageWidthPx(i)
            if (contentX < left || contentX > right) return null
            return Triple(i, contentX - left, contentY - top)
        }
    }
    return null
}

/** Character index under a touch, if any. */
private suspend fun charAt(
    vm: ViewerViewModel,
    pos: Offset,
    snapVertical: Boolean,
): Pair<Int, Int>? {
    val (page, x, y) = pageAt(vm, pos) ?: return null
    val d = vm.doc ?: return null
    val pt = vm.toPageSpace(page, x, y)
    val tolerance = TOUCH_TOLERANCE_PT / max(vm.scale, 0.01f)
    val idx = d.charIndexAt(page, pt, tolerance, snapVertical)
    return if (idx >= 0) page to idx else null
}

private const val TOUCH_TOLERANCE_PT = 12f

/** Follows a link under the tap. Returns true when one was hit. */
private suspend fun followLinkAt(
    vm: ViewerViewModel,
    pos: Offset,
    openUri: (String) -> Unit,
): Boolean {
    val (page, x, y) = pageAt(vm, pos) ?: return false
    val pt = vm.toPageSpace(page, x, y)
    val link = vm.linkAt(page, pt) ?: return false
    link.destPage?.let { vm.goToPage(it, pushHistory = true); return true }
    link.uri?.takeIf { it.isNotBlank() }?.let {
        runCatching { openUri(it) }
        return true
    }
    return false
}

/**
 * Every drag gesture the page surface handles: adjusting a selection handle,
 * panning, pinching, and flinging.
 *
 * They share one loop on purpose. Layering separate detectors would leave them
 * racing for the same pointers, and Compose's `detectTransformGestures` doesn't
 * surface velocity — flinging is the difference between a viewer that feels
 * native and one that feels like a web page.
 */
private suspend fun PointerInputScope.viewportGestures(
    onGestureStart: () -> Unit,
    grabHandle: (Offset) -> HandleEnd?,
    onHandleDrag: (HandleEnd, Offset) -> Unit,
    onPanZoom: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onFling: (velocityY: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onGestureStart()
        val grabbed = grabHandle(down.position)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        var pastSlop = false
        var accumulated = 0f
        val slop = viewConfiguration.touchSlop
        // Velocity is only meaningful from one identified finger. Following
        // "whichever pointer is first" meant that lifting a finger after a
        // pinch teleported the tracked position to the other finger, which the
        // tracker read as a colossal velocity and turned into a runaway fling.
        var trackedPointer = down.id

        val lock = DragLock(commitPx = slop * AXIS_COMMIT)

        // A pinch must never turn into a fling, and the frame where the set of
        // fingers changes must never be read as movement.
        var sawMultiTouch = false
        var pointers = setOf(down.id)
        // Anchored once, when the second finger lands. Re-reading the live
        // centroid every frame anchors the zoom somewhere slightly different
        // each time, and the page creeps along after the fingers.
        var pinchAnchor: Offset? = null

        // When the finger last actually moved, and when the gesture ended: a
        // flick is only a flick when those are close together.
        var lastMoveMs = down.uptimeMillis
        var releaseMs = down.uptimeMillis
        var lastMovePos = down.position

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) break

            if (grabbed != null) {
                // Selection drag: follow the finger, never pan.
                val pos = event.changes.firstOrNull { it.pressed }?.position
                if (pos != null) {
                    onHandleDrag(grabbed, pos)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                continue
            }

            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = false)

            val pressed = event.changes.filter { it.pressed }
            if (pressed.size > 1) {
                // A genuine pinch: no single finger's velocity means anything.
                tracker.resetTracking()
            } else {
                // Note `pressed` is EMPTY on the final up event. Treating that
                // like a pinch and resetting here wiped the history one moment
                // before the fling read it, which killed momentum entirely —
                // so fall back to the tracked pointer's own change and record
                // its release position too.
                val finger = pressed.firstOrNull()
                    ?: event.changes.firstOrNull { it.id == trackedPointer }
                if (finger != null) {
                    if (finger.id != trackedPointer) {
                        // A different finger is carrying the gesture now; its
                        // history has nothing to do with the old one's.
                        tracker.resetTracking()
                        trackedPointer = finger.id
                        lastMovePos = finger.position
                    }
                    tracker.addPosition(finger.uptimeMillis, finger.position)
                    releaseMs = finger.uptimeMillis
                    if ((finger.position - lastMovePos).getDistance() > MOVED_PX) {
                        lastMoveMs = finger.uptimeMillis
                        lastMovePos = finger.position
                    }
                }
            }

            val ids = pressed.map { it.id }.toSet()
            val fingersChanged = ids != pointers
            pointers = ids
            if (pressed.size > 1) sawMultiTouch = true

            if (fingersChanged) {
                // Pan is the movement of the centroid of the pressed pointers,
                // so any change to which fingers are down makes this frame's
                // centroid incomparable with the last one's: it contributes no
                // movement at all. Comparing the ids rather than just how many
                // there are also catches one finger landing as another lifts.
                // Exactly one finger left, so the gesture carries on under new
                // management and has to decide its direction afresh: the totals
                // behind that decision used to survive a pinch, and the finger
                // left behind would judge its direction from how the *pinch* had
                // moved, locking sideways on a drag meant as a scroll.
                //
                // No fingers left means the gesture is over, and the committed
                // axis has to stand — the fling below is reading it.
                if (pressed.size == 1) {
                    lock.reset()
                    pinchAnchor = null
                }
                continue
            }

            if (pressed.size > 1 && pinchAnchor == null) pinchAnchor = centroid

            if (!pastSlop) {
                // The pinch term is the change in how far apart the fingers
                // are. It used to be the centroid's distance from the screen
                // origin — around a thousand pixels, nothing to do with the
                // gesture — which let the faintest two-finger contact clear
                // the slop on its first frame.
                val spread = event.calculateCentroidSize(useCurrent = false)
                accumulated += pan.getDistance() + abs(1f - zoom) * spread
                if (accumulated > slop) pastSlop = true
            }

            if (zoom != 1f || pressed.size > 1) lock.free() else lock.offer(pan.x, pan.y)

            val locked = if (pressed.size > 1) {
                // Two fingers zoom and nothing else: the point under them stays
                // put, so there is no translation left to drift.
                Offset.Zero
            } else {
                Offset(lock.allowX(pan.x), lock.allowY(pan.y))
            }

            if (pastSlop && (locked != Offset.Zero || zoom != 1f)) {
                onPanZoom(locked, zoom, pinchAnchor ?: centroid)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })

        // Flinging sideways would fight the axis lock; a pinch should not coast
        // at all, because releasing one finger used to leave the page drifting
        // on the surviving finger's few samples; and a finger that had come to
        // rest before lifting meant to stop, whatever the tracker extrapolates.
        val mayFling = grabbed == null &&
            pastSlop &&
            lock.axis != Axis.Horizontal &&
            !sawMultiTouch &&
            flingIsLive(lastMoveMs, releaseMs)
        if (mayFling) {
            val velocity = runCatching { tracker.calculateVelocity() }.getOrNull()
            velocity?.y?.takeIf { abs(it) > MIN_FLING }?.let { onFling(it * FLING_BOOST) }
        }
    }
}

/** Travel, as a multiple of the touch slop, before a drag commits to an axis. */
private const val AXIS_COMMIT = 1.25f

/** Movement below this is jitter, not a finger that is still travelling. */
private const val MOVED_PX = 1f

/**
 * Extra reach on a flick.
 *
 * Android's stock fling is tuned for lists, where a screen or two per flick is
 * plenty and a fast-scroller handles long jumps. A few hundred PDF pages needs
 * more travel per flick to feel usable; the deceleration curve is still the
 * platform one, so it stops naturally rather than sliding forever.
 */
private const val FLING_BOOST = 2.2f

/**
 * Draggable position scrubber.
 *
 * Flicking is fine for a few pages; crossing a few hundred needs a way to jump.
 * It fades out when the document is still so it doesn't clutter the page, and
 * shows the page number while being dragged.
 */
@Composable
private fun BoxScope.FastScroll(vm: ViewerViewModel) {
    val colors = PaperTheme.colors
    val layout = vm.layout
    val maxScroll = max(0f, layout.contentHeightPx - vm.viewportH)
    // Pointless on anything that's barely longer than the screen.
    if (maxScroll < vm.viewportH || layout.count < 3) return

    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(vm.scrollY, dragging) {
        visible = true
        if (!dragging) {
            kotlinx.coroutines.delay(1400)
            visible = false
        }
    }
    // Fades back to a hint rather than vanishing. Disappearing entirely would
    // also remove the touch target, leaving a scrollbar that can't be grabbed —
    // and one you can't find is no better than none.
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            dragging -> 1f
            visible -> 0.9f
            else -> 0.3f
        },
        label = "fastScrollAlpha",
    )

    val density = LocalDensity.current
    // Wide enough for the page readout to sit beside the track. Only the thumb
    // itself takes touches, so the extra width costs nothing — squeezing the
    // label inside a 36dp column is what made it unreadable.
    BoxWithConstraints(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(240.dp),
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbHeight = 64.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val fraction = (vm.scrollY / maxScroll).coerceIn(0f, 1f)
        val offsetY = (fraction * travel).roundToInt()
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

        // The grab target.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, offsetY) }
                .height(thumbHeight)
                .width(36.dp)
                .pointerInput(maxScroll, travel) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, drag ->
                            change.consume()
                            vm.scrollY += drag.y / travel * maxScroll
                            vm.clampScroll()
                            vm.syncCurrentPageToScroll()
                        },
                    )
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .width(if (dragging) 7.dp else 5.dp)
                    .height(if (dragging) thumbHeight else 48.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(colors.accent, shape),
            )
        }

        // The readout, level with the thumb and clear of the finger.
        if (dragging) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, offsetY) }
                    .height(thumbHeight)
                    .padding(end = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .background(colors.surface, shape)
                        .border(1.dp, colors.hairline, shape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.Text(
                        "${vm.currentPage + 1}",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                    )
                    androidx.compose.material3.Text(
                        "of ${layout.count}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

/** Screen position of a selection handle, or null if there's no selection. */
private fun handlePosition(vm: ViewerViewModel, anchor: Pair<Int, PdfBox>?, atStart: Boolean): Offset? {
    val (page, box) = anchor ?: return null
    val l = vm.layout
    if (page !in 0 until l.count) return null
    val wPx = l.pageWidthPx(page)
    val hPx = l.pageHeightPx(page)
    val r = rectToView(box, vm.pages[page], vm.rotation, wPx, hPx)
    val x = if (atStart) r.left else r.right
    return Offset(
        vm.contentOriginX() + l.pageLeftPx(page) + x - vm.scrollX,
        vm.contentOriginY() + l.pageTopPx(page) + r.bottom - vm.scrollY,
    )
}

/** Which handle, if any, a touch landed on. */
private fun handleUnder(vm: ViewerViewModel, pos: Offset): HandleEnd? {
    if (!vm.hasSelection) return null
    val start = handlePosition(vm, vm.startHandle, atStart = true)
    val end = handlePosition(vm, vm.endHandle, atStart = false)
    val dStart = start?.let { (it - pos).getDistance() } ?: Float.MAX_VALUE
    val dEnd = end?.let { (it - pos).getDistance() } ?: Float.MAX_VALUE
    return when {
        dStart > HANDLE_GRAB_PX && dEnd > HANDLE_GRAB_PX -> null
        dStart <= dEnd -> HandleEnd.Start
        else -> HandleEnd.End
    }
}

private const val HANDLE_GRAB_PX = 60f
private const val HANDLE_RADIUS_PX = 11f

private const val MIN_FLING = 80f


