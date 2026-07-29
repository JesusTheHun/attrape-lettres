import ALCore
import Foundation

/**
 * `AppVersionProvider` from the bundle's marketing version — the replacement for
 * Vite's `__APP_VERSION__` and `CapApp.getInfo().version`.
 *
 * Two consumers: telemetry stamps it on every event (`{ v: … }`), and the
 * native-version guard compares it against a remote content payload's
 * `minNative` (D13). Neither may reach into `Bundle` itself — ALCore stays pure,
 * so the `CFBundleShortVersionString` read happens exactly here.
 *
 * `MARKETING_VERSION` in the Xcode target is the single source; `package.json`'s
 * `version: "0.1.0"` is what it starts from.
 */
public struct SystemAppVersion: AppVersionProvider {
    /// What an absent or wrongly-typed `CFBundleShortVersionString` reads as.
    /// Matches the inert `Telemetry.shared` default, and `versionAtLeast`
    /// compares it as the lowest possible version — so a missing key can only
    /// ever gate MORE conservatively, never claim a version we are not.
    public static let fallback = "0.0.0"

    public let marketing: String

    public init(bundle: Bundle = .main, fallback: String = SystemAppVersion.fallback) {
        self.init(
            infoValue: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString"),
            fallback: fallback)
    }

    /// The seam: `Bundle`'s Info.plist cannot be forged in a host test, so the
    /// lookup's RESULT is what the tests drive.
    init(infoValue: Any?, fallback: String = SystemAppVersion.fallback) {
        guard let string = infoValue as? String, !string.isEmpty else {
            marketing = fallback
            return
        }
        marketing = string
    }
}
