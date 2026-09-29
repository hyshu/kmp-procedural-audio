@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package bio.aq.audio

import kotlin.concurrent.atomics.AtomicReference

/** The control thread publishes requests and the audio thread owns each fade. */
internal class SwitchingSource(
    initialSource: PcmSource,
    private val transitionFrames: Int = 2_400,
    private val chunkFrames: Int = 1_024,
) : PcmSource {
    private val requested = AtomicReference(initialSource)
    private var current = initialSource
    private var next: PcmSource? = null
    private var fadeFrame = 0
    private val previousBuffer = FloatArray(chunkFrames * PcmFormat.CHANNELS)
    private val nextBuffer = FloatArray(chunkFrames * PcmFormat.CHANNELS)

    init {
        require(transitionFrames > 0) { "The transition must contain at least one frame" }
        require(chunkFrames > 0) { "The chunk must contain at least one frame" }
    }

    fun replaceSource(source: PcmSource) {
        requested.store(source)
    }

    override fun render(buffer: FloatArray, frameCount: Int) {
        requireBuffer(buffer, frameCount)
        if (frameCount == 0) return
        val target = requested.load()
        if (next == null && target !== current) next = target
        if (next == null) {
            current.render(buffer, frameCount)
            return
        }

        var offset = 0
        while (offset < frameCount) {
            val incoming = next
            val count = minOf(
                chunkFrames,
                frameCount - offset,
                if (incoming == null) Int.MAX_VALUE else transitionFrames - fadeFrame,
            )
            current.render(previousBuffer, count)
            if (incoming != null) incoming.render(nextBuffer, count)
            for (frame in 0 until count) {
                val mix = if (transitionFrames == 1) {
                    1f
                } else {
                    (fadeFrame + frame).toFloat() / (transitionFrames - 1)
                }
                for (channel in 0 until PcmFormat.CHANNELS) {
                    val index = frame * PcmFormat.CHANNELS + channel
                    buffer[(offset + frame) * PcmFormat.CHANNELS + channel] =
                        if (incoming == null) {
                            previousBuffer[index]
                        } else {
                            previousBuffer[index] * (1f - mix) + nextBuffer[index] * mix
                        }
                }
            }
            offset += count
            if (incoming != null) {
                fadeFrame += count
                if (fadeFrame == transitionFrames) {
                    current = incoming
                    next = if (target !== current) target else null
                    fadeFrame = 0
                }
            }
        }
    }
}
