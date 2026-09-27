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
        let root = MainViewControllerKt.MainViewController()
        window?.rootViewController = root
        window?.makeKeyAndVisible()
        return true
    }
}
