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
        // Kotlin top-level function Main.kt#MainViewController exports as a
        // static method on the file class "MainKt" (ZcodeShared prefix is
        // hidden by the swift_name attribute).
        let root = MainKt.MainViewController()
        window?.rootViewController = root
        window?.makeKeyAndVisible()
        return true
    }
}
