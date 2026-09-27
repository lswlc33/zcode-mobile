import UIKit
import ZcodeShared

@main
class AppDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        // Top-level Kotlin function MainViewController() from Main.kt is
        // exported to ObjC/Swift directly (no "*Kt" class in Kotlin/Native).
        let root = MainViewController()
        window?.rootViewController = root
        window?.makeKeyAndVisible()
        return true
    }
}
