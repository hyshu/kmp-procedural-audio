package bio.aq.audio

/**
 * Plays a Kotlin source through the platform audio output.
 *
 * Call lifecycle methods from one control thread. Source rendering happens on a
 * separate audio thread. A paused player keeps the source position. Closing a
 * player stops rendering and releases its output. Create a new player to restart
 * after close. The host handles audio focus, interruptions and background policy.
 * Never call lifecycle methods from inside a source callback.
 */
class AudioPlayer(source: PcmSource) {
    private val renderer = SwitchingSource(source)
    internal val output = PlatformAudioOutput(renderer::render)
    private var closed = false

    @Throws(Exception::class)
    fun play() {
        check(!closed) { "The player is closed" }
        output.play()
    }

    @Throws(Exception::class)
    fun pause() {
        if (!closed) output.pause()
    }

    /** Switches to a new source using a short crossfade on the audio thread. */
    fun replaceSource(source: PcmSource) {
        check(!closed) { "The player is closed" }
        renderer.replaceSource(source)
    }

    /** Returns and clears the most recent asynchronous output failure. */
    fun takeFailure(): Throwable? = output.takeFailure()

    @Throws(Exception::class)
    fun close() {
        if (closed) return
        output.close()
        closed = true
    }
}

internal expect class PlatformAudioOutput(render: (FloatArray, Int) -> Unit) {
    fun play()
    fun pause()
    fun close()
    fun takeFailure(): Throwable?
}
