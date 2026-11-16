import UIKit
import WebKit

/// Toont de IBStv-speler schermvullend in een WebView.
/// Daaronder ligt het beeld van de ingebouwde speler (VLC).
final class WebViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private var web: WKWebView!
    private let videoView = UIView()
    private var bridge: PlayerBridge!
    private var cast: CastBridge?
    private let handler = SchemeHandler()
    private var immersive = false

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    /// Vervangt de Android-koppeling: dezelfde functies, maar via berichten naar Swift.
    private static let shim = """
    (function(){
      var sid = 0;
      var idle = function(){ return {state:'idle',t:0,d:0,w:0,h:0,tracks:[],buffering:false,audioSel:-1,sub:'',error:''}; };
      var st = idle();
      function post(cmd, a){ window.webkit.messageHandlers.ibs.postMessage({cmd: cmd, a: a || [], sid: sid}); }
      window.__ibsPush = function(s){ if(s && s.sid === sid) st = s; };
      window.IBSPlayer = {
        play: function(u, at, ai){ sid++; st = idle(); st.state = 'preparing'; st.t = at || 0; st.buffering = true; post('play', [String(u), at || 0, ai == null ? -1 : ai]); },
        pause: function(){ post('pause'); },
        resume: function(){ post('resume'); },
        seek: function(t){ post('seek', [t]); },
        selectTrack: function(i){ post('selectTrack', [i]); },
        deselectTrack: function(i){ post('deselectTrack', [i]); },
        stop: function(){ sid++; st = idle(); post('stop'); },
        layout: function(x, y, w, h, vw){ post('layout', [x, y, w, h, vw]); },
        state: function(){ return JSON.stringify(st); }
      };
      window.IBSApp = { immersive: function(on){ post('immersive', [!!on]); }, setPlaying: function(){} };
      // Casten: Chromecast en AirPlay. De iPhone-app stuurt de lijst via __ibsCast.
      var cast = {devices: [], current: null};
      window.__ibsCast = function(s){ if(s) cast = s; };
      window.IBSCast = {
        devices: function(){ return JSON.stringify(cast.devices || []); },
        current: function(){ return cast.current ? JSON.stringify(cast.current) : ''; },
        connect: function(id){ post('castConnect', [String(id)]); },
        cast: function(u, title, img, live, at){ post('castLoad', [String(u), String(title || ''), String(img || ''), !!live, at || 0]); },
        stop: function(){ post('castStop'); }
      };
    })();
    """

    override func loadView() {
        let v = UIView()
        v.backgroundColor = UIColor(red: 8 / 255, green: 11 / 255, blue: 24 / 255, alpha: 1)
        view = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        videoView.backgroundColor = .black
        videoView.isHidden = true
        videoView.isUserInteractionEnabled = false
        view.addSubview(videoView)

        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(handler, forURLScheme: SchemeHandler.scheme)
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.applicationNameForUserAgent = "Mobile/15E148 IBStviOS/" + WebViewController.version
        cfg.websiteDataStore = .default()
        let uc = cfg.userContentController
        uc.addUserScript(WKUserScript(source: WebViewController.shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        uc.add(self, name: "ibs")

        web = WKWebView(frame: view.bounds, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        web.allowsBackForwardNavigationGestures = false
        web.navigationDelegate = self
        web.uiDelegate = self
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(web)

        bridge = PlayerBridge(videoView: videoView)
        bridge.web = web
        bridge.onLayout = { [weak self] in self?.view.setNeedsLayout() }
        view.insertSubview(bridge.routePicker, belowSubview: web)

        let c = CastBridge()
        c.web = web
        c.player = bridge
        cast = c

        NotificationCenter.default.addObserver(self, selector: #selector(willResign),
                                               name: UIApplication.willResignActiveNotification, object: nil)

        web.load(URLRequest(url: URL(string: "\(SchemeHandler.scheme)://localhost/index.html")!))
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        web.frame = view.bounds
        videoView.frame = bridge.videoFrame(in: view.bounds)
    }

    @objc private func willResign() {
        bridge.appWillResign()
        web.evaluateJavaScript("try{video.pause()}catch(e){}", completionHandler: nil)
    }

    // MARK: - Schermvullend

    override var prefersStatusBarHidden: Bool { immersive }
    override var prefersHomeIndicatorAutoHidden: Bool { immersive }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
    // Bladeren staand, kijken liggend
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { immersive ? .landscape : .portrait }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { immersive ? .landscapeRight : .portrait }

    private func applyOrientation() {
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            if let scene = view.window?.windowScene {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: immersive ? .landscape : .portrait)) { _ in }
            }
        } else {
            let o: UIInterfaceOrientation = immersive ? .landscapeRight : .portrait
            UIDevice.current.setValue(o.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    // MARK: - Berichten uit JavaScript

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let cmd = body["cmd"] as? String else { return }
        let a = body["a"] as? [Any] ?? []
        let sid = (body["sid"] as? NSNumber)?.intValue ?? 0
        if cmd == "immersive" {
            immersive = (a.first as? Bool) ?? ((a.first as? NSNumber)?.boolValue ?? false)
            UIView.animate(withDuration: 0.25) {
                self.setNeedsStatusBarAppearanceUpdate()
                self.setNeedsUpdateOfHomeIndicatorAutoHidden()
            }
            applyOrientation()
            return
        }
        if cmd.hasPrefix("cast") { cast?.handle(cmd, a); return }
        bridge.handle(cmd, a, sid: sid)
        if cmd == "layout" { view.setNeedsLayout(); view.layoutIfNeeded() }
    }

    // MARK: - Navigatie: alles buiten de app in Safari openen

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.scheme == SchemeHandler.scheme || url.scheme == "about" || url.scheme == "blob" || url.scheme == "data" {
            decisionHandler(.allow); return
        }
        if action.targetFrame?.isMainFrame ?? true, url.scheme == "http" || url.scheme == "https" {
            UIApplication.shared.open(url)
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url, url.scheme == "http" || url.scheme == "https" { UIApplication.shared.open(url) }
        return nil
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }
}
