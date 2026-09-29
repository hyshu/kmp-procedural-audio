# Web sample

Build the Kotlin ES module and copy it beside the browser host.

```sh
./gradlew -PskipAndroid=true :audio:jsBrowserProductionLibraryDistribution
node samples/web/stage.mjs
python3 -m http.server 8080 --directory samples/web/dist
```

Open [the local sample](http://localhost:8080). Tap Play to activate audio, switch the source while playing, then pause. HTTPS or localhost and AudioWorklet support are required. The sample requests a 48 kHz AudioContext and reports an error if that rate is unavailable.

`WebAudioEngine` is an exported bridge created inside the AudioWorkletProcessor. It uses the common `AudioPlayer`, sine, noise, and crossfade code. Custom Kotlin sources belong in that same compiled worklet module. JavaScript cannot transfer arbitrary Kotlin objects from the browser window into an audio worklet.

The main-thread `BrowserAudio` host loads the worklet after a user gesture and sends commands through its MessagePort. It suspends the AudioContext on pause and closes it on page exit or processor failure. It handles commands arriving while the module loads. PCM stays on the audio rendering thread.

Run host lifecycle tests without a browser. The compiled tests require the build and staging steps above.

```sh
node --test samples/web/browser-audio.test.mjs
node --test samples/web/compiled-audio.test.mjs
```

Host lifecycle tests use fake browser objects. The compiled tests run the production Kotlin ES module and processor glue with real PCM buffers. They check finite output, source switching, arbitrary callback sizes, pause and resume, cleanup, and errors.
