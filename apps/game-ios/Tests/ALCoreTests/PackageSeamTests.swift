import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* Integration seams — the joints between work packages that no single agent    */
/* owned, and that therefore nothing else asserts.                              */
/*                                                                             */
/* Each package's own suite proves its half. These prove the halves meet:       */
/* `Rewards.ledgerKey` really is the string that reaches disk, `sessionReward`  */
/* really is the only thing that moves `stars.earned`, and ALCore really is     */
/* free of the frameworks that would make `swift test` need a simulator.        */
/*                                                                             */
/* The import scan overlaps deliberately with `MoneySourceScanTests`'s          */
/* StoreKit/UIKit ban; that one is about D4 (the money seam), this one about    */
/* D1 (host-testability) and it bans the modules D4 had no reason to name —     */
/* SwiftUI above all, which is what someone reaching for `@Published` or        */
/* `Color` imports without thinking.                                            */
/* -------------------------------------------------------------------------- */

private func swiftFiles(under directory: URL) -> [URL] {
    guard
        let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
    else { return [] }
    return walker.compactMap { $0 as? URL }
        .filter { $0.pathExtension == "swift" }
        .sorted { $0.path < $1.path }
}

private let alCoreRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALCoreTests/PackageSeamTests.swift
    .deletingLastPathComponent()  // …/Tests/ALCoreTests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/apps/game-ios
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALCore")

/// Import STATEMENTS only. Every file here is allowed — encouraged — to name a
/// banned module in prose: the comments explaining *why* ALCore has no StoreKit
/// are among the most valuable lines in the target.
private func importedModules(in source: String) -> [String] {
    source.split(separator: "\n", omittingEmptySubsequences: false).compactMap { line in
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("import ") else { return nil }
        // `import struct Foundation.Data` → take the last dotted root.
        let rest = String(trimmed.dropFirst("import ".count)).trimmingCharacters(in: .whitespaces)
        let words = rest.split(separator: " ")
        guard let last = words.last else { return nil }
        return String(last.split(separator: ".").first ?? last)
    }
}

@Suite("ALCore stays pure — the D1 seam")
struct ALCorePuritySeamTests {

    @Test("the scan can see the sources it is meant to scan")
    func rootResolves() {
        #expect(FileManager.default.fileExists(atPath: alCoreRoot.path))
        // Big enough that an empty-directory bug cannot masquerade as a pass.
        #expect(swiftFiles(under: alCoreRoot).count > 30)
    }

    /// D1 / ARCHITECTURE §1. `swift test` runs the whole of the app's logic on a
    /// Mac with no simulator, no store, no network and no signing. Exactly one
    /// import can take that away.
    @Test("ALCore imports no UI, store or networking framework")
    func alCoreImportsNothingPlatformBound() throws {
        let banned: Set<String> = [
            "SwiftUI", "UIKit", "AppKit", "WebKit", "StoreKit",
            "Network", "Combine", "AVFoundation", "CoreHaptics", "CoreGraphics",
        ]
        for file in swiftFiles(under: alCoreRoot) {
            for module in importedModules(in: try String(contentsOf: file, encoding: .utf8)) {
                #expect(
                    !banned.contains(module),
                    "\(file.lastPathComponent) imports \(module) — that belongs in ALPlatform or ALUI")
            }
        }
    }

    /// The protocols are the network. A concrete client in ALCore would mean the
    /// merge, the telemetry queue and the entitlement refresh could no longer be
    /// driven from a test — and invariant 11 is a claim about what happens when
    /// the network is *unreachable*, which only a fake can produce on demand.
    @Test("ALCore makes no network call")
    func alCoreHasNoConcreteTransport() throws {
        for file in swiftFiles(under: alCoreRoot) {
            let source = try String(contentsOf: file, encoding: .utf8)
            // Comments in Sync/, Telemetry/ and Updates/ specify the ALPlatform
            // transports by name, so match on use, not on mention.
            #expect(
                !source.contains("URLSession("),
                "\(file.lastPathComponent) constructs a URLSession")
            #expect(
                !source.contains("URLSession.shared"),
                "\(file.lastPathComponent) uses the shared URLSession")
            #expect(
                !source.contains("URLRequest("),
                "\(file.lastPathComponent) builds a URLRequest")
        }
    }
}

@MainActor
@Suite("Rewards ↔ Persistence — the ledger-key seam (D11)")
struct LedgerKeySeamTests {

    private func makeStore(kv: KVStore) -> ProfileStore {
        ProfileStore(kv: kv, device: { "this-phone" }, now: { 1_700_000_000_000 })
    }

    /// D11 end to end. `RewardsTests` proves the format and `ProfileStoreTests`
    /// proves the write; neither proves they are the SAME string. This does:
    /// award once, then find the literal key in the bytes that reach the KV
    /// store. Renaming an `ExerciseId` raw value orphans a real child's history,
    /// and this is the test that turns that into a red build.
    @Test func theKeyAwardWritesIsTheKeyRewardsBuildsAndItReachesDiskVerbatim() throws {
        let kv = InMemoryKVStore()
        let store = makeStore(kv: kv)
        store.createChild(name: "Léa")
        store.award(exercise: .readImage, level: 3, perfectRounds: 0, totalRounds: 8)

        let key = Rewards.ledgerKey(exercise: .readImage, level: 3)
        #expect(key == "read-image:3")

        // In memory: the folded ledger the UI reads is keyed by it…
        #expect(store.profile.ledger[key] == 1)
        // …and so is the per-device counter underneath it (invariant 9).
        let active = try #require(store.children.first { $0.id == store.activeId })
        #expect(active.profile.clears[key]?["this-phone"] == 1)

        // On disk: the literal string, in the persisted roster blob.
        let blob = try #require(kv.string(ProfileStorage.rosterKey))
        #expect(blob.contains("\"\(key)\""))
    }

    /// Every exercise, not just the one above — the raw values are the contract,
    /// so the sweep is over `allCases`.
    @Test func everyExerciseFilesItsClearsUnderItsFrozenRawValue() throws {
        for id in ExerciseId.allCases {
            let kv = InMemoryKVStore()
            let store = makeStore(kv: kv)
            store.createChild(name: "Léa")
            store.award(exercise: id, level: 1, perfectRounds: 0, totalRounds: 4)

            let key = "\(id.rawValue):1"
            #expect(key == Rewards.ledgerKey(exercise: id, level: 1))
            let active = try #require(store.children.first { $0.id == store.activeId })
            #expect(active.profile.clears[key] != nil, "\(id.rawValue) filed elsewhere")

            let blob = try #require(kv.string(ProfileStorage.rosterKey))
            #expect(blob.contains("\"\(key)\""), "\(id.rawValue) did not reach disk under its key")
        }
    }

    /// The same key must survive the sync wire, or a second device files the
    /// child's history under a different name and the counters never merge.
    @Test func theLedgerKeySurvivesTheWireRoundTrip() throws {
        let kv = InMemoryKVStore()
        let store = makeStore(kv: kv)
        store.createChild(name: "Léa")
        store.award(exercise: .spellTwoSyllablesMixed, level: 2, perfectRounds: 4, totalRounds: 4)

        let key = Rewards.ledgerKey(exercise: .spellTwoSyllablesMixed, level: 2)
        let wire = toWire(store.roster)
        let bytes = try JSONEncoder().encode(wire)
        let json = try #require(String(data: bytes, encoding: .utf8))
        #expect(json.contains("\"\(key)\""))
        #expect(wire.children.first?.profile.clears[key]?["this-phone"] == 1)
    }
}

@Suite("invariant 8 — sessionReward is the only earner")
struct SoleEarnerSeamTests {

    /// Structural, not behavioural: ONE line in the whole of ALCore may move
    /// `stars.earned`, and its right-hand side must come from `sessionReward`.
    /// A second earner is how "farming never pays" quietly stops being true, and
    /// it would not fail any existing test — every one of them asserts on a path
    /// that already goes through `award`.
    @Test func exactlyOneAssignmentInALCoreMovesStarsEarned() throws {
        var writes: [(file: String, line: String)] = []
        for file in swiftFiles(under: alCoreRoot) {
            let source = try String(contentsOf: file, encoding: .utf8)
            for raw in source.split(separator: "\n", omittingEmptySubsequences: false) {
                let line = raw.trimmingCharacters(in: .whitespaces)
                guard !line.hasPrefix("//"), !line.hasPrefix("*"), !line.hasPrefix("/*") else {
                    continue
                }
                // A mutation of the earned half of the PN-counter, as opposed to
                // the memberwise `self.earned = earned` in the model initialisers.
                if line.contains(".stars.earned") && line.contains("=") {
                    writes.append((file.lastPathComponent, line))
                }
            }
        }
        #expect(writes.count == 1, "earners: \(writes)")
        let only = try #require(writes.first)
        #expect(only.file == "ProfileStore.swift")
        #expect(only.line.contains("bump("))
    }

    @Test func thatAssignmentIsFedBySessionRewardAndNothingElse() throws {
        let source = try String(
            contentsOf: alCoreRoot.appendingPathComponent("Persistence/ProfileStore.swift"),
            encoding: .utf8)
        let award = try #require(source.range(of: "public func award("))
        let body = source[award.lowerBound...].prefix(1600)
        #expect(body.contains("Rewards.sessionReward("))
        // `spend` and `buy` debit; nothing else may credit.
        #expect(body.contains("p.stars.earned = bump(p.stars.earned, device: device, by: points)"))
    }

    /// The economy's floor: a training exercise pays the completion curve and
    /// nothing else. No combination of arguments — not the perfect run that
    /// maximises the bonus, not a negative clear count — can add a point to it.
    @Test func noArgumentsMakeATrainingExercisePayABonus() {
        for prior in [-1, 0, 1, 2, 3, 4, 99] {
            for rounds in [0, 1, 8, 40] {
                for perfect in 0...rounds {
                    #expect(
                        Rewards.sessionReward(
                            difficulty: .d0, priorClears: prior,
                            perfectRounds: perfect, totalRounds: rounds)
                            == Rewards.rewardFor(priorClears: prior))
                }
            }
        }
    }

    /// And the anti-farming gradient, at the seam rather than in the unit: for
    /// every exercise in the hub, a careful run out-earns a spammed one — or
    /// ties it at the bare curve, which is the training case.
    @Test func carefulPlayNeverEarnsLessThanSpamForAnyExerciseInTheHub() {
        for meta in Levels.exercises {
            let d = Levels.exerciseDifficulty(meta.id)
            #expect(d == meta.difficulty)
            let spam = Rewards.sessionReward(
                difficulty: d, priorClears: 0, perfectRounds: 0, totalRounds: 8)
            let careful = Rewards.sessionReward(
                difficulty: d, priorClears: 0, perfectRounds: 8, totalRounds: 8)
            #expect(careful >= spam, "\(meta.id.rawValue)")
            #expect(careful - spam == d.weight, "\(meta.id.rawValue)")
        }
    }
}
