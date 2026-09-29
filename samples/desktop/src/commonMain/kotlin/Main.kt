import bio.aq.audio.AudioPlayer
import bio.aq.audio.NoiseSource
import bio.aq.audio.SineSource

fun main() {
    val player = AudioPlayer(SineSource())
    println("Press Enter to play a quiet tone")
    readlnOrNull() ?: return player.close()
    try {
        player.play()
        println("Press Enter to switch to noise")
        readlnOrNull()
        player.takeFailure()?.let { throw it }
        player.replaceSource(NoiseSource())
        println("Press Enter to pause")
        readlnOrNull()
        player.pause()
        player.takeFailure()?.let { throw it }
        println("Press Enter to resume")
        readlnOrNull()
        player.play()
        println("Press Enter to close")
        readlnOrNull()
        player.takeFailure()?.let { throw it }
    } finally {
        player.close()
    }
}
