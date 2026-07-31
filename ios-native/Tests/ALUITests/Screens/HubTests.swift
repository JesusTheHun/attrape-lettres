import Foundation
import SwiftUI
import Testing

import ALArt

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The hub of `src/App.tsx` (lines 156-263), value by value.                    */
/*                                                                             */
/* Every expected value is transcribed from the TypeScript — App.tsx (the       */
/* level grid, the aria-labels, the jackpot rule, the listen tap), levels.ts    */
/* (the hint strings, byte for byte, U+2019/U+2026/U+2014 included) and         */
/* rewards.ts (REWARD_CURVE = [10, 3, 2, 2]) — never read back out of the       */
/* Swift under test.                                                           */
/* -------------------------------------------------------------------------- */

private let t0: Int64 = 1_700_000_000_000

/// A store with one chosen child — the state every hub render sits on.
@MainActor
private func makeStore(kv: InMemoryKVStore = InMemoryKVStore()) -> ProfileStore {
    let store = ProfileStore(kv: kv, device: { "hub-test-device" }, now: { t0 })
    store.createChild(name: "Léa")
    store.chooseSpecies(.unicorn)
    return store
}

// MARK: - Invariant 5

@Suite("Hub — invariant 5: every exercise, every level, unconditionally")
@MainActor
struct HubUnlockedTests {

    @Test("the level row is 1...levelCount for every catalog row, whatever preview says")
    func everyLevelOfEveryExercise() {
        // Driven from the catalog itself, so a new exercise is covered the day
        // it is added. The preview value must not be able to hide a level:
        // walk three different economies, including a hostile constant zero.
        let previews: [(ExerciseId, Int) -> Int] = [
            { _, _ in 0 },
            { _, _ in 10 },
            { _, level in level },
        ]
        for preview in previews {
            for row in Levels.exercises {
                let cells = hubLevelCells(for: row, preview: preview)
                #expect(
                    cells.map(\.level) == Array(1...row.levelCount),
                    Comment(rawValue: "\(row.id.rawValue) must expose all \(row.levelCount) levels"))
            }
        }
    }

    @Test("the catalog spans every ExerciseId, so “every exercise” means all 17")
    func catalogCoversEveryId() {
        #expect(Set(Levels.exercises.map(\.id)) == Set(ExerciseId.allCases))
        #expect(Levels.exercises.count == 17)
    }

    @Test("a fresh device exposes every level end to end through the real store")
    func freshDeviceThroughTheRealStore() {
        let store = makeStore()
        for row in Levels.exercises {
            let cells = hubLevelCells(for: row) { store.preview(exercise: $0, level: $1) }
            #expect(cells.map(\.level) == Array(1...row.levelCount))
        }
    }
}

// MARK: - The reward pills

@Suite("Hub — the reward pills (App.tsx:226-255, rewards.ts)")
@MainActor
struct HubRewardPillTests {

    @Test("a training row (difficulty 0) promises the same curve as any other")
    func trainingRowsPayTheCurve() throws {
        let store = makeStore()
        // levels.ts: first-letter and fill-blank are difficulty 0 — no accuracy
        // bonus, but the completion curve like every other row, so the pill
        // shows and the level announces « gagne 10 étoiles ».
        for id in [ExerciseId.firstLetter, .fillBlank] {
            let row = Levels.exercises.first { $0.id == id }!
            let cells = hubLevelCells(for: row) { store.preview(exercise: $0, level: $1) }
            for cell in cells {
                let reward = try #require(
                    cell.reward, Comment(rawValue: "\(id.rawValue) level \(cell.level) shows no pill"))
                #expect(reward.points == Rewards.curve[0])
                #expect(reward.jackpot)
            }
        }
    }

    @Test("first clear is the jackpot: a fresh paying level previews +10 ⭐")
    func freshLevelIsTheJackpot() throws {
        let store = makeStore()
        let row = Levels.exercises.first { $0.id == .findSound }!
        let cells = hubLevelCells(for: row) { store.preview(exercise: $0, level: $1) }
        let reward = try #require(cells[0].reward)
        #expect(reward.points == 10)  // REWARD_CURVE[0]
        #expect(reward.jackpot)
    }

    @Test("after one clear the pill decays to the +3 coin (REWARD_CURVE[1])")
    func clearedLevelDecaysToTheCoin() throws {
        let store = makeStore()
        store.award(exercise: .findSound, level: 1, perfectRounds: 5, totalRounds: 5)
        let row = Levels.exercises.first { $0.id == .findSound }!
        let cells = hubLevelCells(for: row) { store.preview(exercise: $0, level: $1) }
        let cleared = try #require(cells[0].reward)
        #expect(cleared.points == 3)
        #expect(!cleared.jackpot)
        // …and the NEXT level still shows its jackpot untouched.
        let untouched = try #require(cells[1].reward)
        #expect(untouched.points == 10)
        #expect(untouched.jackpot)
    }

    @Test("jackpot means exactly 10 points — 9 and 11 are coins (TSX: pts === 10)")
    func jackpotBoundary() {
        for (points, jackpot) in [(9, false), (10, true), (11, false), (1, false)] {
            let cells = hubLevelCells(for: Levels.exercises[1]) { _, _ in points }
            #expect(cells[0].reward == HubLevelReward(points: points, jackpot: jackpot))
        }
    }

    @Test("the level labels, byte for byte (App.tsx:235-239)")
    func levelLabels() {
        // `Niveau ${lvl}, gagne ${pts} ${pts > 1 ? "étoiles" : "étoile"}` /
        // `Niveau ${lvl}, pour s'entraîner` — ASCII apostrophe in the TSX.
        let row = Levels.exercises[1]  // find-sound, any paying row will do
        #expect(hubLevelCells(for: row) { _, _ in 10 }[2].label == "Niveau 3, gagne 10 étoiles")
        #expect(hubLevelCells(for: row) { _, _ in 1 }[2].label == "Niveau 3, gagne 1 étoile")
        #expect(hubLevelCells(for: row) { _, _ in 0 }[2].label == "Niveau 3, pour s'entraîner")
    }
}

// MARK: - The hint chips

@Suite("Hub — the hint chips (App.tsx:214-223, levels.ts)")
struct HubHintChipTests {

    /// levels.ts, hand-transcribed — U+2019 apostrophes, U+2026 ellipses and
    /// U+2014 em dashes are the source's own bytes.
    private static let tsxChips: [ExerciseId: [String]] = [
        .firstLetter: [],
        .findSound: ["Écoute le son, tape son écriture"],
        .hearSyllable: ["VA, VE, VI… trouve celle que tu entends"],
        .pickVowel: ["La consonne est écrite — pose la voyelle"],
        .fillBlank: ["Trouve la syllabe manquante"],
        .orderSyllables: ["Remets les syllabes dans l\u{2019}ordre"],
        .findIntruder: ["Range le mot… et évite l\u{2019}intrus !"],
        .spellSound: [],
        .spellSyllable: ["Range les lettres de la syllabe"],
        .spellSyllablePlus: ["Range les lettres… évite les intrus"],
        .spellTwoSyllables: ["Complète les deux syllabes"],
        .readImage: [],
        .matchCase: ["Associe majuscule et minuscule"],
        .matchScript: ["Associe le script et l\u{2019}attaché"],
        .soundTwins: ["Trouve toutes les écritures du son"],
        .spellSyllablePlusMixed: ["GRANDE, petite ou attachée — trouve la bonne"],
        .spellTwoSyllablesMixed: ["GRANDE, petite ou attachée — trouve la bonne"],
    ]

    @Test("every catalog row shows exactly the TSX's chips, byte for byte")
    func realRows() {
        for row in Levels.exercises {
            #expect(
                hubHintChips(for: row) == Self.tsxChips[row.id],
                Comment(rawValue: "wrong chips for \(row.id.rawValue)"))
        }
    }

    @Test("all four conditionals render, in the TSX's order (no row uses more than one today)")
    func syntheticAllFour() {
        let all = ExerciseMeta(
            id: .fillBlank, name: "x", emoji: "x", levelCount: 1, difficulty: .d0,
            hint: "libre", mode: .order, grid: nil, spell: .lettersExact, mixed: false,
            match: .case)
        #expect(
            hubHintChips(for: all) == [
                "Remets les syllabes dans l\u{2019}ordre",  // MODE_HINT
                "Range les lettres de la syllabe",  // SPELL_HINT
                "Associe majuscule et minuscule",  // MATCH_HINT
                "libre",  // ex.hint
            ])
    }

    @Test("mixed swaps the spell chip for MIXED_HINT and changes nothing else")
    func mixedSwapsTheSpellChip() {
        let mixed = ExerciseMeta(
            id: .spellSyllablePlusMixed, name: "x", emoji: "x", levelCount: 1,
            difficulty: .d4, spell: .lettersExtra, mixed: true)
        #expect(hubHintChips(for: mixed) == ["GRANDE, petite ou attachée — trouve la bonne"])
        let plain = ExerciseMeta(
            id: .spellSyllablePlus, name: "x", emoji: "x", levelCount: 1,
            difficulty: .d2, spell: .lettersExtra, mixed: false)
        #expect(hubHintChips(for: plain) == ["Range les lettres… évite les intrus"])
    }
}

// MARK: - Invariant 7

@Suite("Hub — invariant 7: a drawn icon per exercise, distinct tints, never the emoji")
struct HubIconTests {

    @Test("every ExerciseId has a non-empty drawn icon")
    func everyIconExists() {
        for id in ExerciseId.allCases {
            #expect(
                !exerciseIconSpec(id).nodes.isEmpty,
                Comment(rawValue: "\(id.rawValue) has an empty icon"))
        }
    }

    @Test("all 17 tints are distinct — a copy-pasted branch cannot hide")
    func tintsAreDistinct() {
        let tints = ExerciseId.allCases.map { exerciseIconSpec($0).tint }
        #expect(Set(tints).count == ExerciseId.allCases.count)
    }
}

// MARK: - The header

/// Records the exact calls the TSX makes on the audio api, in order.
private final class RecordingAudio: AudioEngine {
    enum Event: Equatable {
        case unlock
        case say(String, Double, Double)
    }
    private(set) var events: [Event] = []

    func unlock() { events.append(.unlock) }
    func pop() {}
    func success() {}
    func nudge() {}
    func oops() {}
    @discardableResult
    func say(_ text: String, rate: Double, pitch: Double) async -> Bool {
        events.append(.say(text, rate, pitch))
        return true
    }
    func stop() {}
}

@Suite("Hub — the header (App.tsx:161-194)")
@MainActor
struct HubHeaderTests {

    @Test("the player chip shows the active child's name, and “” when nobody matches")
    func playerName() {
        let store = makeStore()
        #expect(hubPlayerName(children: store.children, activeId: store.activeId) == "Léa")
        // `children.find(...)?.name ?? ""` — both miss shapes.
        #expect(hubPlayerName(children: store.children, activeId: "nobody") == "")
        #expect(hubPlayerName(children: store.children, activeId: nil) == "")
        #expect(hubPlayerName(children: [], activeId: "x") == "")
    }

    @Test("🔊 unlocks FIRST, then says the bare digits at rate 0.85, pitch default (App.tsx:42-45)")
    func listenBalance() async {
        let audio = RecordingAudio()
        await hubListenBalance(audio: audio, balance: 42).value
        // The bare digit string — fr-FR TTS reads "42" as « quarante-deux ».
        // Rate 0.85 is the app's ONE override of the 0.94 default; pitch stays 1.1.
        #expect(audio.events == [.unlock, .say("42", 0.85, 1.1)])
    }

    @Test("the trial pill wording is entitlement.ts's, byte for byte")
    func trialNoticeBytes() {
        #expect(
            trialNotice(.trial(daysLeft: 11, endsAt: 0)) == "Essai gratuit — 11 jours restants")
        #expect(trialNotice(.trial(daysLeft: 1, endsAt: 0)) == "Dernier jour d'essai")
        #expect(trialNotice(.paid) == nil)
        #expect(trialNotice(.expired) == nil)
        #expect(trialNotice(.unknown) == nil)
    }

    @Test("max-w-[55%] resolves against the header row, not the viewport")
    func playerChipWidth() {
        // index.css: card = min(480, vw − 32); stage px-5 → −40; max-w-md = 448.
        // 390 pt phone: min(448, 358 − 40) = 318 → 55 % = 174.9.
        #expect(abs(HubMetrics.headerWidth(viewport: 390) - 318) < 0.001)
        #expect(abs(HubMetrics.playerChipMaxWidth(viewport: 390) - 174.9) < 0.001)
        // 1024 pt iPad: card caps at 480 → min(448, 440) = 440 → 55 % = 242.
        #expect(abs(HubMetrics.headerWidth(viewport: 1024) - 440) < 0.001)
        #expect(abs(HubMetrics.playerChipMaxWidth(viewport: 1024) - 242) < 0.001)
    }
}

// MARK: - Source scan

@Suite("Hub — what the file may not do")
struct HubSourceScanTests {

    private static let source: URL =
        URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // …/Tests/ALUITests/Screens
        .deletingLastPathComponent()  // …/Tests/ALUITests
        .deletingLastPathComponent()  // …/Tests
        .deletingLastPathComponent()  // …/ios-native
        .appendingPathComponent("Sources/ALUI/Screens/HubView.swift")

    private func code() throws -> String {
        try String(contentsOf: Self.source, encoding: .utf8)
            .split(separator: "\n")
            .filter { line in
                let t = line.trimmingCharacters(in: .whitespaces)
                return !t.hasPrefix("//") && !t.hasPrefix("/*") && !t.hasPrefix("*")
            }
            .joined(separator: "\n")
    }

    @Test("the scan can find the file it is meant to scan")
    func fileExists() {
        #expect(FileManager.default.fileExists(atPath: Self.source.path))
    }

    /// Invariant 7: the hub renders the DRAWN icon; `ExerciseMeta.emoji` exists
    /// in the data and must stay unused here (shell.md §4.8 — an implementer
    /// "simplifying" to `meta.emoji` breaks the invariant without touching the
    /// icon catalog).
    @Test("the hub renders ExerciseIcon and never touches meta.emoji")
    func drawnIconNeverTheEmoji() throws {
        let code = try code()
        #expect(code.contains("ExerciseIcon("))
        #expect(!code.contains(".emoji"))
    }

    /// Invariant 5's structural half: no lock vocabulary, and no entitlement
    /// gate on the level buttons — `canPlay` is `RootView.open`'s business, a
    /// hub-side check would be exactly the "if unlocked" regression.
    @Test("no lock, no gate, no canPlay in the hub")
    func noLockVocabulary() throws {
        let code = try code()
        for forbidden in ["locked", "unlocked", "requires", "canPlay("] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "HubView.swift contains \(forbidden)"))
        }
        // …and the level row really is the unconditional range.
        #expect(code.contains("1...meta.levelCount"))
    }

    /// Invariant 8's display half: the hub PREVIEWS points; it must never mint
    /// or spend them.
    @Test("the hub reads the preview and never awards or spends")
    func previewOnly() throws {
        let code = try code()
        #expect(code.contains("store.preview(exercise:"))
        for forbidden in ["award(", "spend(", ".buy(", "sessionReward"] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "HubView.swift contains \(forbidden)"))
        }
    }
}

// MARK: - Rasterised

#if canImport(AppKit) || canImport(UIKit)

    @Suite("Hub — the stage, rasterised")
    @MainActor
    struct HubRasterTests {

        @Test("the hub renders on the host and paints its warm stage top (#FFE7C9)")
        func stagePaints() throws {
            let store = makeStore()
            let entitlement = EntitlementModel(
                store: StubPurchaseStore(),
                persist: LicenseStore(InMemoryKVStore()),
                time: MutableTimeSource(t0))

            // `HubStage`, not `HubView`: `ImageRenderer` cannot lay out a
            // `ScrollView`'s content on the host, so the stage is rasterised
            // directly — it is everything the scroll contains.
            //
            // `.stageWash` is applied here because since D51 it lives OUTSIDE
            // the scroll view (a wash inside scrolling content scrolls with it),
            // so `HubStage` no longer paints itself. Same modifier, same value
            // as `HubView` — what this asserts is that the hub lays out over the
            // warm stage, not where the modifier is spelled.
            let hub = HubStage(
                audio: SilentAudioEngine(),
                onOpen: { _, _ in },
                onDashboard: {},
                onPaywall: {}
            )
            .stageWash(Palette.stage)
            .environment(store)
            .environment(entitlement)
            .frame(width: 480, height: 700, alignment: .top)

            let renderer = ImageRenderer(content: hub)
            renderer.scale = 1
            let cg = try #require(renderer.cgImage)

            var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
            let ctx = try #require(
                CGContext(
                    data: &buffer, width: cg.width, height: cg.height,
                    bitsPerComponent: 8, bytesPerRow: cg.width * 4,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))

            // Centre-top, below the rounded-corner band: the stage gradient's
            // first stop is #FFE7C9 — warm, red over blue.
            let i = (40 * cg.width + 240) * 4
            let (r, g, b) = (Int(buffer[i]), Int(buffer[i + 1]), Int(buffer[i + 2]))
            #expect(r > 200, Comment(rawValue: "expected the warm stage, got (\(r), \(g), \(b))"))
            #expect(r > b, Comment(rawValue: "expected #FFE7C9-ish, got (\(r), \(g), \(b))"))
            #expect(g > 150, Comment(rawValue: "expected #FFE7C9-ish, got (\(r), \(g), \(b))"))
        }
    }

#endif
