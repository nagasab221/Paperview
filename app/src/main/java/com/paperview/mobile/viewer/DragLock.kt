package com.paperview.mobile.viewer

import kotlin.math.abs
import kotlin.math.max

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
