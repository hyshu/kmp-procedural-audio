package bio.aq.audio

/** Owned by an AudioWorkletProcessor. This class never creates an AudioContext. */
internal actual class PlatformAudioOutput actual constructor(private val render: (FloatArray, Int) -> Unit) {
    private val buffer = FloatArray(1_024 * 2)
    private var playing = false
    private var closed = false
    private var failure: Throwable? = null

    actual fun play() {
        check(!closed) { "PCM output is closed" }
        playing = true
    }

    actual fun pause() {
        playing = false
    }
    actual fun close() {
        playing = false
        closed = true
    }
    actual fun takeFailure(): Throwable? = failure.also { failure = null }

    fun renderChannels(left: dynamic, right: dynamic) {
        // Web Audio supplies zero-filled channel arrays on every callback.
        if (!playing || closed) return
        val frames = left.length as Int
        try {
            require(right.length == frames) { "Stereo channels must have the same length" }
            var offset = 0
            while (offset < frames) {
                val count = minOf(frames - offset, buffer.size / 2)
                render(buffer, count)
                var frame = 0
                while (frame < count) {
                    left[offset + frame] = buffer[frame * 2]
                    right[offset + frame] = buffer[frame * 2 + 1]
                    frame++
                }
                offset += count
            }
        } catch (error: Throwable) {
            left.fill(0)
            right.fill(0)
            failure = error
            playing = false
        }
    }
}
