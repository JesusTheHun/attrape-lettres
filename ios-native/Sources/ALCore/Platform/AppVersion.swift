// Replaces Vite's `__APP_VERSION__` / `CapApp.getInfo().version`.
//
// Two consumers: telemetry stamps it on every event, and `Updates`' native
// guard compares it against a content payload's `minNative` (D13). Neither may
// reach into `Bundle` — ALCore stays pure; `ALPlatform` reads
// `CFBundleShortVersionString`.

public protocol AppVersionProvider: Sendable {
    /// The marketing version, e.g. "0.1.0". Compared with `versionAtLeast`.
    var marketing: String { get }
}

/// Tests and previews.
public struct FixedAppVersion: AppVersionProvider {
    public let marketing: String
    public init(_ marketing: String) { self.marketing = marketing }
}
