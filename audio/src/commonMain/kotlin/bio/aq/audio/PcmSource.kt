package bio.aq.audio

/** The format used by every source and output in this library. */
object PcmFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
}

/**
 * Generates interleaved stereo float samples at 48 kHz.
 *
 * Fill the first frameCount times two elements on each call. Keep samples finite
 * and between minus one and one. The output owns the buffer and reuses it.
 * Rendering runs on the audio thread. Avoid allocation, locks and blocking work.
 * Each mutable source must belong to one player at a time.
 */
fun interface PcmSource {
    fun render(buffer: FloatArray, frameCount: Int)
}

internal fun requireBuffer(buffer: FloatArray, frameCount: Int) {
    require(frameCount >= 0 && frameCount <= buffer.size / PcmFormat.CHANNELS) {
        "The buffer must hold two samples for every requested frame"
    }
}
