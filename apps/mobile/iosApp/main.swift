import UIKit
import Security
import VaultMobile

final class KeychainStore: NSObject, SecureStore {
    private let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "dev.swarch.vault.session",
        kSecAttrAccount as String: "current-session"
    ]
    func read() -> String? {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    func write(value: String?) {
        guard let value else { SecItemDelete(query as CFDictionary); return }
        let values: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            let inserted = SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil)
            precondition(inserted == errSecSuccess, "No se pudo guardar la sesión en Keychain.")
        } else { precondition(status == errSecSuccess, "No se pudo actualizar la sesión en Keychain.") }
    }
}

final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Vault", sessionRole: session.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        let origin = Bundle.main.object(forInfoDictionaryKey: "VaultApiOrigin") as? String ?? "https://localhost:3445"
        window.rootViewController = IosPlatformKt.MainViewController(store: KeychainStore(), origin: origin)
        window.makeKeyAndVisible()
        self.window = window
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let index = arguments.firstIndex(of: "--content-link"), arguments.indices.contains(index + 1) {
            DestinationLinks.shared.open(url: arguments[index + 1])
        }
        #endif
        for context in options.urlContexts { DestinationLinks.shared.open(url: context.url.absoluteString) }
    }
    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        for context in contexts { DestinationLinks.shared.open(url: context.url.absoluteString) }
    }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
