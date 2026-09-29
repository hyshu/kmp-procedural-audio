# Apple sample

This small SwiftUI view plays a quiet sine wave through the Kotlin audio player.
It pauses when the app becomes inactive and closes the player when the view leaves the screen.

Build the framework for an Apple Silicon iOS simulator from the repository root.

```sh
./gradlew :audio:linkDebugFrameworkIosSimulatorArm64
```

Create an iOS SwiftUI app in Xcode with iOS 15 or later as its deployment target.
Add `audio/build/bin/iosSimulatorArm64/debugFramework/PcmAudio.framework` to the app target.
Choose Do Not Embed because the framework is static.
Replace the generated `ContentView.swift` with this sample and run the app in a simulator.
Use `linkDebugFrameworkIosArm64` and the matching framework directory for an iPhone.
Rebuild and replace the framework whenever you change the Kotlin source.

The iOS backend configures and activates the shared playback audio session.
Pause and close deactivate that session.
Use one audio player at a time in this first version.
Apps that already manage a recording or mixed audio session need a different session policy.

The host app handles interruptions and route changes.
Call `pause()` when an interruption begins and call `play()` after the session can resume.
The sample pauses with the app lifecycle and does not provide background playback controls.
Check `takeFailure()` from the host if you need to show errors raised by the audio callback.
Session cleanup errors after close are also available through `takeFailure()`.

The same backend supports macOS without an audio session.
Kotlin applications can use the native console sample without Swift.

The watch app must configure and activate its own audio session before calling `play()`.
The host remains responsible for audio routing and long-form playback activation.
