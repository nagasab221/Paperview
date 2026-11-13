package com.paperview.mobile

import com.paperview.mobile.viewer.settleFlingVelocity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Replays real gestures captured on a Galaxy S24 Ultra. Samples are
 * `ms:y`, deduplicated by timestamp the same way the gesture loop does.
 */
class FlingVelocityTest {

    private fun samples(raw: String): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        raw.trim().split(' ').forEach {
            val (t, y) = it.removeSuffix("UP").split(':')
            if (out.isEmpty() || t.toLong() > out.last().first) out += t.toLong() to y.toFloat()
        }
        return out
    }

    private val maxFling = 25_000f

    @Test
    fun `slow read then lift no longer flings to the top`() {
        // The finger crept up at ~50px/s; the tracker said +44,309px/s downwards.
        val v = settleFlingVelocity(
            tracked = 44309.89f,
            recent = samples(
                "0:407.3 9:407.2 26:406.9 42:406.2 100:405.1 109:405.1 125:404.4 142:403.7 " +
                    "158:403.0 167:403.0 242:398.9 242:398.8 251:398.8UP",
            ),
            maxVelocity = maxFling,
        )
        assertEquals(0f, v, 0f)
    }

    @Test
    fun `a slow drag the tracker reads backwards gets no fling`() {
        val v = settleFlingVelocity(
            tracked = 74.86847f,
            recent = samples(
                "199:365.2 208:365.0 224:362.4 233:362.2 283:359.9 291:358.5 292:358.4 300:358.4UP",
            ),
            maxVelocity = maxFling,
        )
        assertEquals(0f, v, 0f)
    }

    @Test
    fun `a real quick flick keeps the tracker's velocity`() {
        val v = settleFlingVelocity(
            tracked = -2422.125f,
            recent = samples("0:828.1 9:803.1 17:772.8 25:734.8 34:699.4 34:699.0 42:699.0UP"),
            maxVelocity = maxFling,
        )
        assertEquals(-2422.125f, v, 0.01f)
    }

    @Test
    fun `a longer swipe keeps the tracker's velocity`() {
        val v = settleFlingVelocity(
            tracked = -2184.2185f,
            recent = samples(
                "0:954.1 8:938.3 16:920.5 25:902.6 33:884.6 41:866.1 50:849.3 58:832.2 66:818.5 " +
                    "75:803.8 83:787.5 91:775.7 100:762.4 108:746.2 116:730.9 125:714.2 133:694.8 " +
                    "141:670.9 143:665.6 150:665.6UP",
            ),
            maxVelocity = maxFling,
        )
        assertEquals(-2184.2185f, v, 0.01f)
    }

    @Test
    fun `an absurd estimate in the right direction is held near the observed speed`() {
        // Moving 100px in 100ms is 1,000px/s; the tracker claiming 40,000 is capped at 2x.
        val v = settleFlingVelocity(
            tracked = 40_000f,
            recent = samples("0:100 50:150 100:200"),
            maxVelocity = maxFling,
        )
        assertEquals(2_000f, v, 0.01f)
    }

    @Test
    fun `never exceeds the platform maximum`() {
        val v = settleFlingVelocity(
            tracked = -90_000f,
            recent = samples("0:3000 20:1000"),
            maxVelocity = maxFling,
        )
        assertEquals(maxFling, abs(v), 0.01f)
    }

    @Test
    fun `no evidence means no fling`() {
        assertEquals(0f, settleFlingVelocity(5_000f, samples("0:100"), maxFling), 0f)
        assertEquals(0f, settleFlingVelocity(5_000f, samples("0:100 8:140"), maxFling), 0f)
        assertEquals(0f, settleFlingVelocity(Float.NaN, samples("0:100 50:200"), maxFling), 0f)
    }
}
