@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package bio.aq.audio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.CoreAudioTypes.AudioBufferList
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppleRenderStateTest {
    @Test
    fun interleavedSourceKeepsLeftAndRightChannelsSeparate() = memScoped {
        val failure = AtomicReference<Throwable?>(null)
        val state = AppleRenderState({ samples, frames ->
            for (frame in 0 until frames) {
                samples[frame * 2] = (frame + 1) / 10f
                samples[frame * 2 + 1] = -(frame + 1) / 20f
            }
        }, failure)
        val buffers = stereoBuffers(4)
        state.resume()

        assertEquals(0, state.render(4u, buffers))

        for (frame in 0 until 4) {
            assertEquals((frame + 1) / 10f, channelData(buffers, 0)[frame])
            assertEquals(-(frame + 1) / 20f, channelData(buffers, 1)[frame])
        }
        assertNull(failure.load())
    }

    @Test
    fun sourceFailureSilencesEveryChannelAndStopsCallingTheSource() = memScoped {
        val failure = AtomicReference<Throwable?>(null)
        val expected = IllegalStateException("Test source failed")
        var calls = 0
        val state = AppleRenderState({ samples, _ ->
            calls++
            samples[0] = 0.5f
            throw expected
        }, failure)
        val buffers = stereoBuffers(4)
        state.resume()

        assertNotEquals(0, state.render(4u, buffers))
        assertSame(expected, failure.load())
        assertSilent(buffers, 4)
        assertEquals(0, state.render(4u, buffers))
        assertEquals(1, calls)
        assertSilent(buffers, 4)
    }

    @Test
    fun nonFiniteRightChannelAlsoClearsAlreadyWrittenLeftChannel() = memScoped {
        val failure = AtomicReference<Throwable?>(null)
        val state = AppleRenderState({ samples, frames ->
            samples.fill(0.25f, 0, frames * 2)
            samples[1] = Float.NaN
        }, failure)
        val buffers = stereoBuffers(4)
        state.resume()

        assertNotEquals(0, state.render(4u, buffers))

        assertSilent(buffers, 4)
        assertEquals("The source produced a non-finite sample", failure.load()?.message)
    }

    @Test
    fun suspendedAndReleasedOutputDoesNotAdvanceSource() = memScoped {
        val failure = AtomicReference<Throwable?>(null)
        var calls = 0
        val state = AppleRenderState({ samples, frames ->
            calls++
            samples.fill(0.25f, 0, frames * 2)
        }, failure)
        val buffers = stereoBuffers(4)
        state.resume()
        assertEquals(0, state.render(4u, buffers))
        state.suspend()
        state.awaitIdle()
        state.releaseCallback()

        assertEquals(0, state.render(4u, buffers))

        assertEquals(1, calls)
        assertSilent(buffers, 4)
        assertNull(failure.load())
    }

    private fun MemScope.stereoBuffers(frames: Int): CPointer<AudioBufferList> {
        // Reserve extra space for the flexible second AudioBuffer entry.
        val buffers = allocArray<AudioBufferList>(2)
        buffers.pointed.mNumberBuffers = 2u
        for (channel in 0 until 2) {
            val data = allocArray<FloatVar>(frames)
            for (frame in 0 until frames) data[frame] = 0.75f
            val buffer = buffers.pointed.mBuffers[channel]
            buffer.mNumberChannels = 1u
            buffer.mDataByteSize = (frames * Float.SIZE_BYTES).toUInt()
            buffer.mData = data
        }
        return buffers
    }

    private fun channelData(buffers: CPointer<AudioBufferList>, channel: Int): CPointer<FloatVar> =
        checkNotNull(buffers.pointed.mBuffers[channel].mData?.reinterpret<FloatVar>())

    private fun assertSilent(buffers: CPointer<AudioBufferList>, frames: Int) {
        for (channel in 0 until 2) {
            for (frame in 0 until frames) assertEquals(0f, channelData(buffers, channel)[frame])
        }
    }
}
