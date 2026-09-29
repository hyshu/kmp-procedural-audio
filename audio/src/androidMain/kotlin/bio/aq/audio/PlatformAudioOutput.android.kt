package bio.aq.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

/** Foreground PCM output. The host owns audio focus and background playback. */
internal actual class PlatformAudioOutput actual constructor(private val render: (FloatArray, Int) -> Unit) {
    private val trackLock = Any()
    private val epoch = AtomicLong(0)
    private val failure = AtomicReference<Throwable?>(null)

    @Volatile private var running = false

    @Volatile private var playing = false

    @Volatile private var closed = false

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Synchronized
    actual fun play() {
        check(!closed) { "PCM output is closed" }
        try {
            val audioTrack = track ?: createTrack().also { track = it }
            synchronized(trackLock) {
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play()
                epoch.incrementAndGet()
                playing = true
            }
            if (worker?.isAlive != true) {
                running = true
                worker = Thread(::writeLoop, "KmpPcmOutput").also { it.start() }
            }
            LockSupport.unpark(worker)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    actual fun pause() {
        synchronized(trackLock) {
            playing = false
            epoch.incrementAndGet()
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
            }
        }
    }

    @Synchronized
    actual fun close() {
        if (closed && track == null) return
        check(Thread.currentThread() !== worker) { "Close the player from its control thread" }
        closed = true
        running = false
        runCatching { pause() }.onFailure { failure.compareAndSet(null, it) }
        val thread = worker
        LockSupport.unpark(thread)
        // PcmSource callbacks must be bounded and nonblocking. Closing waits until
        // no callback can still access the source before releasing native output.
        var interrupted = false
        while (thread?.isAlive == true) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        worker = null
        runCatching { track?.release() }.onFailure { failure.compareAndSet(null, it) }
        track = null
        if (interrupted) Thread.currentThread().interrupt()
    }

    actual fun takeFailure(): Throwable? = failure.getAndSet(null)

    private fun writeLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = FloatArray(BLOCK_FRAMES * CHANNELS)
            while (running) {
                if (!playing) {
                    LockSupport.parkNanos(20_000_000L)
                    continue
                }
                val renderEpoch = epoch.get()
                render(buffer, BLOCK_FRAMES)
                synchronized(trackLock) {
                    if (!running || !playing || renderEpoch != epoch.get()) return@synchronized
                    val audioTrack = checkNotNull(track) { "AudioTrack unavailable" }
                    var offset = 0
                    while (offset < buffer.size && running && playing && renderEpoch == epoch.get()) {
                        val written = audioTrack.write(
                            buffer,
                            offset,
                            buffer.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        check(written > 0) { "AudioTrack.write failed ($written)" }
                        offset += written
                    }
                }
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        } finally {
            playing = false
            running = false
            runCatching { pause() }.onFailure { failure.compareAndSet(null, it) }
        }
    }

    private fun createTrack(): AudioTrack {
        val minimumBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minimumBytes > 0) { "Unsupported float stereo output ($minimumBytes)" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBytes, BLOCK_FRAMES * CHANNELS * Float.SIZE_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BLOCK_FRAMES = 2_048
    }
}
