package bio.aq.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AudioWorkletOutputTest {
    @Test
    fun customSourceRendersIntoWorkletChannelsAndResumesAtTheSamePosition() {
        var renderedFrames = 0
        val player = AudioPlayer(
            PcmSource { buffer, frames ->
                for (frame in 0 until frames) {
                    buffer[frame * 2] = (renderedFrames + frame) / 8f
                    buffer[frame * 2 + 1] = -(renderedFrames + frame) / 8f
                }
                renderedFrames += frames
            },
        )
        val left: dynamic = js("new Float32Array(3)")
        val right: dynamic = js("new Float32Array(3)")

        player.play()
        player.renderChannels(left, right)
        assertEquals(0.25f, left[2] as Float)
        assertEquals(-0.25f, right[2] as Float)
        player.pause()
        player.renderChannels(left, right)
        assertEquals(3, renderedFrames)
        player.play()
        player.renderChannels(left, right)
        assertEquals(0.375f, left[0] as Float)
        assertEquals(6, renderedFrames)
        assertNull(player.takeFailure())
        player.close()
    }

    @Test
    fun mismatchedWorkletChannelsAreSilencedBeforeCallingTheSource() {
        var calls = 0
        val player = AudioPlayer(PcmSource { _, _ -> calls++ })
        val left: dynamic = js("new Float32Array(4).fill(0.5)")
        val right: dynamic = js("new Float32Array(2).fill(0.5)")

        player.play()
        player.renderChannels(left, right)
        assertEquals(0, calls)
        for (index in 0 until 4) assertEquals(0f, left[index] as Float)
        for (index in 0 until 2) assertEquals(0f, right[index] as Float)
        assertIs<IllegalArgumentException>(player.takeFailure())
        assertNull(player.takeFailure())
        player.renderChannels(left, right)
        assertEquals(0, calls)
        player.close()
    }

    @Test
    fun sourceFailureSilencesBothChannelsAndStopsRendering() {
        var calls = 0
        val player = AudioPlayer(
            PcmSource { _, _ ->
                calls++
                error("Custom source failed")
            },
        )
        val left: dynamic = js("new Float32Array(4).fill(0.5)")
        val right: dynamic = js("new Float32Array(4).fill(0.5)")

        player.play()
        player.renderChannels(left, right)
        for (index in 0 until 4) {
            assertEquals(0f, left[index] as Float)
            assertEquals(0f, right[index] as Float)
        }
        assertEquals("Custom source failed", player.takeFailure()?.message)
        assertNull(player.takeFailure())
        player.renderChannels(left, right)
        assertEquals(1, calls)
        player.close()
    }
}
