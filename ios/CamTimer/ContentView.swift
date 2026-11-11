import SwiftUI
import AVFoundation
import AudioToolbox

struct ContentView: View {
    @StateObject private var camera = CameraService()
    @AppStorage("durationSeconds") private var configuredSeconds = 180
    @AppStorage("alertEnabled") private var alertEnabled = true
    @AppStorage("alertHz") private var alertHz = 800
    @AppStorage("alertDuration") private var alertDuration = 2.0
    @AppStorage("alertAt") private var alertAt = 15
    @AppStorage("autoRecord") private var autoRecord = false
    @AppStorage("autoRecordAt") private var autoRecordAt = 15
    @State private var remaining = 180.0
    @State private var running = false
    @State private var lastTick = Date()
    @State private var alertPlayed = false
    @State private var autoTriggered = false
    @State private var showSettings = false
    @State private var showDuration = false
    @State private var samples: [(Double, Double)] = []
    @State private var recordStarted: Date?
    @State private var beepPlayer: AVAudioPlayer?
    private let ticker = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    private var timeLabel: String {
        let n = Int(ceil(abs(remaining)))
        return "\(remaining <= -1 ? "+" : "")\(String(format: "%02d:%02d", n / 60, n % 60))"
    }

    private var cue: String? {
        guard running else { return nil }
        if remaining <= 0 && remaining > -1 { return "Départ" }
        let seconds = Int(ceil(remaining))
        if seconds == 10 || (1...5).contains(seconds) { return String(seconds) }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                CameraPreview(session: camera.session)
                    .ignoresSafeArea(edges: .top)
                VStack {
                    Text(timeLabel)
                        .font(.system(size: 42, weight: .bold, design: .monospaced))
                        .foregroundStyle(remaining < 0 ? .green : .white)
                        .frame(maxWidth: .infinity)
                        .padding(8)
                        .background(.black.opacity(0.35))
                        .onTapGesture { if !running { showDuration = true } }
                    Spacer()
                }
                if let cue = cue {
                    Text(cue)
                        .font(.system(size: cue == "Départ" ? 70 : 190, weight: .bold))
                        .foregroundStyle(.white)
                        .minimumScaleFactor(0.3)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .shadow(color: .black, radius: 5)
                        .allowsHitTesting(false)
                }
                HStack {
                    Spacer()
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                            .font(.system(size: 28))
                            .foregroundStyle(.white)
                            .padding(12)
                    }
                }
            }
            .frame(maxHeight: .infinity)
            HStack {
                Button(action: toggleTimer) {
                    Image(systemName: running ? "stop.fill" : "play.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(.blue)
                        .frame(width: 68, height: 68)
                        .background(.white, in: Circle())
                }
                .frame(maxWidth: .infinity)
                Button(action: toggleRecording) {
                    ZStack {
                        Circle().fill(.white).frame(width: 68, height: 68)
                        Circle().fill(Color(red: 0.09, green: 0.11, blue: 0.14)).frame(width: 62, height: 62)
                        if camera.recording {
                            RoundedRectangle(cornerRadius: 5).fill(.red).frame(width: 27, height: 27)
                        } else {
                            Circle().fill(.red).frame(width: 43, height: 43)
                        }
                    }
                }
                .disabled(camera.processing)
                .frame(maxWidth: .infinity)
            }
            .padding(.vertical, 12)
        }
        .background(Color(red: 20/255, green: 24/255, blue: 32/255))
        .preferredColorScheme(.dark)
        .onAppear {
            remaining = Double(configuredSeconds)
            camera.setup()
            camera.onFinished = { url in
                camera.processing = true
                // Original video is saved until the timed overlay export is implemented.
                camera.saveToPhotos(url) { _ in camera.processing = false }
            }
        }
        .onReceive(ticker) { now in
            if running {
                let before = remaining
                remaining -= now.timeIntervalSince(lastTick)
                lastTick = now
                if alertEnabled && !alertPlayed && before > Double(alertAt) && remaining <= Double(alertAt) && remaining > 0 {
                    alertPlayed = true
                    playAlert()
                }
                if autoRecord && !autoTriggered && before > Double(autoRecordAt) && remaining <= Double(autoRecordAt) && !camera.recording {
                    autoTriggered = true
                    startRecording()
                }
            }
            if let started = recordStarted {
                samples.append((now.timeIntervalSince(started), remaining))
            }
        }
        .sheet(isPresented: $showSettings) { settingsSheet }
        .sheet(isPresented: $showDuration) { durationSheet }
        .alert("CamTimer", isPresented: Binding(get: { camera.message != nil }, set: { if !$0 { camera.message = nil } })) {
            Button("OK") { camera.message = nil }
        } message: {
            Text(camera.message ?? "")
        }
    }

    private func toggleTimer() {
        if running {
            if camera.recording { camera.stop() }
            running = false
            remaining = Double(configuredSeconds)
            alertPlayed = false
            autoTriggered = false
        } else {
            running = true
            lastTick = Date()
            alertPlayed = false
            autoTriggered = false
        }
        UIApplication.shared.isIdleTimerDisabled = running || camera.recording
    }

    private func startRecording() {
        samples.removeAll()
        recordStarted = Date()
        camera.start()
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func toggleRecording() {
        if camera.recording {
            camera.stop()
            recordStarted = nil
        } else {
            startRecording()
        }
    }

    private func playAlert() {
        let rate = 44100.0
        let count = Int(rate * alertDuration)
        var pcm = Data(capacity: count * 2)
        for i in 0..<count {
            let fade = min(1.0, Double(i) / 220, Double(count - i) / 220)
            var sample = Int16(sin(2 * .pi * Double(alertHz) * Double(i) / rate) * 21000 * fade).littleEndian
            withUnsafeBytes(of: &sample) { pcm.append(contentsOf: $0) }
        }
        let header = wavHeader(sampleCount: count, sampleRate: Int(rate))
        do {
            beepPlayer = try AVAudioPlayer(data: header + pcm)
            beepPlayer?.play()
        } catch { camera.message = "Impossible de jouer le signal sonore" }
    }

    private func wavHeader(sampleCount: Int, sampleRate: Int) -> Data {
        var data = Data()
        func word(_ value: UInt32, bytes: Int) {
            for i in 0..<bytes { data.append(UInt8((value >> (i * 8)) & 255)) }
        }
        data.append(contentsOf: Array("RIFF".utf8))
        word(UInt32(36 + sampleCount * 2), bytes: 4)
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        word(16, bytes: 4)
        word(1, bytes: 2)
        word(1, bytes: 2)
        word(UInt32(sampleRate), bytes: 4)
        word(UInt32(sampleRate * 2), bytes: 4)
        word(2, bytes: 2)
        word(16, bytes: 2)
        data.append(contentsOf: Array("data".utf8))
        word(UInt32(sampleCount * 2), bytes: 4)
        return data
    }

    private var durationSheet: some View {
        NavigationStack {
            Form {
                Stepper("Minutes : \(configuredSeconds / 60)", value: Binding(get: { configuredSeconds / 60 }, set: { configuredSeconds = $0 * 60 + configuredSeconds % 60 }), in: 0...99)
                Stepper("Secondes : \(configuredSeconds % 60)", value: Binding(get: { configuredSeconds % 60 }, set: { configuredSeconds = configuredSeconds / 60 * 60 + $0 }), in: 0...59)
            }
            .navigationTitle("Durée du chronomètre")
            .toolbar {
                Button("Valider") {
                    configuredSeconds = max(1, configuredSeconds)
                    remaining = Double(configuredSeconds)
                    showDuration = false
                }
            }
        }
    }

    private var settingsSheet: some View {
        NavigationStack {
            Form {
                Toggle("Activer le signal sonore", isOn: $alertEnabled)
                Stepper("Fréquence : \(alertHz) Hz", value: $alertHz, in: 100...5000, step: 100)
                Stepper("Durée : \(alertDuration, specifier: "%.1f") s", value: $alertDuration, in: 0.5...5.0, step: 0.5)
                Stepper("Son à : \(alertAt) s restantes", value: $alertAt, in: 1...120)
                Section("Enregistrement automatique") {
                    Toggle("Démarrer REC automatiquement", isOn: $autoRecord)
                    Stepper("REC à : \(autoRecordAt / 60) min \(autoRecordAt % 60) s", value: $autoRecordAt, in: 0...5999)
                }
            }
            .navigationTitle("Paramètres CamTimer")
            .toolbar { Button("Terminé") { showSettings = false } }
        }
    }
}
