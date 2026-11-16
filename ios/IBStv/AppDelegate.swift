import UIKit
import AVFoundation
import GoogleCast

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Geluid ook als de stille modus aan staat.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        try? AVAudioSession.sharedInstance().setActive(true)

        // Chromecast
        CastBridge.setUp()

        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = UIColor(red: 8 / 255, green: 11 / 255, blue: 24 / 255, alpha: 1)
        w.rootViewController = WebViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }
}
