@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package bio.aq.audio

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredOutputNumberOfChannels
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSError

/** The initial iOS backend owns the process-wide playback session. */
internal actual class AppleAudioSession actual constructor() {
    private val session = AVAudioSession.sharedInstance()
    private var active = false

    actual fun activate() {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            check(session.setCategory(AVAudioSessionCategoryPlayback, withOptions = 0u, error = error.ptr)) {
                error.value?.localizedDescription ?: "Could not configure the audio session"
            }
            // Routes may reject either preference. The engine still converts its source format.
            error.value = null
            session.setPreferredSampleRate(PcmFormat.SAMPLE_RATE.toDouble(), error.ptr)
            error.value = null
            session.setPreferredOutputNumberOfChannels(count = PcmFormat.CHANNELS.toLong(), error = error.ptr)
            error.value = null
            check(session.setActive(true, error.ptr)) {
                error.value?.localizedDescription ?: "Could not activate the audio session"
            }
            active = true
        }
    }

    actual fun deactivate() {
        if (!active) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            check(
                session.setActive(
                    active = false,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = error.ptr,
                ),
            ) {
                error.value?.localizedDescription ?: "Could not deactivate the audio session"
            }
        }
        active = false
    }
}
