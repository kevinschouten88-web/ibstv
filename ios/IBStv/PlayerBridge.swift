import UIKit
import WebKit
import AVFoundation
import AVKit
import MobileVLCKit

/// De ingebouwde speler van de iPhone. Er zitten twee spelers in:
///  - de Apple-speler (AVPlayer) voor zenders (m3u8) en gewone films (mp4, m4v, mov).
///    Daarmee werken beeld in beeld en AirPlay.
///  - VLC voor de rest: MKV, AVI, .ts en lastige formaten.
/// Het beeld ligt onder de doorzichtige WebView; JavaScript bedient de speler
/// via window.IBSPlayer en krijgt de toestand terug via window.__ibsPush().
final class PlayerBridge: NSObject, AVPictureInPictureControllerDelegate {
    let videoView: UIView
    weak var web: WKWebView?
    var onLayout: (() -> Void)?

    private enum Engine { case none, av, vlc }
    private var engine: Engine = .none

    // VLC
    private let vlc = VLCMediaPlayer()
    private let vlcView = UIView()

    // Apple-speler
    private var av: AVPlayer?
    private let avView = PlayerLayerView()
    private var pip: AVPictureInPictureController?
    private var statusObs: NSKeyValueObservation?
    private var endObs: NSObjectProtocol?
    private var avFailed = false
    private var avEnded = false
    private var userPaused = false

    // AirPlay-knop van het systeem (onzichtbaar; we tikken hem aan vanuit de app)
    let routePicker = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))

    private var timer: Timer?
    private var sid = 0
    private var state = "idle"          // idle, preparing, playing, paused, ended, error
    private var startedAt = Date()
    private var pendingAudio: Int32 = -1
    private var pendingStart: Double = 0
    private var picked = false

    /// Plaats van het beeld in CSS-pixels; nil = hele scherm.
    private(set) var box: CGRect?
    private var boxViewportWidth: CGFloat = 0

    init(videoView: UIView) {
        self.videoView = videoView
        super.init()
        for v in [vlcView, avView] {
            v.frame = videoView.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            v.backgroundColor = .black
            v.isHidden = true
            videoView.addSubview(v)
        }
        vlc.drawable = vlcView
        avView.playerLayer.videoGravity = .resizeAspect
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: avView.playerLayer)
            pip?.delegate = self
            if #available(iOS 14.2, *) { pip?.canStartPictureInPictureAutomaticallyFromInline = true }
        }
        routePicker.alpha = 0.011
        routePicker.isUserInteractionEnabled = false
    }

    // MARK: - Opdrachten uit JavaScript

    func handle(_ cmd: String, _ a: [Any], sid newSid: Int) {
        func num(_ i: Int) -> Double { i < a.count ? ((a[i] as? NSNumber)?.doubleValue ?? 0) : 0 }
        switch cmd {
        case "play":
            sid = newSid
            play(url: a.first as? String ?? "", start: num(1), audio: Int32(num(2)))
        case "pause":
            userPaused = true
            if engine == .av { av?.pause() } else if vlc.isPlaying { vlc.pause() }
        case "resume":
            userPaused = false
            if engine == .av {
                if avEnded { av?.seek(to: .zero); avEnded = false }
                av?.play()
            } else {
                if state == "ended" { vlc.stop() }
                vlc.play()
            }
        case "seek":
            var sec = max(0, num(0))
            let len = durationSec
            if len > 0 { sec = min(sec, max(0, len - 1.5)) }
            if engine == .av {
                av?.seek(to: CMTime(seconds: sec, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                if avEnded { avEnded = false; av?.play() }
            } else {
                vlc.time = VLCTime(int: Int32(sec * 1000))
                if state == "ended" { vlc.play() }
            }
        case "selectTrack":
            let i = Int(num(0))
            if engine == .av { selectAvTrack(i) }
            else if i >= 100000 { vlc.currentVideoSubTitleIndex = Int32(i - 100000) }
            else { vlc.currentAudioTrackIndex = Int32(i) }
        case "deselectTrack":
            if engine == .av, let item = av?.currentItem, let g = group(.legible) { item.select(nil, in: g) }
            else { vlc.currentVideoSubTitleIndex = -1 }
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

    // MARK: - Afspelen

    private static func usesApplePlayer(_ url: URL) -> Bool {
        ["m3u8", "mp4", "m4v", "mov"].contains(url.pathExtension.lowercased())
    }

    private func play(url: String, start: Double, audio: Int32) {
        stopEngines()
        guard let u = URL(string: url) else { state = "error"; push(); return }
        state = "preparing"
        picked = false
        pendingAudio = audio
        pendingStart = start
        startedAt = Date()
        userPaused = false
        videoView.isHidden = false
        if PlayerBridge.usesApplePlayer(u) { playAv(u, start: start) } else { playVlc(u, start: start) }
        startTimer()
        push()
    }

    private func playAv(_ u: URL, start: Double) {
        engine = .av
        avFailed = false; avEnded = false
        let asset = AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": SchemeHandler.userAgent]])
        let item = AVPlayerItem(asset: asset)
        let p = AVPlayer(playerItem: item)
        p.allowsExternalPlayback = true
        p.usesExternalPlaybackWhileExternalScreenIsActive = true
        av = p
        avView.playerLayer.player = p
        avView.isHidden = false
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if it.status == .failed { self.avFailed = true }
                if it.status == .readyToPlay && self.pendingStart > 0 {
                    self.av?.seek(to: CMTime(seconds: self.pendingStart, preferredTimescale: 600))
                    self.pendingStart = 0
                }
            }
        }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.avEnded = true
        }
        p.play()
    }

    private func playVlc(_ u: URL, start: Double) {
        engine = .vlc
        let media = VLCMedia(url: u)
        var opts: [String: Any] = ["http-user-agent": SchemeHandler.userAgent, "network-caching": 1500, "http-reconnect": true]
        if start > 0 { opts["start-time"] = start }
        media.addOptions(opts)
        vlc.media = media
        vlcView.isHidden = false
        vlc.play()
    }

    private func stopEngines() {
        if engine == .vlc || vlc.isPlaying { vlc.stop() }
        if let p = av {
            p.pause()
            p.replaceCurrentItem(with: nil)
        }
        statusObs?.invalidate(); statusObs = nil
        if let o = endObs { NotificationCenter.default.removeObserver(o); endObs = nil }
        if pip?.isPictureInPictureActive == true { pip?.stopPictureInPicture() }
        avView.playerLayer.player = nil
        av = nil
        avView.isHidden = true
        vlcView.isHidden = true
        engine = .none
    }

    private func stop() {
        stopEngines()
        state = "idle"
        videoView.isHidden = true
        timer?.invalidate(); timer = nil
        UIApplication.shared.isIdleTimerDisabled = false
        push()
    }

    /// De app gaat naar de achtergrond.
    func appWillResign() {
        if engine == .vlc { if vlc.isPlaying { vlc.pause() } }
        // De Apple-speler laten doorspelen: die gaat vanzelf naar beeld in beeld.
        else if engine == .av && (pip == nil) { av?.pause() }
    }

    // MARK: - AirPlay

    var externalActive: Bool { av?.isExternalPlaybackActive ?? false }

    var airPlayName: String? {
        guard externalActive else { return nil }
        let out = AVAudioSession.sharedInstance().currentRoute.outputs.first { $0.portType == .airPlay }
        return out?.portName ?? "AirPlay"
    }

    /// Opent het AirPlay-menu van het systeem.
    func showAirPlayPicker() {
        for case let b as UIButton in routePicker.subviews { b.sendActions(for: .touchUpInside); return }
    }

    // MARK: - Beeld in beeld

    func pictureInPictureController(_ c: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) {
        done(true)
    }

    // MARK: - Toestand

    private var timeSec: Double {
        if engine == .av { let t = av?.currentTime().seconds ?? 0; return t.isFinite ? max(0, t) : 0 }
        let t: VLCTime? = vlc.time
        return Double(max(0, t?.intValue ?? 0)) / 1000
    }

    private var durationSec: Double {
        if engine == .av { let d = av?.currentItem?.duration.seconds ?? 0; return d.isFinite ? max(0, d) : 0 }
        let m: VLCMedia? = vlc.media
        let l: VLCTime? = m?.length
        return Double(max(0, l?.intValue ?? 0)) / 1000
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            self?.update()
            self?.push()
        }
    }

    private func update() {
        if engine == .av { updateAv() } else if engine == .vlc { updateVlc() }
        if state == "playing" && !picked {
            picked = true
            if pendingAudio >= 0 {
                if engine == .av { selectAvTrack(Int(pendingAudio)) } else { vlc.currentAudioTrackIndex = pendingAudio }
            }
        }
        UIApplication.shared.isIdleTimerDisabled = (state == "playing")
    }

    private func updateAv() {
        guard let p = av else { return }
        if avFailed { state = "error"; return }
        if avEnded { state = "ended"; return }
        switch p.timeControlStatus {
        case .playing: state = "playing"
        case .paused: if userPaused || state != "preparing" { state = "paused" }
        case .waitingToPlayAtSpecifiedRate: if state != "preparing" { state = "playing" }
        @unknown default: break
        }
        if state == "preparing" && Date().timeIntervalSince(startedAt) > 25 && p.currentItem?.status != .readyToPlay { state = "error" }
    }

    private func updateVlc() {
        let vs = vlc.state
        switch vs {
        case .error:
            state = "error"
        case .paused:
            if state != "preparing" { state = "paused" }
        case .playing:
            state = "playing"
        case .buffering, .opening:
            if vlc.isPlaying && timeSec > 0 { state = "playing" }
        case .stopped, .ended:
            if state == "preparing" {
                if Date().timeIntervalSince(startedAt) > 1.5 { state = "error" }
            } else if state == "playing" || state == "paused" {
                state = "ended"
            }
        default:
            break
        }
    }

    private var buffering: Bool {
        if state == "preparing" { return true }
        if engine == .av { return av?.timeControlStatus == .waitingToPlayAtSpecifiedRate }
        let vs = vlc.state
        return (vs == .buffering || vs == .opening) && !vlc.isPlaying && state != "paused"
    }

    // Geluid en ondertitels van de Apple-speler
    private func group(_ c: AVMediaCharacteristic) -> AVMediaSelectionGroup? {
        av?.currentItem?.asset.mediaSelectionGroup(forMediaCharacteristic: c)
    }

    private func selectAvTrack(_ i: Int) {
        guard let item = av?.currentItem else { return }
        if i >= 100000 {
            if let g = group(.legible), i - 100000 < g.options.count { item.select(g.options[i - 100000], in: g) }
        } else if let g = group(.audible), i < g.options.count {
            item.select(g.options[i], in: g)
        }
    }

    private func tracks() -> [[String: Any]] {
        var out: [[String: Any]] = []
        if engine == .av {
            if let g = group(.audible) {
                for (i, o) in g.options.enumerated() { out.append(["i": i, "kind": "audio", "lang": o.displayName]) }
            }
            if let g = group(.legible) {
                for (i, o) in g.options.enumerated() { out.append(["i": 100000 + i, "kind": "text", "lang": o.displayName]) }
            }
            return out
        }
        let ai = (vlc.audioTrackIndexes as? [NSNumber]) ?? []
        let an = (vlc.audioTrackNames as? [String]) ?? []
        for (k, idx) in ai.enumerated() where idx.int32Value >= 0 {
            out.append(["i": idx.intValue, "kind": "audio", "lang": k < an.count ? an[k] : ""])
        }
        let si = (vlc.videoSubTitlesIndexes as? [NSNumber]) ?? []
        let sn = (vlc.videoSubTitlesNames as? [String]) ?? []
        for (k, idx) in si.enumerated() where idx.int32Value >= 0 {
            out.append(["i": 100000 + idx.intValue, "kind": "text", "lang": k < sn.count ? sn[k] : ""])
        }
        return out
    }

    private var audioSel: Int {
        if engine == .av {
            guard let item = av?.currentItem, let g = group(.audible), let o = item.currentMediaSelection.selectedMediaOption(in: g) else { return -1 }
            return g.options.firstIndex(of: o) ?? -1
        }
        return Int(vlc.currentAudioTrackIndex)
    }

    private var videoSize: CGSize {
        if engine == .av { return av?.currentItem?.presentationSize ?? .zero }
        return vlc.videoSize
    }

    private func push() {
        guard let web = web else { return }
        let size = videoSize
        let st: [String: Any] = [
            "sid": sid,
            "state": state,
            "t": timeSec,
            "d": durationSec,
            "w": Int(size.width), "h": Int(size.height),
            "buffering": buffering,
            "audioSel": audioSel,
            "tracks": state == "idle" ? [] : tracks(),
            "sub": "",
            "error": state == "error" ? (engine == .av ? "AV" : "VLC") : ""
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: st),
              let json = String(data: data, encoding: .utf8) else { return }
        web.evaluateJavaScript("window.__ibsPush && window.__ibsPush(\(json))", completionHandler: nil)
    }
}

/// Een view waarvan de laag een AVPlayerLayer is (nodig voor beeld in beeld).
final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
