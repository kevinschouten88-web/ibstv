import UIKit
import WebKit
import GoogleCast

/// Casten vanaf de iPhone: Chromecast (via Google Cast) en AirPlay (via de Apple-speler).
/// De lijst met apparaten en de huidige verbinding gaan naar JavaScript via window.__ibsCast().
final class CastBridge: NSObject, GCKDiscoveryManagerListener, GCKSessionManagerListener {
    weak var web: WKWebView?
    weak var player: PlayerBridge?
    private var pending: GCKMediaLoadRequestData?
    private var timer: Timer?

    static func setUp() {
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
    }

    override init() {
        super.init()
        let ctx = GCKCastContext.sharedInstance()
        ctx.discoveryManager.add(self)
        ctx.discoveryManager.startDiscovery()
        ctx.sessionManager.add(self)
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in self?.push() }
    }

    // MARK: - Opdrachten uit JavaScript

    func handle(_ cmd: String, _ a: [Any]) {
        switch cmd {
        case "castConnect":
            let id = a.first as? String ?? ""
            if id == "airplay" { player?.showAirPlayPicker(); return }
            let dm = GCKCastContext.sharedInstance().discoveryManager
            for i in 0..<dm.deviceCount {
                let d = dm.device(at: i)
                if d.uniqueID == id { GCKCastContext.sharedInstance().sessionManager.startSession(with: d); break }
            }
        case "castLoad":
            guard a.count >= 5, let s = a[0] as? String, let url = URL(string: s) else { return }
            let title = a[1] as? String ?? ""
            let img = a[2] as? String ?? ""
            let live = (a[3] as? Bool) ?? ((a[3] as? NSNumber)?.boolValue ?? false)
            let start = (a[4] as? NSNumber)?.doubleValue ?? 0
            let md = GCKMediaMetadata(metadataType: .movie)
            md.setString(title, forKey: kGCKMetadataKeyTitle)
            if let iu = URL(string: img), img.hasPrefix("http") { md.addImage(GCKImage(url: iu, width: 480, height: 720)) }
            let ib = GCKMediaInformationBuilder(contentURL: url)
            ib.streamType = live ? .live : .buffered
            ib.contentType = CastBridge.type(of: url)
            ib.metadata = md
            let rb = GCKMediaLoadRequestDataBuilder()
            rb.mediaInformation = ib.build()
            rb.autoplay = NSNumber(value: true)
            rb.startTime = live ? 0 : start
            pending = rb.build()
            sendPending()
        case "castStop":
            pending = nil
            if player?.externalActive == true { player?.showAirPlayPicker() }
            else { GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true) }
        default:
            break
        }
        push()
    }

    private static func type(of url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m3u8": return "application/x-mpegURL"
        case "mkv": return "video/x-matroska"
        case "webm": return "video/webm"
        case "ts": return "video/mp2t"
        default: return "video/mp4"
        }
    }

    private func sendPending() {
        guard let req = pending,
              let s = GCKCastContext.sharedInstance().sessionManager.currentCastSession,
              s.connectionState == .connected,
              let client = s.remoteMediaClient else { return }
        client.loadMedia(with: req)
        pending = nil
    }

    // MARK: - Meldingen van Google Cast

    func didUpdateDeviceList() { push() }

    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) { sendPending(); push() }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) { sendPending(); push() }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) { pending = nil; push() }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) { pending = nil; push() }

    // MARK: - Naar JavaScript

    func push() {
        guard let web = web else { return }
        var devs: [[String: Any]] = []
        let dm = GCKCastContext.sharedInstance().discoveryManager
        for i in 0..<dm.deviceCount {
            let d = dm.device(at: i)
            devs.append(["id": d.uniqueID, "name": d.friendlyName ?? "Chromecast", "kind": "chromecast"])
        }
        devs.append(["id": "airplay", "name": "AirPlay", "kind": "airplay"])
        var cur: Any = NSNull()
        if let s = GCKCastContext.sharedInstance().sessionManager.currentCastSession,
           s.connectionState == .connected || s.connectionState == .connecting {
            cur = ["id": s.device.uniqueID, "name": s.device.friendlyName ?? "Chromecast", "kind": "chromecast"]
        } else if let name = player?.airPlayName {
            cur = ["id": "airplay", "name": name, "kind": "airplay"]
        }
        let st: [String: Any] = ["devices": devs, "current": cur]
        guard let data = try? JSONSerialization.data(withJSONObject: st),
              let json = String(data: data, encoding: .utf8) else { return }
        web.evaluateJavaScript("window.__ibsCast && window.__ibsCast(\(json))", completionHandler: nil)
    }
}
