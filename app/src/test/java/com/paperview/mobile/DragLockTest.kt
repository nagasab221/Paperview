package com.paperview.mobile

import com.paperview.mobile.viewer.Axis
import com.paperview.mobile.viewer.DragLock
import com.paperview.mobile.viewer.STALE_FLING_MS
import com.paperview.mobile.viewer.flingIsLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch slop on a typical phone, so the numbers below read like real pixels. */
private const val SLOP = 22f
private const val COMMIT = SLOP * 1.25f

class DragLockTest {

    private fun lock() = DragLock(commitPx = COMMIT)

    @Test
    fun `a short drag has not committed yet`() {
        val lock = lock()
        lock.offer(3f, 5f)
        assertEquals(Axis.Undecided, lock.axis)
    }

    @Test
    fun `vertical movement is allowed before committing`() {
        // Otherwise every scroll would start with a patch of dead travel.
        val lock = lock()
        lock.offer(1f, 6f)
        assertEquals(6f, lock.allowY(6f), 0f)
        assertEquals(0f, lock.allowX(1f), 0f)
    }

    @Test
    fun `a mostly vertical drag locks to vertical`() {
        val lock = lock()
        repeat(10) { lock.offer(1f, 8f) }
        assertEquals(Axis.Vertical, lock.axis)
        assertEquals(0f, lock.allowX(14f), 0f)
        assertEquals(14f, lock.allowY(14f), 0f)
    }

    @Test
    fun `a clearly sideways drag locks to horizontal`() {
        val lock = lock()
        repeat(10) { lock.offer(8f, 1f) }
        assertEquals(Axis.Horizontal, lock.axis)
        assertEquals(9f, lock.allowX(9f), 0f)
        assertEquals(0f, lock.allowY(9f), 0f)
    }

    @Test
    fun `an ambiguous drag scrolls rather than drifting sideways`() {
        // The reported bug: a diagonal-looking drag used to unlock both axes,
        // and a long scroll then wandered left and right for its whole length.
        val lock = lock()
        repeat(10) { lock.offer(5f, 5f) }
        assertEquals(Axis.Vertical, lock.axis)
        assertEquals(0f, lock.allowX(5f), 0f)
    }

    @Test
    fun `the axis never flips once committed`() {
        val lock = lock()
        repeat(10) { lock.offer(0f, 9f) }
        assertEquals(Axis.Vertical, lock.axis)
        // A thumb curling sideways at the end of a long scroll must not take
        // the page with it.
        repeat(40) { lock.offer(9f, 0f) }
        assertEquals(Axis.Vertical, lock.axis)
        assertEquals(0f, lock.allowX(9f), 0f)
    }

    @Test
    fun `a decision needs more than a slop's worth of travel`() {
        // Deciding the instant slop is crossed means judging direction from
        // about eight pixels of noise.
        val lock = lock()
        lock.offer(SLOP * 0.9f, 0f)
        assertEquals(Axis.Undecided, lock.axis)
    }

    @Test
    fun `a pinch unlocks both directions`() {
        val lock = lock()
        lock.free()
        assertEquals(Axis.Free, lock.axis)
        assertEquals(7f, lock.allowX(7f), 0f)
        assertEquals(7f, lock.allowY(7f), 0f)
    }

    @Test
    fun `resetting forgets how the pinch moved`() {
        // The real bug this guards: the totals used to survive a pinch, so the
        // finger left behind afterwards decided its axis from the pinch's own
        // sideways spread and locked horizontal on an intended scroll.
        val lock = lock()
        lock.free()
        repeat(10) { lock.offer(20f, 0f) }
        lock.reset()
        assertEquals(Axis.Undecided, lock.axis)
        repeat(10) { lock.offer(0f, 8f) }
        assertEquals(Axis.Vertical, lock.axis)
    }

    @Test
    fun `a finger still moving at the lift flings`() {
        assertTrue(flingIsLive(lastMoveMs = 1_000, releaseMs = 1_000))
        assertTrue(flingIsLive(lastMoveMs = 1_000, releaseMs = 1_000 + STALE_FLING_MS))
    }

    @Test
    fun `a finger that had stopped does not fling`() {
        // Android sends no move events while a finger holds still, so the
        // tracker's newest sample is from before the pause and extrapolating
        // across it invents a speed the reader had deliberately given up.
        assertFalse(flingIsLive(lastMoveMs = 1_000, releaseMs = 1_000 + STALE_FLING_MS + 1))
        assertFalse(flingIsLive(lastMoveMs = 1_000, releaseMs = 1_400))
    }
}
