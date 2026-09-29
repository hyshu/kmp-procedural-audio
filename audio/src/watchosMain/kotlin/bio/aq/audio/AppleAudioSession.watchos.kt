package bio.aq.audio

/** The watch host must activate its audio session before calling play. */
internal actual class AppleAudioSession actual constructor() {
    actual fun activate() = Unit
    actual fun deactivate() = Unit
}
