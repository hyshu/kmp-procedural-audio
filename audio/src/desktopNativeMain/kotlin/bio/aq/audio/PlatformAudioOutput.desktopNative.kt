@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package bio.aq.audio

import bio.aq.audio.nativeinterop.pcm_audio_create
import bio.aq.audio.nativeinterop.pcm_audio_destroy
import bio.aq.audio.nativeinterop.pcm_audio_start
import bio.aq.audio.nativeinterop.pcm_audio_stop
import bio.aq.audio.nativeinterop.pcm_audio_take_error
import cnames.structs.pcm_audio_output
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlin.concurrent.atomics.AtomicReference

private const val RENDER_CHUNK_FRAMES = 2048
private const val CHANNEL_COUNT = 2
private const val ERROR_CAPACITY = 1024

private class NativeRenderState(val render: (FloatArray, Int) -> Unit) {
    val buffer = FloatArray(RENDER_CHUNK_FRAMES * CHANNEL_COUNT)
    val failure = AtomicReference<Throwable?>(null)
}

private fun renderNativeAudio(userData: COpaquePointer?, samples: CPointer<FloatVar>?, frames: Int): Int {
    if (userData == null || samples == null || frames < 0 || frames > Int.MAX_VALUE / CHANNEL_COUNT) {
        return 0
    }
    val state = userData.asStableRef<NativeRenderState>().get()
    return try {
        var remaining = frames
        var sampleOffset = 0
        while (remaining > 0) {
            val chunkFrames = minOf(remaining, RENDER_CHUNK_FRAMES)
            val sampleCount = chunkFrames * CHANNEL_COUNT
            state.buffer.fill(0f, 0, sampleCount)
            state.render(state.buffer, chunkFrames)
            for (index in 0 until sampleCount) {
                samples[sampleOffset + index] = state.buffer[index]
            }
            sampleOffset += sampleCount
            remaining -= chunkFrames
        }
        1
    } catch (error: Throwable) {
        state.failure.compareAndSet(null, error)
        for (index in 0 until frames * CHANNEL_COUNT) {
            samples[index] = 0f
        }
        0
    }
}

internal actual class PlatformAudioOutput actual constructor(render: (FloatArray, Int) -> Unit) {
    private val state = NativeRenderState(render)
    private val stateRef = StableRef.create(state)
    private var output: CPointer<pcm_audio_output>? = null

    init {
        try {
            output = memScoped {
                val error = allocArray<ByteVar>(ERROR_CAPACITY)
                pcm_audio_create(
                    staticCFunction(::renderNativeAudio),
                    stateRef.asCPointer(),
                    error,
                    ERROR_CAPACITY.convert(),
                ) ?: throw IllegalStateException(error.toKString())
            }
        } catch (error: Throwable) {
            stateRef.dispose()
            throw error
        }
    }

    actual fun play() {
        val handle = checkNotNull(output) { "Audio output is closed" }
        if (pcm_audio_start(handle) != 1) {
            throw takeNativeFailure(handle) ?: IllegalStateException("Audio output could not start")
        }
    }

    actual fun pause() {
        val handle = output ?: return
        if (pcm_audio_stop(handle) != 1) {
            throw takeNativeFailure(handle) ?: IllegalStateException("Audio output could not pause")
        }
    }

    actual fun close() {
        val handle = output ?: return
        if (pcm_audio_destroy(handle) != 1) {
            throw takeNativeFailure(handle) ?: IllegalStateException("Audio output could not close")
        }
        output = null
        // Destroy joins the native worker before its callback state is released.
        stateRef.dispose()
    }

    actual fun takeFailure(): Throwable? {
        val callbackFailure = state.failure.exchange(null)
        val nativeFailure = output?.let(::takeNativeFailure)
        return callbackFailure ?: nativeFailure
    }

    private fun takeNativeFailure(handle: CPointer<pcm_audio_output>): Throwable? = memScoped {
        val error = allocArray<ByteVar>(ERROR_CAPACITY)
        if (pcm_audio_take_error(handle, error, ERROR_CAPACITY.convert()) == 1) {
            IllegalStateException(error.toKString())
        } else {
            null
        }
    }
}
