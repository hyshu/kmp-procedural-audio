package bio.aq.audio.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import bio.aq.audio.AudioPlayer
import bio.aq.audio.NoiseSource
import bio.aq.audio.SineSource

/** Foreground demonstration. A production host must also manage audio focus. */
class MainActivity : Activity() {
    private lateinit var player: AudioPlayer
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val checkFailure = object : Runnable {
        override fun run() {
            player.takeFailure()?.let(::recoverFromFailure)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = AudioPlayer(SineSource())
        val spacing = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        content.addView(
            TextView(this).apply {
                text = "Kotlin procedural audio"
                textSize = 24f
            },
        )
        content.addView(
            TextView(this).apply {
                text = "Shared Kotlin generates stereo PCM at 48 kHz. AudioTrack plays it."
                setPadding(0, spacing / 2, 0, spacing)
            },
        )
        status = TextView(this).apply { text = "Ready" }
        content.addView(status)
        fun button(label: String, action: () -> Unit) {
            content.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener {
                        runCatching(action).onFailure(::recoverFromFailure)
                    }
                },
            )
        }
        button("Play") {
            player.play()
            status.text = "Playing"
        }
        button("Pause") {
            player.pause()
            status.text = "Paused"
        }
        button("Sine wave") {
            player.replaceSource(SineSource())
            status.text = "Sine selected"
        }
        button("Noise") {
            player.replaceSource(NoiseSource())
            status.text = "Noise selected"
        }
        setContentView(content)
    }

    private fun recoverFromFailure(error: Throwable) {
        player.close()
        player = AudioPlayer(SineSource())
        status.text = "${error.message ?: "Audio output failed"}. Tap Play to retry with a sine wave."
    }

    override fun onStart() {
        super.onStart()
        handler.post(checkFailure)
    }

    override fun onStop() {
        handler.removeCallbacks(checkFailure)
        player.pause()
        status.text = "Paused"
        super.onStop()
    }

    override fun onDestroy() {
        player.close()
        super.onDestroy()
    }
}
