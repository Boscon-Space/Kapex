// Decode an AVI with AVFoundation and count the frames it can read.
// usage: swift tools/avi_check.swift <file.avi>
import AVFoundation
let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ""
let asset = AVURLAsset(url: URL(fileURLWithPath: path))
let sem = DispatchSemaphore(value: 0)
var code: Int32 = 1
Task {
    do {
        let duration = try await asset.load(.duration)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard let track = tracks.first else { print("FAIL: no video track in \(path)"); sem.signal(); return }
        let size = try await track.load(.naturalSize)
        let fps = try await track.load(.nominalFrameRate)
        let desc = try await track.load(.formatDescriptions).first
        let codec = desc.map { String(describing: CMFormatDescriptionGetMediaSubType($0)) } ?? "?"
        let reader = try AVAssetReader(asset: asset)
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(out); reader.startReading()
        var n = 0
        while out.copyNextSampleBuffer() != nil { n += 1 }
        let status = reader.status == .completed ? "completed" : "status \(reader.status.rawValue) \(reader.error?.localizedDescription ?? "")"
        print("OK: \(Int(size.width))x\(Int(size.height)) codec=\(codec) fps=\(fps) duration=\(CMTimeGetSeconds(duration)) s decoded_frames=\(n) reader=\(status)")
        code = (n > 0 && reader.status == .completed) ? 0 : 1
    } catch { print("FAIL: \(error)") }
    sem.signal()
}
sem.wait()
exit(code)
