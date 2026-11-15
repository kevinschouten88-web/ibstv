import Foundation
import WebKit

/// De ingebouwde "server" van de app op ibstv://localhost/.
/// Levert de speler (www-map) en haalt via /proxy gegevens van de IPTV-server op,
/// net als de Windows- en Android-versie.
final class SchemeHandler: NSObject, WKURLSchemeHandler, URLSessionDataDelegate {
    static let scheme = "ibstv"
    static let userAgent = "VLC/3.0.20 LibVLC/3.0.20"

    private var session: URLSession!
    private var byData: [Int: WKURLSchemeTask] = [:]                     // dataTask -> verzoek van de pagina
    private var byScheme: [ObjectIdentifier: URLSessionDataTask] = [:]   // verzoek van de pagina -> dataTask
    private var answered = Set<Int>()

    override init() {
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 25
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.httpMaximumConnectionsPerHost = 8
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: OperationQueue.main)
    }

    // MARK: - WKURLSchemeHandler

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        var path = url.path
        if path.isEmpty || path == "/" { path = "/index.html" }
        switch path {
        case "/proxy":
            proxy(task, url)
        case "/status":
            respond(task, 200, "application/json", Data(#"{"ffmpeg":false,"conversions":0,"max":0}"#.utf8))
        case "/convert", "/probe":
            respond(task, 501, "text/plain", Data("Niet beschikbaar op de iPhone".utf8))
        default:
            asset(task, path)
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        let key = ObjectIdentifier(task)
        if let dt = byScheme.removeValue(forKey: key) {
            byData.removeValue(forKey: dt.taskIdentifier)
            answered.remove(dt.taskIdentifier)
            dt.cancel()
        }
    }

    // MARK: - Bestanden van de speler

    private func asset(_ task: WKURLSchemeTask, _ path: String) {
        guard let base = Bundle.main.resourceURL?.appendingPathComponent("www", isDirectory: true) else {
            respond(task, 404, "text/plain", Data("Niet gevonden".utf8)); return
        }
        let rel = path.split(separator: "/").filter { $0 != ".." && $0 != "." }.joined(separator: "/")
        let file = base.appendingPathComponent(rel)
        guard let data = try? Data(contentsOf: file) else {
            respond(task, 404, "text/plain", Data("Niet gevonden".utf8)); return
        }
        respond(task, 200, mime(for: rel), data)
    }

    private func mime(for name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "js": return "application/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json"
        case "woff2": return "font/woff2"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "svg": return "image/svg+xml"
        default: return "application/octet-stream"
        }
    }

    private func respond(_ task: WKURLSchemeTask, _ code: Int, _ type: String, _ data: Data) {
        guard let url = task.request.url,
              let resp = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1",
                                         headerFields: ["Content-Type": type,
                                                        "Content-Length": String(data.count),
                                                        "Cache-Control": "no-store",
                                                        "Access-Control-Allow-Origin": "*"]) else { return }
        task.didReceive(resp)
        task.didReceive(data)
        task.didFinish()
    }

    // MARK: - Verbinding met de IPTV-server

    private func proxy(_ task: WKURLSchemeTask, _ url: URL) {
        let target = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "url" })?.value ?? ""
        guard let dest = URL(string: target), let sch = dest.scheme?.lowercased(), sch == "http" || sch == "https" else {
            respond(task, 400, "text/plain", Data("Ongeldig adres".utf8)); return
        }
        var req = URLRequest(url: dest)
        req.setValue(SchemeHandler.userAgent, forHTTPHeaderField: "User-Agent")
        if let range = task.request.value(forHTTPHeaderField: "Range") {
            req.setValue(range, forHTTPHeaderField: "Range")
        }
        let dt = session.dataTask(with: req)
        byData[dt.taskIdentifier] = task
        byScheme[ObjectIdentifier(task)] = dt
        dt.resume()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.allow)
        guard let task = byData[dataTask.taskIdentifier], let url = task.request.url else { return }
        let http = response as? HTTPURLResponse
        var headers: [String: String] = ["Cache-Control": "no-store", "Access-Control-Allow-Origin": "*"]
        headers["Content-Type"] = (http?.value(forHTTPHeaderField: "Content-Type")) ?? response.mimeType ?? "application/octet-stream"
        for name in ["Content-Length", "Content-Range", "Accept-Ranges"] {
            if let v = http?.value(forHTTPHeaderField: name) { headers[name] = v }
        }
        // URLSession pakt gzip zelf uit; dan klopt de oorspronkelijke lengte niet meer.
        if let enc = http?.value(forHTTPHeaderField: "Content-Encoding"), !enc.isEmpty, enc != "identity" {
            headers.removeValue(forKey: "Content-Length")
        }
        let code = http?.statusCode ?? 200
        if let resp = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers) {
            answered.insert(dataTask.taskIdentifier)
            task.didReceive(resp)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let task = byData[dataTask.taskIdentifier] else { return }
        task.didReceive(data)
    }

    func urlSession(_ session: URLSession, task dt: URLSessionTask, didCompleteWithError error: Error?) {
        let id = dt.taskIdentifier
        guard let task = byData.removeValue(forKey: id) else { return }
        byScheme.removeValue(forKey: ObjectIdentifier(task))
        let wasAnswered = answered.remove(id) != nil
        if error == nil {
            task.didFinish()
        } else if !wasAnswered {
            respond(task, 502, "text/plain", Data("IPTV-server niet bereikbaar".utf8))
        } else {
            task.didFailWithError(error!)
        }
    }

    // Veel IPTV-servers gebruiken een eigen of verlopen certificaat; net als de tv-app accepteren we dat.
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
