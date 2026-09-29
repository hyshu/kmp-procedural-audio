import PcmAudio
import SwiftUI

@MainActor
final class AudioDemo: ObservableObject {
    @Published var isPlaying = false
    @Published var message = "Ready to play a quiet sine wave"

    private var player: AudioPlayer?

    func play() {
        let output = player ?? AudioPlayer(source: SineSource(frequencyHz: 220, amplitude: 0.12))
        player = output
        do {
            try output.play()
            isPlaying = true
            message = "Playing a quiet sine wave"
        } catch {
            isPlaying = false
            message = error.localizedDescription
        }
    }

    func pause() {
        do {
            try player?.pause()
            isPlaying = false
            message = "Paused"
        } catch {
            isPlaying = false
            message = error.localizedDescription
        }
    }

    func close() {
        do {
            try player?.close()
        } catch {
            message = error.localizedDescription
        }
        player = nil
        isPlaying = false
    }
}

struct ContentView: View {
    @StateObject private var audio = AudioDemo()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 20) {
            Text("Procedural audio")
                .font(.title)
            Text(audio.message)
            Button(audio.isPlaying ? "Pause" : "Play") {
                if audio.isPlaying {
                    audio.pause()
                } else {
                    audio.play()
                }
            }
        }
        .padding()
        .onChange(of: scenePhase) { phase in
            if phase != .active {
                audio.pause()
            }
        }
        .onDisappear {
            audio.close()
        }
    }
}
