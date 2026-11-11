import AVFoundation
import Photos
import UIKit

final class CameraService: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "camtimer.camera")
    private var sourceURL: URL?
    @Published var recording = false
    @Published var processing = false
    @Published var message: String?
    var onFinished: ((URL) -> Void)?

    func setup() {
        AVCaptureDevice.requestAccess(for: .video) { granted in
            guard granted else {
                DispatchQueue.main.async { self.message = "Autorisation caméra nécessaire" }
                return
            }
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                self.queue.async {
                    self.session.beginConfiguration()
                    self.session.sessionPreset = .hd1280x720
                    if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                       let input = try? AVCaptureDeviceInput(device: camera),
                       self.session.canAddInput(input) {
                        self.session.addInput(input)
                    }
                    if let microphone = AVCaptureDevice.default(for: .audio),
                       let input = try? AVCaptureDeviceInput(device: microphone),
                       self.session.canAddInput(input) {
                        self.session.addInput(input)
                    }
                    if self.session.canAddOutput(self.output) { self.session.addOutput(self.output) }
                    self.session.commitConfiguration()
                    self.session.startRunning()
                }
            }
        }
    }

    func start() {
        queue.async {
            guard !self.output.isRecording else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("CamTimer_\(UUID().uuidString).mov")
            self.sourceURL = url
            self.output.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stop() {
        queue.async {
            if self.output.isRecording { self.output.stopRecording() }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        DispatchQueue.main.async { self.recording = true }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        DispatchQueue.main.async {
            self.recording = false
            if let error = error { self.message = error.localizedDescription }
            else { self.onFinished?(outputFileURL) }
        }
    }

    func saveToPhotos(_ url: URL, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { self.message = "Autorisation Photos nécessaire"; completion(false) }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            }, completionHandler: { saved, error in
                DispatchQueue.main.async {
                    self.message = saved ? "Vidéo enregistrée dans Photos" : (error?.localizedDescription ?? "Erreur de sauvegarde")
                    completion(saved)
                }
            })
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface()
        view.layerVideo.session = session
        view.layerVideo.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ uiView: PreviewSurface, context: Context) {}
}

final class PreviewSurface: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var layerVideo: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
