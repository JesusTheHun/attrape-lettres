import Foundation

// The committed index of the baked voice bank.
//
// `src/vo/clips.ts` builds its map at BUILD time from a Vite glob. There is no
// glob in SwiftPM, and — more importantly — the 845 clips are not committed
// under `ios-native/`: they live once, in the PWA tree, and are staged into
// `Sources/ALPlatform/Resources/vo/` by `scripts/stage-vo.sh` before a build.
//
// So the *manifest* is committed instead: one `<voKey>.<ext>` per line, exactly
// the filenames the bake produced. That is what makes the test that actually
// matters — "every utterance the app can speak has a clip" — run on a machine
// that has never staged a byte of audio. A new word shipping without a voice is
// then a red test, not a robot voice a child hears three weeks later.
//
// The manifest is data about the bank; the bank is the bank. `BundleClipBank`
// consults the manifest for the extension and the *bundle* for the file, so an
// unstaged build resolves cleanly to `nil` and degrades to TTS (invariant 3).

public struct VOManifest: Sendable {

    /// `RANK` from `clips.ts`: prefer the compressed formats when a key has more
    /// than one file on disk. (In practice the bank is 100 % `.m4a`; the mp3/wav
    /// arms are as dead here as they are in the TS, and kept for the same reason.)
    static let rank: [String: Int] = ["m4a": 3, "mp3": 2, "wav": 1]

    /// voKey → the winning extension.
    public let extensions: [String: String]

    public init(extensions: [String: String]) {
        self.extensions = extensions
    }

    /// Parse `<key>.<ext>` lines. Blank lines and `#` comments are ignored;
    /// anything without a known extension is ignored (mirrors the TS regex).
    public init(lines: [String]) {
        var best: [String: Int] = [:]
        var chosen: [String: String] = [:]
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            guard let dot = line.lastIndex(of: ".") else { continue }
            let key = String(line[line.startIndex..<dot])
            let ext = String(line[line.index(after: dot)...])
            guard !key.isEmpty, let rank = VOManifest.rank[ext] else { continue }
            if rank > (best[key] ?? 0) {
                best[key] = rank
                chosen[key] = ext
            }
        }
        self.extensions = chosen
    }

    public var count: Int { extensions.count }
    public var keys: Set<String> { Set(extensions.keys) }

    public func contains(_ key: String) -> Bool { extensions[key] != nil }

    /// The extension the bake wrote for this key, or `nil` if it was never baked.
    public func fileExtension(for key: String) -> String? { extensions[key] }

    /// The manifest that ships in `ALPlatform`'s resource bundle. Empty only if
    /// the resource itself is missing, which is a packaging bug, not a runtime
    /// condition — but it still degrades to "no baked voice" rather than trapping.
    public static let shipped: VOManifest = {
        guard let url = Bundle.module.url(forResource: "vo-manifest", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return VOManifest(extensions: [:]) }
        return VOManifest(lines: text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init))
    }()
}
