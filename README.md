# KMP Procedural Audio

A lightweight audio playback library for Kotlin Multiplatform.

Create sounds in shared Kotlin code and play them on Android, iOS, macOS, watchOS, Windows, Linux, and the web.
It provides a small shared API for playback control and smooth source switching, backed by platform audio outputs.

## Play a source

```kotlin
val player = AudioPlayer(SineSource())
player.play()
```

Import these classes from `bio.aq.audio`.
Keep the player while you need audio and call `close()` when you are done.
Use `pause()` to pause playback and `play()` to resume it.
Call `replaceSource(NoiseSource())` to switch to noise during playback.

Each source fills interleaved stereo float samples at 48 kHz.
Implement `PcmSource` to provide your own sound.

To use this source checkout from another Gradle project, add a composite build in
that project's settings and add the library to its common dependencies.

```kotlin
// settings.gradle.kts
includeBuild("../kmp-procedural-audio")

// build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("bio.aq.audio:audio:0.1.1")
    }
}
```

This release has not been uploaded to a package registry.

Call player methods from one control thread.
The output calls your source on its audio thread and reuses the buffer.
Keep rendering short and avoid blocking work or calling player methods from a source.
Give each player its own source instance.
Use `takeFailure()` to read asynchronous output errors.

Use `CrossfadeSource` when a host needs to render directly into its own PCM buffer.
It implements `PcmSource` and accepts a custom transition length in frames.
Call `replaceSource()` to switch sounds smoothly.

## Targets

| Target                           | Output        |
| -------------------------------- | ------------- |
| Android                          | AudioTrack    |
| iOS                              | AVAudioEngine |
| macOS on Apple Silicon and Intel | AVAudioEngine |
| watchOS                          | AVAudioEngine |
| Windows x64                      | WASAPI        |
| Linux x64                        | ALSA          |
| Web with Kotlin/JS               | AudioWorklet  |

Desktop support uses Kotlin/Native.
The desktop player owns its output and can be used directly from Kotlin.
Windows and Linux use a small C bridge included in the library build.

Browser audio runs inside an AudioWorklet.
The Web sample sends commands from the page and generates samples in the worklet.
Custom Kotlin sources must be compiled into that worklet module.
See the [Web sample](samples/web/README.md) for the browser host.

## Build and try

Use JDK 21 and the included Gradle wrapper.
Android builds also need Android SDK 36.
Pass `-PskipAndroid=true` when working without the Android SDK.

On an Apple Silicon Mac, build the console sample and run it from a terminal.

```sh
./gradlew -PskipAndroid=true :audio:macosArm64Test :samples:desktop:linkDebugExecutableMacosArm64
./samples/desktop/build/bin/macosArm64/debugExecutable/pcm-audio-demo.kexe
```

The sample plays a quiet tone, switches to noise, pauses, and resumes.
Press Enter at each step.
Use `macosX64` in place of `macosArm64` for an Intel Mac.

More examples are in [Android](samples/android/README.md),
[Apple](samples/apple/README.md), and [Web](samples/web/README.md).
Desktop build instructions are in [the desktop sample](samples/desktop/README.md).
See [verification notes](VERIFICATION.md) for the checks run on this release.

## Development checks

CI runs on Linux, Windows, and both Mac architectures.
It also runs iOS simulator tests, Android host tests and lint, and Chromium playback tests.
watchOS targets are compiled.

On macOS or Linux, install the pinned development tools before checking source formatting.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements-quality.txt
npm ci
CLANG_FORMAT="$PWD/.venv/bin/clang-format" ./gradlew -PskipAndroid=true spotlessCheck
npm run check
.venv/bin/ruff check tools
.venv/bin/ruff format --check tools
```

On macOS, also run the Swift sample check.
CI uses the formatter from Xcode 26.3.

```sh
xcrun swift-format lint --strict --recursive samples/apple
```

Use `spotlessApply`, `npm run format`, and `ruff format tools` to apply formatting.

## Scope

This first version uses a fixed audio format and a 50 ms source crossfade.
Pause preserves the source position. Close releases the output and cannot be undone.
The host app manages audio focus, interruptions, routes, and background playback.
On iOS the backend owns the shared playback session, so use one player at a time.
watchOS requires the host to activate its audio session.

The library and sample sources use the MIT license.
The Gradle wrapper retains its license and notices in `gradle/wrapper`.
