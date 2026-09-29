# Android sample

Run from the repository root with Android SDK 36 installed.

```sh
./gradlew :samples:android:installDebug
adb shell am start -n bio.aq.audio.sample/.MainActivity
```

Tap Play, then switch between sine and noise. Source changes use the shared crossfade. Pause preserves the current source. Leaving the activity pauses audio and destroying it releases the player.

The host app manages audio focus, media controls, and background playback.

The backend uses 2048-frame render blocks and blocking AudioTrack writes. It makes no low-latency guarantee. Source callbacks must be bounded and nonblocking. Call player controls from the host thread, never from the render callback. Closing waits for the writer to finish before releasing AudioTrack.

If output fails, the sample releases that player and creates a fresh sine source. Tap Play to retry.
