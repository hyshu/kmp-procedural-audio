@file:OptIn(ExperimentalJsExport::class)

package bio.aq.audio

/**
 * Small exported example bridge. Construct inside an AudioWorkletProcessor.
 * Custom sources must be compiled into this worker-side module, not sent from the window.
 */
@JsExport
class WebAudioEngine {
    private val player = AudioPlayer(SineSource())

    fun play() = player.play()
    fun pause() = player.pause()
    fun close() = player.close()

    fun selectSource(source: String) {
        player.replaceSource(
            when (source) {
                "sine" -> SineSource()
                "noise" -> NoiseSource()
                else -> throw IllegalArgumentException("Unknown source '$source'")
            },
        )
    }

    fun renderChannels(left: dynamic, right: dynamic) = player.renderChannels(left, right)

    fun takeFailureMessage(): String? = player.takeFailure()?.let { it.message ?: "PCM generation failed" }
}
