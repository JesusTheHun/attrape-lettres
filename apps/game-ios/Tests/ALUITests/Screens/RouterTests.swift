import Foundation
import SwiftUI
import Testing

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The route table of `src/App.tsx`, walked value by value.                     */
/*                                                                             */
/* Every expected value below is transcribed from the TypeScript — App.tsx      */
/* (the gates, the dispatch, `next()`, `open()`), levels.ts (the EXERCISES      */
/* array order and each row's capability fields) and licensing/entitlement.ts   */
/* (`canPlay`) — never read back out of the Swift under test.                   */
/* -------------------------------------------------------------------------- */

// MARK: - The TSX-derived tables

/// `EXERCISES` in `levels.ts:890-922` — the array order, hand-transcribed.
/// `next()` walks this by index and the hub renders it top to bottom, so the
/// order is behaviour.
private let tsxCatalogOrder: [String] = [
    "first-letter",
    "find-sound",
    "hear-syllable",
    "pick-vowel",
    "fill-blank",
    "order-syllables",
    "find-intruder",
    "spell-sound",
    "spell-syllable",
    "spell-syllable-plus",
    "spell-two-syllables",
    "read-image",
    "match-case",
    "match-script",
    "sound-twins",
    "spell-syllable-plus-mixed",
    "spell-two-syllables-mixed",
]

/// The dispatch of `App.tsx:89-121`, resolved by hand for every row: four id
/// checks (`read-image`, `spell-sound`, `find-sound`, `sound-twins`), then
/// `meta.grid`, `meta.spell` (+`meta.mixed`), `meta.match`, and finally
/// `meta.mode` ?: first-letter.
private let tsxDispatch: [String: ExerciseEngine] = [
    "first-letter": .firstLetter,
    "find-sound": .findSound,
    "hear-syllable": .syllableGrid(.hear),
    "pick-vowel": .syllableGrid(.vowel),
    "fill-blank": .assemble(.fillBlank),
    "order-syllables": .assemble(.order),
    "find-intruder": .assemble(.orderDistractor),
    "spell-sound": .spellSound,
    "spell-syllable": .spellSyllable(.lettersExact, mixed: false),
    "spell-syllable-plus": .spellSyllable(.lettersExtra, mixed: false),
    "spell-two-syllables": .spellSyllable(.lettersTwo, mixed: false),
    "read-image": .readImage,
    "match-case": .letterMatch(.case),
    "match-script": .letterMatch(.script),
    "sound-twins": .soundTwins,
    "spell-syllable-plus-mixed": .spellSyllable(.lettersExtra, mixed: true),
    "spell-two-syllables-mixed": .spellSyllable(.lettersTwo, mixed: true),
]

private func meta(
    _ id: ExerciseId,
    levelCount: Int = 3,
    hint: String? = nil,
    mode: SyllableMode? = nil,
    grid: SyllableGridMode? = nil,
    spell: SpellSyllableMode? = nil,
    mixed: Bool = false,
    match: LetterMatchKind? = nil
) -> ExerciseMeta {
    ExerciseMeta(
        id: id, name: "x", emoji: "x", levelCount: levelCount, difficulty: .d1,
        hint: hint, mode: mode, grid: grid, spell: spell, mixed: mixed, match: match)
}

// MARK: - Dispatch

@Suite("Router — the exercise dispatch (App.tsx:89-121)")
struct RouterDispatchTests {

    @Test("the catalog is the TSX catalog: 17 rows, same ids, same order")
    func catalogMatchesTheTSX() {
        #expect(Levels.exercises.map(\.id.rawValue) == tsxCatalogOrder)
        #expect(Set(Levels.exercises.map(\.id)) == Set(ExerciseId.allCases))
    }

    @Test("every catalog row resolves to the engine the TSX if-chain picks")
    func allSeventeenRows() {
        #expect(tsxDispatch.count == 17)
        for row in Levels.exercises {
            #expect(
                engine(for: row) == tsxDispatch[row.id.rawValue],
                Comment(rawValue: "\(row.id.rawValue) dispatched to the wrong engine"))
        }
    }

    @Test("the id checks come FIRST: read-image with a mode field is still read-image")
    func idChecksPrecedeCapabilities() {
        // No real row has this shape; the ORDER of the checks is the behaviour.
        #expect(engine(for: meta(.readImage, mode: .order)) == .readImage)
        #expect(engine(for: meta(.spellSound, grid: .hear)) == .spellSound)
        #expect(engine(for: meta(.findSound, spell: .lettersTwo)) == .findSound)
        #expect(engine(for: meta(.soundTwins, match: .case)) == .soundTwins)
    }

    @Test("grid outranks spell outranks match outranks mode")
    func capabilityOrder() {
        #expect(
            engine(for: meta(.fillBlank, mode: .order, grid: .vowel, spell: .lettersTwo, match: .case))
                == .syllableGrid(.vowel))
        #expect(
            engine(for: meta(.fillBlank, mode: .order, spell: .lettersTwo, match: .case))
                == .spellSyllable(.lettersTwo, mixed: false))
        #expect(
            engine(for: meta(.fillBlank, mode: .order, match: .case)) == .letterMatch(.case))
        #expect(engine(for: meta(.fillBlank, mode: .order)) == .assemble(.order))
    }

    @Test("no fields at all falls through to first-letter")
    func bareMetaIsFirstLetter() {
        #expect(engine(for: meta(.firstLetter)) == .firstLetter)
    }

    @Test("mixed rides the spell dispatch: same mode, different flag")
    func mixedTravels() {
        #expect(
            engine(for: meta(.spellSyllablePlusMixed, spell: .lettersExtra, mixed: true))
                == .spellSyllable(.lettersExtra, mixed: true))
        #expect(
            engine(for: meta(.spellSyllablePlus, spell: .lettersExtra))
                == .spellSyllable(.lettersExtra, mixed: false))
    }
}

// MARK: - « Suivant »

@Suite("Router — « Suivant » rolls levels, then exercises, then the hub (App.tsx:82-87)")
struct RouterNextTests {

    @Test("mid-exercise: level + 1, same exercise")
    func bumpsTheLevel() {
        #expect(
            nextRoute(after: .play(exercise: .fillBlank, level: 1), in: Levels.exercises)
                == .play(exercise: .fillBlank, level: 2))
    }

    @Test("past the last level: level 1 of the NEXT catalog row")
    func rollsIntoTheNextExercise() {
        // first-letter is row 0; find-sound is row 1 (the TSX array).
        let last = Levels.exercises[0].levelCount
        #expect(
            nextRoute(after: .play(exercise: .firstLetter, level: last), in: Levels.exercises)
                == .play(exercise: .findSound, level: 1))
    }

    @Test("every non-final row rolls into its successor — the whole ladder, in TSX order")
    func everyRowRollsForward() {
        for (index, row) in Levels.exercises.enumerated() where index + 1 < Levels.exercises.count {
            let next = Levels.exercises[index + 1]
            #expect(
                nextRoute(after: .play(exercise: row.id, level: row.levelCount), in: Levels.exercises)
                    == .play(exercise: next.id, level: 1),
                Comment(rawValue: "\(row.id.rawValue) should roll into \(next.id.rawValue)"))
        }
    }

    @Test("past the final exercise's last level: back to the hub")
    func lastLevelOfTheLastExerciseGoesHome() throws {
        let last = try #require(Levels.exercises.last)
        #expect(last.id.rawValue == "spell-two-syllables-mixed")  // the TSX's final row
        #expect(
            nextRoute(after: .play(exercise: last.id, level: last.levelCount), in: Levels.exercises)
                == .hub)
    }

    @Test("a level past the end behaves like the last one (TSX: `level < levelCount` is the only check)")
    func overshootRollsForwardToo() {
        #expect(
            nextRoute(after: .play(exercise: .firstLetter, level: 99), in: Levels.exercises)
                == .play(exercise: .findSound, level: 1))
    }

    @Test("non-play routes have no “next”: hub")
    func nonPlayRoutesGoHome() {
        for route in [AppRoute.hub, .dashboard, .shop, .pick, .paywall] {
            #expect(nextRoute(after: route, in: Levels.exercises) == .hub)
        }
    }

    @Test("an exercise missing from the catalog bounces to the hub instead of trapping")
    func missingExerciseGoesHome() {
        // TSX: findIndex −1 ⇒ `meta.levelCount` crashes. The port bounces
        // (shell.md §7.3 — a recorded divergence in an unreachable path).
        let without = Levels.exercises.filter { $0.id != .fillBlank }
        #expect(nextRoute(after: .play(exercise: .fillBlank, level: 1), in: without) == .hub)
        #expect(nextRoute(after: .play(exercise: .fillBlank, level: 1), in: []) == .hub)
    }

    @Test("the remount key is unique per (exercise, level) and stable elsewhere")
    func remountKeys() {
        var seen = Set<String>()
        for row in Levels.exercises {
            for level in 1...row.levelCount {
                let key = AppRoute.play(exercise: row.id, level: level).remountKey
                #expect(key == "\(row.id.rawValue)-\(level)")  // the TSX template
                #expect(seen.insert(key).inserted, Comment(rawValue: "duplicate key \(key)"))
            }
        }
        #expect(AppRoute.hub.remountKey == "route")
        #expect(AppRoute.dashboard.remountKey == AppRoute.shop.remountKey)
    }
}

// MARK: - Gates

@Suite("Router — the ordered gates (App.tsx:54-73)")
struct RouterGateTests {

    @Test("the dev benches win over EVERYTHING, onboarding and roster included")
    func devBenchesWinOverEverything() {
        #expect(
            shellGate(dev: .stages, onboarded: false, activeId: nil, chosen: false, route: .hub)
                == .devStages)
        #expect(
            shellGate(
                dev: .vo, onboarded: false, activeId: nil, chosen: false,
                route: .play(exercise: .fillBlank, level: 2))
                == .devVo)
    }

    @Test("a fresh device meets the parent screen before anything else")
    func onboardingBeatsTheRoster() {
        #expect(
            shellGate(dev: nil, onboarded: false, activeId: nil, chosen: false, route: .hub)
                == .onboarding)
        // …even if a route was somehow already stored.
        #expect(
            shellGate(
                dev: nil, onboarded: false, activeId: "c1", chosen: true, route: .shop)
                == .onboarding)
    }

    @Test("no active player: « Qui joue ? », whatever the stored route says")
    func rosterGateOverridesTheRoute() {
        // The silent override (shell.md §4.1): `.play` is stored, the child
        // disappeared, the roster screen wins. Do not reorder for tidiness.
        #expect(
            shellGate(
                dev: nil, onboarded: true, activeId: nil, chosen: true,
                route: .play(exercise: .findSound, level: 3))
                == .whoIsPlaying)
    }

    @Test("no species chosen: the first-run picker IS the app")
    func speciesGateOverridesTheRoute() {
        #expect(
            shellGate(
                dev: nil, onboarded: true, activeId: "c1", chosen: false,
                route: .play(exercise: .findSound, level: 3))
                == .firstRunPicker)
    }

    @Test("all gates passed: the stored route renders, whichever kind it is")
    func routesPassThrough() {
        for route in [
            AppRoute.hub, .play(exercise: .readImage, level: 2), .dashboard, .shop, .pick,
            .paywall,
        ] {
            #expect(
                shellGate(dev: nil, onboarded: true, activeId: "c1", chosen: true, route: route)
                    == .screen(route))
        }
    }
}

// MARK: - The parent gate, once per device

@Suite("Router — onboarding happens once per DEVICE, across launches")
@MainActor
struct RouterOnboardingPersistenceTests {

    private func gate(_ model: EntitlementModel) -> ShellGate {
        shellGate(
            dev: nil, onboarded: model.onboarded, activeId: "c1", chosen: true, route: .hub)
    }

    @Test("first launch gates on onboarding; beginTrial opens the app; a second launch stays open")
    func onboardedSurvivesRelaunch() async {
        let kv = InMemoryKVStore()
        let time = MutableTimeSource(1_700_000_000_000)

        // Launch 1 — a fresh device.
        let first = EntitlementModel(
            store: StubPurchaseStore(), persist: LicenseStore(kv), time: time)
        #expect(first.onboarded == false)
        #expect(gate(first) == .onboarding)

        // The parent taps « Commencer » → `beginTrial()` persists synchronously
        // FIRST, so the screen dismisses even if everything after fails.
        first.beginTrial()
        #expect(first.onboarded == true)
        #expect(gate(first) == .screen(.hub))

        // Launch 2 — a NEW model over the SAME storage: the gate never returns.
        let second = EntitlementModel(
            store: StubPurchaseStore(), persist: LicenseStore(kv), time: time)
        #expect(second.onboarded == true)
        #expect(gate(second) == .screen(.hub))
    }
}

// MARK: - Opening a level

@Suite("Router — opening a level (App.tsx:147-154, entitlement.ts canPlay)")
struct RouterOpenTests {

    @Test("expired is the ONLY entitlement that diverts to the paywall")
    func onlyExpiredDiverts() {
        // entitlement.ts: `canPlay = e.status !== "expired"` — a blacklist of one.
        #expect(
            openOutcome(exercise: .findSound, level: 2, entitlement: .expired) == .paywall)
        #expect(
            openOutcome(exercise: .findSound, level: 2, entitlement: .paid)
                == .play(.findSound, 2))
        #expect(
            openOutcome(
                exercise: .findSound, level: 2,
                entitlement: .trial(daysLeft: 3, endsAt: 0))
                == .play(.findSound, 2))
    }

    @Test("a store that has not answered PLAYS (invariant 11)")
    func unknownPlays() {
        #expect(
            openOutcome(exercise: .spellSound, level: 1, entitlement: .unknown)
                == .play(.spellSound, 1))
    }
}

// MARK: - Dev benches

@Suite("Router — the #stages / #vo stand-in")
struct RouterDevScreenTests {

    @Test("the launch argument resolves both benches and nothing else")
    func parsing() {
        #expect(DevScreen.parse(arguments: ["-ALDevScreen", "stages"]) == .stages)
        #expect(DevScreen.parse(arguments: ["-ALDevScreen", "vo"]) == .vo)
        #expect(DevScreen.parse(arguments: ["app", "-Other", "x", "-ALDevScreen", "vo"]) == .vo)
        #expect(DevScreen.parse(arguments: []) == nil)
        #expect(DevScreen.parse(arguments: ["-ALDevScreen"]) == nil)  // dangling flag
        #expect(DevScreen.parse(arguments: ["-ALDevScreen", "bogus"]) == nil)  // a typo must not strand the app
        #expect(DevScreen.parse(arguments: ["stages"]) == nil)  // value without the flag
    }
}

// MARK: - Invariant 11 at the composition root

/// A store that answers NOTHING: unreachable, refuses to sell, no price. The
/// harshest failure the root can meet short of not existing.
private struct DeadStore: PurchaseStore {
    var available: Bool { false }
    func refresh() async -> StoreSnapshot { .unreachable }
    func beginTrial() async -> Int64? { nil }
    func purchase(_ tier: UnlockTier) async -> Bool { false }
    func restore() async -> Bool { false }
    func priceLabel(_ tier: UnlockTier) async -> String? { nil }
}

#if canImport(AppKit) || canImport(UIKit)

    @Suite("RootView — invariant 11: no store, no network, and the app still opens playable")
    @MainActor
    struct RootCompositionTests {

        /// The full screen graph over dead adapters: in-memory KV, a store that
        /// answers nothing, telemetry with no endpoint, sync never constructed.
        /// This is `swift test` on the host — no simulator, no signing either.
        private func makeWorld() async -> (
            profiles: ProfileStore, entitlement: EntitlementModel, telemetry: Telemetry,
            transport: RecordingTransport, kv: InMemoryKVStore, time: MutableTimeSource
        ) {
            let kv = InMemoryKVStore()
            let time = MutableTimeSource(1_700_000_000_000)
            let entitlement = EntitlementModel(
                store: DeadStore(), persist: LicenseStore(kv), time: time)
            // The gates a real family has already passed:
            entitlement.beginTrial()
            let profiles = ProfileStore(kv: kv, device: { "test-device" }, now: { time.nowMillis })
            profiles.createChild(name: "Léa")
            profiles.chooseSpecies(.unicorn)
            // ALCore's own recorder — the correct transport seam, no network.
            let transport = RecordingTransport()
            let telemetry = Telemetry(
                endpoint: nil,  // a build that sets no endpoint posts nowhere
                transport: transport,
                kv: kv,
                appVersion: FixedAppVersion("1.0.0"))
            return (profiles, entitlement, telemetry, transport, kv, time)
        }

        @Test("the whole graph builds and rasterises on the host, and the child lands on the HUB")
        func opensOnTheHub() async throws {
            let world = await makeWorld()

            // The routing facts first, so the raster below means something:
            // every gate passes and an exercise tap goes to PLAY, not paywall.
            #expect(
                shellGate(
                    dev: nil,
                    onboarded: world.entitlement.onboarded,
                    activeId: world.profiles.activeId,
                    chosen: world.profiles.profile.chosen,
                    route: .hub)
                    == .screen(.hub))
            #expect(canPlay(world.entitlement.entitlement))
            #expect(
                openOutcome(
                    exercise: .findSound, level: 1,
                    entitlement: world.entitlement.entitlement)
                    == .play(.findSound, 1))

            // Now the pixels: RootView over the dead world must produce a frame.
            let root = RootView(
                audio: SilentAudioEngine(),
                kv: world.kv,
                time: world.time,
                dev: nil
            )
            .environment(world.profiles)
            .environment(world.entitlement)
            .environment(world.telemetry)
            .frame(width: 480, height: 700)

            let renderer = ImageRenderer(content: root)
            renderer.scale = 1
            let image = try #require(
                renderer.cgImage,
                Comment(rawValue: "the composition root failed to render with no store and no network"))
            #expect(image.width == 480)

            // A drawn pixel proves the graph actually RENDERED, not merely
            // constructed. NB the limit of this tier: `ImageRenderer` cannot
            // lay out a `ScrollView`'s content on the host, so the pixel here
            // is the page chrome (`Palette.page`, #EFE6DA), not the hub stage —
            // the stage's own paint is asserted in `HubTests.stagePaints` over
            // `HubStage` directly. That the hub IS the resolved screen is the
            // `shellGate` assertion above.
            let pixel = try #require(samplePixel(image, x: 240, y: 40))
            #expect(pixel.r > 200, Comment(rawValue: "expected the warm page, got \(pixel)"))
            #expect(pixel.r > pixel.b, Comment(rawValue: "expected #EFE6DA-ish, got \(pixel)"))
        }

        @Test("nothing left the device while the graph booted (invariant 10 at the root)")
        func nothingTransmitted() async {
            let world = await makeWorld()
            // No consent was ever given and no endpoint exists — the recording
            // transport must never have been touched, even by construction.
            world.telemetry.track(.exerciseStarted, TelemetryProps(exercise: .findSound, level: 1))
            world.telemetry.flush()
            await world.telemetry.awaitPendingSends()
            #expect(world.transport.sent.isEmpty)
        }

        @Test("an EXPIRED family still reaches the hub; only the level tap diverts")
        func expiredStillOpens() async throws {
            let world = await makeWorld()
            world.time.advance(days: 30)  // trial long gone, store still dead
            // The resume path: `scenePhase == .active` → `refresh()`. The dead
            // store answers `.unreachable`; the clock still moves the trial out.
            await world.entitlement.refresh()

            #expect(world.entitlement.entitlement == .expired)
            // The hub gate does NOT consult the entitlement — the screen renders.
            #expect(
                shellGate(
                    dev: nil,
                    onboarded: world.entitlement.onboarded,
                    activeId: world.profiles.activeId,
                    chosen: world.profiles.profile.chosen,
                    route: .hub)
                    == .screen(.hub))
            // The only thing an expired trial blocks: starting a new round.
            #expect(
                openOutcome(
                    exercise: .findSound, level: 1,
                    entitlement: world.entitlement.entitlement)
                    == .paywall)
        }
    }

    /// One RGBA pixel out of a rasterised `CGImage` (GameFrameZOrderTests' tier).
    @MainActor
    private func samplePixel(_ cg: CGImage, x: Int, y: Int) -> (r: Int, g: Int, b: Int)? {
        var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        guard
            let ctx = CGContext(
                data: &buffer, width: cg.width, height: cg.height,
                bitsPerComponent: 8, bytesPerRow: cg.width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        guard x >= 0, y >= 0, x < cg.width, y < cg.height else { return nil }
        let i = (y * cg.width + x) * 4
        return (Int(buffer[i]), Int(buffer[i + 1]), Int(buffer[i + 2]))
    }

#endif
