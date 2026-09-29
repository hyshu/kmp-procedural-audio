package bio.aq.audio

import kotlin.math.PI
import kotlin.math.sin

/** A quiet sine wave for testing an output or learning the source interface. */
class SineSource(frequencyHz: Double = 220.0, private val amplitude: Float = 0.12f) : PcmSource {
    private var phase = 0.0
    private val step = 2.0 * PI * frequencyHz / PcmFormat.SAMPLE_RATE

    init {
        require(
            frequencyHz.isFinite() && frequencyHz > 0.0 &&
                frequencyHz < PcmFormat.SAMPLE_RATE / 2.0,
        ) { "The frequency is outside the audible format range" }
        require(amplitude.isFinite() && amplitude in 0f..1f) { "Amplitude must be between zero and one" }
    }

    override fun render(buffer: FloatArray, frameCount: Int) {
        requireBuffer(buffer, frameCount)
        for (frame in 0 until frameCount) {
            val sample = sin(phase).toFloat() * amplitude
            buffer[frame * 2] = sample
            buffer[frame * 2 + 1] = sample
            phase += step
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
        }
    }
}

/** Seeded white noise with the same integer sequence on every target. */
class NoiseSource(seed: Int = 1, private val amplitude: Float = 0.08f) : PcmSource {
    private var state = seed.toUInt()

    init {
        require(seed != 0) { "The noise seed must be nonzero" }
        require(amplitude.isFinite() && amplitude in 0f..1f) { "Amplitude must be between zero and one" }
    }

    override fun render(buffer: FloatArray, frameCount: Int) {
        requireBuffer(buffer, frameCount)
        for (index in 0 until frameCount * PcmFormat.CHANNELS) {
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            buffer[index] = ((state shr 8).toFloat() / 8_388_608f - 1f) * amplitude
        }
    }
}
