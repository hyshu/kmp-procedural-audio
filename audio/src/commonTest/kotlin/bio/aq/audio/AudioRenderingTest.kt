package bio.aq.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioRenderingTest {
    @Test
    fun seededNoiseDoesNotDependOnCallbackSize() {
        val whole = FloatArray(8_192)
        NoiseSource(17).render(whole, whole.size / 2)
        val chunked = FloatArray(whole.size)
        val source = NoiseSource(17)
        val scratch = FloatArray(74)
        var offset = 0
        while (offset < chunked.size) {
            val count = minOf(scratch.size, chunked.size - offset)
            source.render(scratch, count / 2)
            scratch.copyInto(chunked, offset, 0, count)
            offset += count
        }
        assertContentEquals(whole, chunked)
        assertTrue(whole.all { it.isFinite() && abs(it) <= 0.08f })
        assertTrue(whole.any { it > 0f } && whole.any { it < 0f })
    }

    @Test
    fun sineFillsOnlyRequestedFramesAndKeepsStereoAligned() {
        val buffer = FloatArray(130) { 42f }
        SineSource().render(buffer, 64)
        for (frame in 0 until 64) {
            assertEquals(buffer[frame * 2], buffer[frame * 2 + 1])
            assertTrue(abs(buffer[frame * 2]) <= 0.12f)
        }
        assertEquals(42f, buffer[128])
        assertEquals(42f, buffer[129])
    }

    @Test
    fun crossfadeIsContinuousAcrossArbitraryBufferBoundaries() {
        fun render(chunks: List<Int>): FloatArray {
            val source = CrossfadeSource(constant(-1f), transitionFrames = 9, chunkFrames = 3)
            source.replaceSource(constant(1f))
            val result = FloatArray(chunks.sum() * 2)
            var offset = 0
            for (frames in chunks) {
                val buffer = FloatArray(frames * 2)
                source.render(buffer, frames)
                buffer.copyInto(result, offset)
                offset += buffer.size
            }
            return result
        }
        val result = render(listOf(12))
        assertContentEquals(result, render(listOf(1, 4, 2, 5)))
        assertEquals(-1f, result.first())
        assertEquals(1f, result.last())
        for (frame in 1 until 12) {
            assertTrue(abs(result[frame * 2] - result[(frame - 1) * 2]) <= 0.25f)
        }
    }

    @Test
    fun newRequestDuringFadeFinishesCurrentTransitionBeforeNext() {
        val source = CrossfadeSource(constant(-1f), transitionFrames = 3, chunkFrames = 1)
        source.replaceSource(constant(0f))
        val first = FloatArray(2)
        source.render(first, 1)
        source.replaceSource(constant(1f))
        val rest = FloatArray(10)
        source.render(rest, 5)
        assertContentEquals(floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 1f, 1f), rest)
    }

    @Test
    fun zeroFramesNeverAdvanceSources() {
        var calls = 0
        val source = CrossfadeSource(PcmSource { _, _ -> calls++ })
        source.replaceSource(PcmSource { _, _ -> calls++ })
        source.render(FloatArray(0), 0)
        assertEquals(0, calls)
    }

    @Test
    fun invalidRequestsFailBeforeRendering() {
        assertFailsWith<IllegalArgumentException> { SineSource(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SineSource(24_000.0) }
        assertFailsWith<IllegalArgumentException> { NoiseSource(0) }
        assertFailsWith<IllegalArgumentException> { NoiseSource(amplitude = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SineSource().render(FloatArray(2), Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { NoiseSource().render(FloatArray(2), -1) }
        assertFailsWith<IllegalArgumentException> { CrossfadeSource(constant(0f), transitionFrames = 0) }
        assertFailsWith<IllegalArgumentException> { CrossfadeSource(constant(0f), transitionFrames = -1) }
        assertFailsWith<IllegalArgumentException> { CrossfadeSource(constant(0f), chunkFrames = 0) }
        assertFailsWith<IllegalArgumentException> { CrossfadeSource(constant(0f), chunkFrames = -1) }
        assertFailsWith<IllegalArgumentException> { CrossfadeSource(constant(0f), chunkFrames = Int.MAX_VALUE) }
    }

    private fun constant(value: Float) = PcmSource { buffer, frames ->
        buffer.fill(value, 0, frames * 2)
    }
}
