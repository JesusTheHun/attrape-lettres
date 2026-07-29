import ALCore
import Foundation

// Port of `src/vo/clips.ts` — the `voKey(text) → file URL` mapping.
//
// The TS glob is eager (Vite inlines every clip URL at build time). Here the
// lookup is lazy and memoised: `Bundle.url(forResource:)` is a cached directory
// probe costing microseconds, a round needs ~15 distinct keys, and an eager
// index over 845 entries would cost 1–3 ms at launch for nothing. Negative
// results are memoised too, so a line with no clip does not re-probe the
// filesystem on every repeat.

/// What the voice channel needs from the bank. A protocol so a test can hand it
/// a spy and prove `pop()`/`nudge()` never reach it (invariant 1).
public protocol ClipLocating: AnyObject {
    /// `clipUrl(text)` — the baked clip for this EXACT utterance, or `nil`.
    func clipURL(for text: String) -> URL?
    /// `hasBakedVoice` — whether any clip is actually present in this build.
    var hasBakedVoice: Bool { get }
}

public final class BundleClipBank: ClipLocating, @unchecked Sendable {

    private let manifest: VOManifest
    private let bundle: Bundle
    /// Where `.process(...)` put the clips. SwiftPM FLATTENS a processed
    /// resource directory, so the staged `Resources/vo/*.m4a` land at the bundle
    /// root — but `.copy` (or an app target that keeps the folder) would not, so
    /// both are probed. Cheap: it is one extra cached lookup on a miss.
    private let subdirectories: [String?] = [nil, "vo"]

    private let lock = NSLock()
    /// voKey → URL, `nil` meaning "probed, absent". Distinguishing "not yet
    /// probed" from "probed and absent" is why the value is doubly optional.
    private var memo: [String: URL?] = [:]

    /// `bundle: nil` means `Bundle.module` — the ALPlatform resource bundle,
    /// which cannot be spelled in a default argument because SwiftPM synthesises
    /// it `internal`.
    public init(manifest: VOManifest = .shipped, bundle: Bundle? = nil) {
        self.manifest = manifest
        self.bundle = bundle ?? .module
    }

    /// The number of clips the bake produced, per the committed manifest. Not
    /// the number actually staged — see `hasBakedVoice` for that.
    public var manifestCount: Int { manifest.count }

    public func clipURL(for text: String) -> URL? {
        url(forKey: voKey(text))
    }

    /// Resolve a key directly. The bank is keyed by `voKey`, and the tests hash
    /// the catalog themselves, so this is the honest entry point for them.
    public func url(forKey key: String) -> URL? {
        lock.lock()
        if let cached = memo[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let resolved = probe(key)

        lock.lock()
        memo[key] = resolved
        lock.unlock()
        return resolved
    }

    private func probe(_ key: String) -> URL? {
        // The manifest names the winning extension; without it, fall back to the
        // TS's RANK order so a hand-dropped clip still resolves.
        let candidates: [String]
        if let ext = manifest.fileExtension(for: key) {
            candidates = [ext]
        } else {
            candidates = ["m4a", "mp3", "wav"]
        }
        for ext in candidates {
            for sub in subdirectories {
                if let url = bundle.url(forResource: key, withExtension: ext, subdirectory: sub) {
                    return url
                }
            }
        }
        return nil
    }

    /**
     * `hasBakedVoice` — true when the audio has actually been staged into this
     * build. Probes ONE manifest entry, once; an unstaged build answers `false`
     * and every `say()` takes the `AVSpeechSynthesizer` path. That is the whole
     * degradation story: no clips is quieter, never broken (invariant 3).
     */
    public private(set) lazy var hasBakedVoice: Bool = {
        guard let first = manifest.extensions.keys.sorted().first else { return false }
        return url(forKey: first) != nil
    }()
}

/// Test/preview double: nothing is ever baked, so every line takes the TTS path.
public final class EmptyClipBank: ClipLocating {
    public init() {}
    public func clipURL(for text: String) -> URL? { nil }
    public var hasBakedVoice: Bool { false }
}
