package com.paperview.mobile.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Which direction a drag has committed to. */
internal enum class Axis { Undecided, Vertical, Horizontal, Free }

/**
 * Decides which way a drag is allowed to move the page.
 *
 * A thumb never travels in a straight line, and letting the leftover sideways
 * component through is what made long scrolls feel like the page was sliding
 * around. So a one-finger drag commits to one axis and stays there for the rest
 * of the gesture.
 *
 * Two things matter and both were wrong before:
 *
 *  * **The decision needs enough travel to be meaningful.** Deciding the
 *    instant the touch slop is crossed means judging direction from about eight
 *    pixels of noise, and one unlucky sample used to hand the whole gesture
 *    free rein in both directions.
 *  * **Ambiguous is not free.** A drag that looks diagonal is a vertical scroll
 *    with wobble far more often than it is a deliberate diagonal pan, and
 *    treating it as diagonal is precisely the drift being complained about.
 *    Only a pinch unlocks both axes.
 *
 * Before committing, vertical movement is already allowed through: in a reader
 * that is what almost every drag turns out to be, and waiting would put a dead
 * patch at the start of every scroll.
 *
 * Pure Kotlin on purpose — the gesture loop it serves can only be tested on a
 * device, but this can be tested on the desktop.
 */
internal class DragLock(private val commitPx: Float) {

    var axis: Axis = Axis.Undecided
        private set

    private var dx = 0f
    private var dy = 0f

    /**
     * Back to undecided.
     *
     * Used when the finger count changes, which is also the fix for a real bug:
     * the running totals used to survive a pinch, so the one finger left over
     * afterwards decided its axis from how the *pinch* had moved and could lock
     * sideways on a drag the reader meant as a scroll.
     */
    fun reset() {
        axis = Axis.Undecided
        dx = 0f
        dy = 0f
    }

    /** A pinch is under way: moving in both directions is the whole point. */
    fun free() {
        axis = Axis.Free
    }

    /** Feeds in one frame of movement, committing to an axis once it's clear. */
    fun offer(px: Float, py: Float) {
        if (axis != Axis.Undecided) return
        dx += px
        dy += py
        val ax = abs(dx)
        val ay = abs(dy)
        if (max(ax, ay) < commitPx) return
        axis = when {
            ax > ay * AXIS_BIAS -> Axis.Horizontal
            else -> Axis.Vertical
        }
    }

    /** The sideways part of this frame that the lock lets through. */
    fun allowX(px: Float): Float = when (axis) {
        Axis.Horizontal, Axis.Free -> px
        else -> 0f
    }

    /** The vertical part of this frame that the lock lets through. */
    fun allowY(py: Float): Float = when (axis) {
        Axis.Horizontal -> 0f
        else -> py
    }

    companion object {
        /** How much one direction must dominate before the gesture locks to it. */
        const val AXIS_BIAS = 1.4f
    }
}

/**
 * Whether the finger was still moving as it left the screen.
 *
 * Android stops sending move events when a finger holds still, so a velocity
 * tracker's newest sample can be from well before the lift. Asked to
 * extrapolate across that gap it reports the speed the finger had *before* it
 * stopped, and the page coasts away from a reader who had deliberately come to
 * a halt — the random slide "after scrolling a lot then letting go".
 */
internal fun flingIsLive(lastMoveMs: Long, releaseMs: Long): Boolean =
    releaseMs - lastMoveMs <= STALE_FLING_MS

/** Longest pause before a lift that still counts as a flick. */
internal const val STALE_FLING_MS = 70L

/**
 * The fling velocity to actually use, given the tracker's estimate and the
 * finger's last few (time, y) samples.
 *
 * The tracker extrapolates from a handful of points and can be wildly wrong —
 * after a pause, with two samples in the same millisecond, it returned a speed
 * hundreds of times the finger's and sometimes in the opposite direction. So
 * its answer is checked against what the finger visibly did over the last
 * [FLING_WINDOW_MS]: no fling if they disagree on direction, and never more
 * than [OBSERVED_HEADROOM] times the observed speed (a flick is still speeding
 * up as it leaves, so its end speed beats its average), nor the platform max.
 */
internal fun settleFlingVelocity(
    tracked: Float,
    recent: List<Pair<Long, Float>>,
    maxVelocity: Float,
): Float {
    if (!tracked.isFinite() || tracked == 0f || recent.size < 2) return 0f
    val end = recent.last()
    val start = recent.firstOrNull { end.first - it.first <= FLING_WINDOW_MS } ?: return 0f
    val spanMs = end.first - start.first
    if (spanMs < MIN_FLING_WINDOW_MS) return 0f
    val observed = (end.second - start.second) / spanMs * 1000f
    if (observed == 0f || sign(observed) != sign(tracked)) return 0f
    val cap = if (maxVelocity > 0f && maxVelocity.isFinite()) maxVelocity else Float.MAX_VALUE
    return sign(tracked) * min(min(abs(tracked), abs(observed) * OBSERVED_HEADROOM), cap)
}

/** How far back [settleFlingVelocity] looks at the finger's real movement. */
internal const val FLING_WINDOW_MS = 100L

/** Shortest stretch of movement that says anything about speed. */
internal const val MIN_FLING_WINDOW_MS = 16L

/** How much faster than its recent average a flick may be judged to end. */
internal const val OBSERVED_HEADROOM = 2f
