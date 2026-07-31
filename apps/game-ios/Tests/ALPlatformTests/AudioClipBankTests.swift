import ALCore
import Foundation
import Testing

@testable import ALPlatform

// The Swift-side half of `node scripts/generate-vo.mjs --list=all`.
//
// This is the test that catches a new word shipping without a voice. A drift
// between the authored catalog and the baked bank does not crash, does not log
// and is nearly invisible in review: the `voKey` lookup simply misses and that
// ONE line comes out of the robot text-to-speech voice, in the middle of
// otherwise-recorded narration. Nobody notices until a child hears it.
//
// It runs against the committed MANIFEST, not the audio, so it is green (or red)
// on a machine that has never run `scripts/stage-vo.sh`.

@Suite("Clip bank — the catalog is fully voiced")
struct AudioClipBankTests {

    private var catalog: [String] {
        VO.enumerateUtterances() + VO.enumeratePreviewUtterances().map(\.text)
    }

    @Test("every utterance the app can speak has a baked clip")
    func manifestCoversTheWholeCatalog() {
        let manifest = VOManifest.shipped
        #expect(manifest.count > 0, Comment(rawValue: "vo-manifest.txt did not load from the bundle"))

        let missing = catalog.filter { !manifest.contains(voKey($0)) }
        #expect(
            missing.isEmpty,
            Comment(rawValue: "\(missing.count) unvoiced utterance(s), e.g. \(missing.prefix(5))")
        )
    }

    @Test("the catalog is the measured 845 lines: 723 phrases + 122 preview tokens")
    func catalogSize() {
        // Measured in the PWA working tree (spec/audio-feedback.md §1.3) and
        // matched by the 845 files on disk.
        #expect(VO.enumerateUtterances().count == 723)
        #expect(VO.enumeratePreviewUtterances().count == 122)
        let previewLetters = VO.enumeratePreviewUtterances().filter { $0.kind == .letter }
        let previewSyllables = VO.enumeratePreviewUtterances().filter { $0.kind == .syllable }
        #expect(previewLetters.count == 25)
        #expect(previewSyllables.count == 97)
    }

    @Test("845 distinct utterances hash to 845 distinct keys — no collisions")
    func noKeyCollisions() {
        let texts = Set(catalog)
        #expect(texts.count == 845)
        let keys = Set(texts.map(voKey))
        #expect(keys.count == texts.count, Comment(rawValue: "two utterances share one clip file"))
    }

    @Test("no orphans: the bank holds exactly the catalog, nothing stale")
    func noOrphanClips() {
        let manifest = VOManifest.shipped
        #expect(manifest.count == 845)
        let catalogKeys = Set(catalog.map(voKey))
        let orphans = manifest.keys.subtracting(catalogKeys)
        #expect(
            orphans.isEmpty,
            Comment(rawValue: "\(orphans.count) clip(s) no utterance can reach: \(orphans.sorted().prefix(5))")
        )
    }

    // MARK: - The loader

    @Test("m4a beats mp3 beats wav when a key has more than one file")
    func extensionPreference() {
        // `RANK = { m4a: 3, mp3: 2, wav: 1 }` in clips.ts, and the winner is
        // independent of the order the files are listed in.
        let manifest = VOManifest(lines: ["x.wav", "x.m4a", "x.mp3", "y.wav", "y.mp3", "z.wav", "junk", "z.aiff"])
        #expect(manifest.fileExtension(for: "x") == "m4a")
        #expect(manifest.fileExtension(for: "y") == "mp3")
        #expect(manifest.fileExtension(for: "z") == "wav")
        #expect(manifest.fileExtension(for: "junk") == nil)
        #expect(manifest.count == 3)
    }

    @Test("a key resolves to the file the manifest names, preferring m4a over wav")
    func resolvesToTheRankedFileOnDisk() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("al-clipbank-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let key = voKey("Bravo ! Tu as tout réussi !")
        try Data([0x00]).write(to: dir.appendingPathComponent("\(key).m4a"))
        try Data([0x00]).write(to: dir.appendingPathComponent("\(key).wav"))

        let bundle = try #require(Bundle(url: dir))
        let bank = BundleClipBank(manifest: VOManifest(lines: ["\(key).m4a", "\(key).wav"]), bundle: bundle)

        let url = try #require(bank.clipURL(for: "Bravo ! Tu as tout réussi !"))
        #expect(url.pathExtension == "m4a")
        #expect(url.deletingPathExtension().lastPathComponent == key)
        #expect(bank.hasBakedVoice)
    }

    @Test("an unbaked utterance resolves to nil — that is the TTS-fallback contract")
    func unbakedResolvesToNil() {
        let bank = BundleClipBank()
        // "1234" is not in the catalog and never will be: the balance read-out
        // is the one line in the app with no clip (App.tsx:44).
        #expect(bank.clipURL(for: "1234") == nil)
        // Memoised negatives must stay negative.
        #expect(bank.clipURL(for: "1234") == nil)
    }

    @Test("an unstaged build answers nil cleanly instead of trapping")
    func unstagedBankDegrades() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("al-clipbank-empty-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let bundle = try #require(Bundle(url: dir))
        // The manifest is committed; the audio is not staged. That is exactly
        // what a fresh clone looks like.
        let bank = BundleClipBank(manifest: .shipped, bundle: bundle)
        #expect(bank.manifestCount == 845)
        #expect(bank.hasBakedVoice == false)
        #expect(bank.clipURL(for: "Bravo ! Tu as tout réussi !") == nil)
    }

    @Test("when the audio IS staged, every catalog utterance resolves to a real file")
    func stagedBankResolvesEverything() {
        let bank = BundleClipBank()
        // Skipped on a checkout that has not run `scripts/stage-vo.sh`; the
        // manifest test above is the one that must always run.
        guard bank.hasBakedVoice else { return }
        let unresolved = catalog.filter { bank.clipURL(for: $0) == nil }
        #expect(
            unresolved.isEmpty,
            Comment(rawValue: "\(unresolved.count) staged clip(s) missing, e.g. \(unresolved.prefix(5))")
        )
    }
}
