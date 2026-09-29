# Native desktop sample

This console program uses the same `AudioPlayer` API on macOS, Windows, and Linux.

Run Gradle from the project root with JDK 21.
Gradle downloads the matching Kotlin Native compiler and native dependencies.
Windows and Linux use those dependencies to build the output library.

## macOS

Install the Xcode command line tools.

```sh
./gradlew -PskipAndroid=true :samples:desktop:linkDebugExecutableMacosArm64
./samples/desktop/build/bin/macosArm64/debugExecutable/pcm-audio-demo.kexe
```

Use `macosX64` for an Intel Mac.

## Linux

Install CMake and Ninja. Playback needs the system ALSA library.
The build loads ALSA at runtime and does not need its development headers.

```sh
sudo apt-get install cmake ninja-build
./gradlew -PskipAndroid=true :samples:desktop:linkDebugExecutableLinuxX64
./samples/desktop/build/bin/linuxX64/debugExecutable/pcm-audio-demo.kexe
```

## Windows

Use an x64 Windows machine with JDK 21, CMake, and Ninja on your path.
Gradle uses the MinGW compiler dependencies provided by Kotlin Native.

```sh
./gradlew -PskipAndroid=true :samples:desktop:linkDebugExecutableMingwX64
./samples/desktop/build/bin/mingwX64/debugExecutable/pcm-audio-demo.exe
```

The Windows and Linux targets can also be built from macOS.
Run the resulting executable on its target operating system.

For an existing compiler installation, Gradle respects `kotlin.native.home`
and `KONAN_DATA_DIR`. Native toolchain paths can be overridden with
`PCM_AUDIO_LLVM_ROOT`, `PCM_AUDIO_MINGW_ROOT`, and `PCM_AUDIO_LINUX_ROOT`.

## Run

Press Enter to start the tone.
Press Enter again to change to noise, pause, resume, and close.
The program reports output failures and closes the player when it exits.
