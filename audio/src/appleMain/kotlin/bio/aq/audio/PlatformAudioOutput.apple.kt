@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package bio.aq.audio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioSourceNode
import platform.CoreAudioTypes.AudioBufferList
import platform.Foundation.NSError
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

internal actual class PlatformAudioOutput actual constructor(render: (FloatArray, Int) -> Unit) {
    private val session = AppleAudioSession()
    private val failure = AtomicReference<Throwable?>(null)
    private val state = AppleRenderState(render, failure)
    private var engine: AVAudioEngine? = null
    private var sourceNode: AVAudioSourceNode? = null
    private var closed = false

    actual fun play() {
        check(!closed) { "The audio output is closed" }
        if (engine?.running == true) {
            state.resume()
            return
        }
        try {
            session.activate()
            val audioEngine = engine ?: createEngine().also { engine = it }
            audioEngine.prepare()
            state.resume()
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                check(audioEngine.startAndReturnError(error.ptr)) {
                    error.value?.localizedDescription ?: "Could not start the audio engine"
                }
            }
        } catch (error: Throwable) {
            state.suspend()
            disposeEngine()
            state.awaitIdle()
            runCatching { session.deactivate() }
            throw error
        }
    }

    actual fun pause() {
        if (closed) return
        state.suspend()
        engine?.pause()
        state.awaitIdle()
        session.deactivate()
    }

    actual fun close() {
        if (closed) return
        closed = true
        state.suspend()
        disposeEngine()
        state.awaitIdle()
        state.releaseCallback()
        try {
            session.deactivate()
        } catch (error: Throwable) {
            // The output is already closed. Keep the cleanup error available to the host.
            failure.compareAndSet(null, error)
        }
    }

    actual fun takeFailure(): Throwable? = failure.exchange(null)

    private fun createEngine(): AVAudioEngine {
        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = PcmFormat.SAMPLE_RATE.toDouble(),
            channels = PcmFormat.CHANNELS.toUInt(),
            interleaved = false,
        )
        // The Objective-C block retains only render state, never the engine owner.
        val renderState = state
        val node = AVAudioSourceNode(format) { _, _, frames, buffers ->
            renderState.render(frames, buffers)
        }
        val audioEngine = AVAudioEngine()
        audioEngine.attachNode(node)
        audioEngine.connect(node, to = audioEngine.mainMixerNode, format = format)
        sourceNode = node
        return audioEngine
    }

    private fun disposeEngine() {
        engine?.let { audioEngine ->
            audioEngine.stop()
            sourceNode?.let(audioEngine::detachNode)
        }
        sourceNode = null
        engine = null
    }
}

internal expect class AppleAudioSession() {
    fun activate()
    fun deactivate()
}

internal class AppleRenderState(
    callback: (FloatArray, Int) -> Unit,
    private val failure: AtomicReference<Throwable?>,
) {
    private var callback: ((FloatArray, Int) -> Unit)? = callback
    private val samples = FloatArray(MAXIMUM_FRAMES * PcmFormat.CHANNELS)
    private val enabled = AtomicBoolean(false)
    private val activeCallbacks = AtomicInt(0)

    fun resume() = enabled.store(true)

    fun suspend() = enabled.store(false)

    fun awaitIdle() {
        // Only the lifecycle thread waits. The audio thread never takes a lock.
        while (activeCallbacks.load() != 0) usleep(100u)
    }

    fun releaseCallback() {
        callback = null
    }

    fun render(frameCount: UInt, output: CPointer<AudioBufferList>?): Int {
        val buffers = output?.pointed ?: run {
            reportFailure(IllegalStateException("The audio output has no buffers"))
            return -50
        }
        activeCallbacks.fetchAndAdd(1)
        try {
            if (!enabled.load()) {
                zero(buffers)
                return 0
            }
            val frames = frameCount.toInt()
            check(frames in 0..MAXIMUM_FRAMES) { "The audio buffer exceeds the supported size" }
            check(buffers.mNumberBuffers.toInt() == PcmFormat.CHANNELS) {
                "The audio source requires two planar output buffers"
            }
            samples.fill(0f, 0, frames * PcmFormat.CHANNELS)
            callback?.invoke(samples, frames)
            for (channel in 0 until PcmFormat.CHANNELS) {
                val buffer = buffers.mBuffers[channel]
                val byteCount = frames * Float.SIZE_BYTES
                check(buffer.mDataByteSize.toLong() >= byteCount.toLong()) {
                    "The audio output buffer is too small"
                }
                val destination = checkNotNull(buffer.mData?.reinterpret<FloatVar>()) {
                    "The audio output buffer has no storage"
                }
                for (frame in 0 until frames) {
                    val sample = samples[frame * PcmFormat.CHANNELS + channel]
                    check(sample.isFinite()) { "The source produced a non-finite sample" }
                    destination[frame] = sample
                }
                buffer.mDataByteSize = byteCount.toUInt()
            }
            return 0
        } catch (error: Throwable) {
            zero(buffers)
            reportFailure(error)
            return -1
        } finally {
            activeCallbacks.fetchAndAdd(-1)
        }
    }

    private fun reportFailure(error: Throwable) {
        enabled.store(false)
        failure.compareAndSet(null, error)
    }

    private fun zero(buffers: AudioBufferList) {
        for (index in 0 until buffers.mNumberBuffers.toInt()) {
            val buffer = buffers.mBuffers[index]
            val destination = buffer.mData?.reinterpret<FloatVar>() ?: continue
            val sampleCount = (buffer.mDataByteSize / Float.SIZE_BYTES.toUInt()).toInt()
            for (sample in 0 until sampleCount) destination[sample] = 0f
        }
    }
}

private const val MAXIMUM_FRAMES = 8_192
