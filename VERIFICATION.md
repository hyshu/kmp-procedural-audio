# Verification

The Verify workflow checks pull requests and changes on main.
Each desktop target runs on its own operating system and CPU architecture.

| Runner                         | Checks                                                                                                          |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| Ubuntu                         | Kotlin and C++ formatting, JavaScript lint, Prettier, Ruff, and actionlint                                      |
| macOS ARM64 and Intel          | Shared and Apple tests, desktop sample linking, and native callback tests                                       |
| macOS ARM64 with iOS simulator | Shared and Apple tests on iOS, device and simulator frameworks, Swift sample typecheck, and watchOS compilation |
| Ubuntu with Android SDK        | Shared host tests, Android lint, and sample APK                                                                 |
| Ubuntu with Chromium           | Kotlin JS tests, compiled worklet tests, and browser playback controls                                          |
| Linux x64                      | Kotlin Native tests, desktop sample linking, native lifecycle tests, and real ALSA null output                  |
| Windows x64                    | Kotlin Native tests, desktop sample linking, native lifecycle tests, and WASAPI smoke test                      |

Linux uses the ALSA null device so its backend can render without physical speakers.
The Windows smoke test is marked skipped when the runner has no default audio endpoint.
Other WASAPI failures fail the test.
Android host tests do not exercise AudioTrack on an Android device.
The iOS simulator tests check rendering and buffer conversion without audio hardware.
Physical device playback, interruptions, route changes, long sessions, and power use
are outside the scope of this CI suite.
These checks do not establish physical playback quality or latency.

## Repeat the local checks

```sh
./gradlew -PskipAndroid=true :audio:macosArm64Test :audio:jsNodeTest :audio:jsBrowserProductionLibraryDistribution
node samples/web/stage.mjs
node --test samples/web/*.test.mjs
cmake -S audio/native -B audio/build/lifecycle -DPCM_AUDIO_BUILD_BACKEND=OFF -DBUILD_TESTING=ON
cmake --build audio/build/lifecycle
ctest --test-dir audio/build/lifecycle --output-on-failure
```
