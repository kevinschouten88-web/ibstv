import UIKit
import WebKit
import MobileVLCKit

/// De ingebouwde speler van de iPhone (VLC).
/// Speelt zenders, films en series af, ook MKV, H.265, AC3 en DTS.
/// Het beeld ligt onder de doorzichtige WebView; JavaScript bedient de speler
/// via window.IBSPlayer en krijgt de toestand terug via window.__ibsPush().
final class PlayerBridge: NSObject {
    let videoView: UIView
    weak var web: WKWebView?
    var onLayout: (() -> Void)?

    private let player = VLCMediaPlayer()
    private var timer: Timer?
    private var sid = 0
    private var state = "idle"          // idle, preparing, playing, paused, ended, error
    private var startedAt = Date()
    private var pendingAudio: Int32 = -1
    private var picked = false

    /// Plaats van het beeld in CSS-pixels; nil = hele scherm.
    private(set) var box: CGRect?
    private var boxViewportWidth: CGFloat = 0

    init(videoView: UIView) {
        self.videoView = videoView
        super.init()
        player.drawable = videoView
    }

    // MARK: - Opdrachten uit JavaScript

    func handle(_ cmd: String, _ a: [Any], sid newSid: Int) {
        func num(_ i: Int) -> Double { i < a.count ? ((a[i] as? NSNumber)?.doubleValue ?? 0) : 0 }
        switch cmd {
        case "play":
            sid = newSid
            play(url: a.first as? String ?? "", start: num(1), audio: Int32(num(2)))
        case "pause":
            if player.isPlaying { player.pause() }
        case "resume":
            if state == "ended" { player.stop() }
            player.play()
        case "seek":
            var ms = Int32(max(0, num(0)) * 1000)
            let len = lengthMs
            if len > 0 { ms = min(ms, max(0, len - 1500)) }
            player.time = VLCTime(int: ms)
            if state == "ended" { player.play() }
        case "selectTrack":
            let i = Int32(num(0))
            if i >= 100000 { player.currentVideoSubTitleIndex = i - 100000 } else { player.currentAudioTrackIndex = i }
        case "deselectTrack":
            player.currentVideoSubTitleIndex = -1
        case "stop":
            sid = newSid
            stop()
        case "layout":
            let w = num(2), h = num(3)
            if w > 0 && h > 0 {
                box = CGRect(x: num(0), y: num(1), width: w, height: h)
                boxViewportWidth = CGFloat(num(4))
            } else {
                box = nil
            }
            onLayout?()
        default:
            break
        }
    }

    /// Rechthoek voor het beeld binnen de WebView, in punten.
    func videoFrame(in bounds: CGRect) -> CGRect {
        guard let b = box, boxViewportWidth > 0 else { return bounds }
        let k = bounds.width / boxViewportWidth
        return CGRect(x: b.minX * k, y: b.minY * k, width: b.width * k, height: b.height * k)
    }

    private func play(url: String, start: Double, audio: Int32) {
        player.stop()
        guard let u = URL(string: url) else { state = "error"; push(); return }
        let media = VLCMedia(url: u)
        var opts: [String: Any] = ["http-user-agent": SchemeHandler.userAgent,
                                   "network-caching": 1500,
                                   "http-reconnect": true]
        if start > 0 { opts["start-time"] = start }
        media.addOptions(opts)
        player.media = media
        state = "preparing"
        picked = false
        pendingAudio = audio
        startedAt = Date()
        videoView.isHidden = false
        player.play()
        startTimer()
        push()
    }

    private func stop() {
        player.stop()
        state = "idle"
        videoView.isHidden = true
        timer?.invalidate(); timer = nil
        UIApplication.shared.isIdleTimerDisabled = false
        push()
    }

    func pauseForBackground() {
        if player.isPlaying { player.pause() }
    }

    // MARK: - Toestand

    private var timeMs: Int32 {
        let t: VLCTime? = player.time
        return t?.intValue ?? 0
    }
    private var lengthMs: Int32 {
        let m: VLCMedia? = player.media
        let l: VLCTime? = m?.length
        return l?.intValue ?? 0
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            self?.update()
            self?.push()
        }
    }

    private func update() {
        let vs = player.state
        let t = timeMs
        switch vs {
        case .error:
            state = "error"
        case .paused:
            if state != "preparing" { state = "paused" }
        case .playing:
            state = "playing"
        case .buffering, .opening:
            if player.isPlaying && t > 0 { state = "playing" }
        case .stopped, .ended:
            if state == "preparing" {
                if Date().timeIntervalSince(startedAt) > 1.5 { state = "error" }
            } else if state == "playing" || state == "paused" {
                state = "ended"
            }
        default:
            break
        }
        if state == "playing" && !picked {
            picked = true
            if pendingAudio >= 0 { player.currentAudioTrackIndex = pendingAudio }
        }
        UIApplication.shared.isIdleTimerDisabled = (state == "playing")
    }

    private func tracks() -> [[String: Any]] {
        var out: [[String: Any]] = []
        let ai = (player.audioTrackIndexes as? [NSNumber]) ?? []
        let an = (player.audioTrackNames as? [String]) ?? []
        for (k, idx) in ai.enumerated() where idx.int32Value >= 0 {
            out.append(["i": idx.intValue, "kind": "audio", "lang": k < an.count ? an[k] : ""])
        }
        let si = (player.videoSubTitlesIndexes as? [NSNumber]) ?? []
        let sn = (player.videoSubTitlesNames as? [String]) ?? []
        for (k, idx) in si.enumerated() where idx.int32Value >= 0 {
            out.append(["i": 100000 + idx.intValue, "kind": "text", "lang": k < sn.count ? sn[k] : ""])
        }
        return out
    }

    private func push() {
        guard let web = web else { return }
        let vs = player.state
        let size = player.videoSize
        let len = lengthMs
        let st: [String: Any] = [
            "sid": sid,
            "state": state,
            "t": Double(max(0, timeMs)) / 1000,
            "d": Double(max(0, len)) / 1000,
            "w": Int(size.width), "h": Int(size.height),
            "buffering": state == "preparing" || ((vs == .buffering || vs == .opening) && !player.isPlaying && state != "paused"),
            "audioSel": Int(player.currentAudioTrackIndex),
            "tracks": state == "idle" ? [] : tracks(),
            "sub": "",
            "error": state == "error" ? "VLC" : ""
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: st),
              let json = String(data: data, encoding: .utf8) else { return }
        web.evaluateJavaScript("window.__ibsPush && window.__ibsPush(\(json))", completionHandler: nil)
    }
}
