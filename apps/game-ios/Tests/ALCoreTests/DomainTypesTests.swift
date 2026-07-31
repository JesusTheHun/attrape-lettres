import Testing

@testable import ALCore

/// The string unions of `types.ts`. Every raw value here is either a VO lookup
/// key, a persisted value, or both — they are copied byte for byte, hyphens
/// included.
@Suite struct DomainEnumRawValueTests {
    @Test func syllableModesKeepTheirHyphens() {
        #expect(SyllableMode.allCases.map(\.rawValue) == ["fill-blank", "order", "order-distractor"])
    }

    @Test func spellSyllableModes() {
        #expect(
            SpellSyllableMode.allCases.map(\.rawValue)
                == ["letters-exact", "letters-extra", "letters-two"]
        )
    }

    @Test func syllableGridModes() {
        #expect(SyllableGridMode.allCases.map(\.rawValue) == ["hear", "vowel"])
    }

    @Test func imageKeys() {
        #expect(ImageKey.allCases.map(\.rawValue) == ["igloo", "jupe", "macaron", "pyjama"])
    }
}

@Suite struct AppRouteTests {
    @Test func playCarriesItsExerciseAndLevel() {
        let route = AppRoute.play(exercise: .spellSound, level: 3)
        guard case .play(let exercise, let level) = route else {
            Issue.record("expected .play")
            return
        }
        #expect(exercise == .spellSound)
        #expect(level == 3)
    }

    @Test func routesWithDifferentPayloadsAreDifferent() {
        #expect(AppRoute.play(exercise: .fillBlank, level: 1) != .play(exercise: .fillBlank, level: 2))
        #expect(
            AppRoute.play(exercise: .fillBlank, level: 1)
                != .play(exercise: .orderSyllables, level: 1))
        #expect(AppRoute.hub != .dashboard)
        #expect(AppRoute.hub == .hub)
    }

    /// The union must stay a union: an exhaustive switch with no `default:` is
    /// what makes a new destination a compile error at every router.
    @Test func everyCaseIsReachableExhaustively() {
        let all: [AppRoute] = [
            .hub, .play(exercise: .firstLetter, level: 1), .dashboard, .shop, .pick, .paywall,
        ]
        var names: [String] = []
        for route in all {
            switch route {
            case .hub: names.append("hub")
            case .play: names.append("play")
            case .dashboard: names.append("dashboard")
            case .shop: names.append("shop")
            case .pick: names.append("pick")
            case .paywall: names.append("paywall")
            }
        }
        #expect(names == ["hub", "play", "dashboard", "shop", "pick", "paywall"])
    }

    @Test func appViewIsTheSameType() {
        let view: AppView = .paywall
        #expect(view == AppRoute.paywall)
    }
}

@Suite struct RoundShapeTests {
    /// `slots: (string | null)[]` → `[String?]`, exactly: the engines index it
    /// and write `nil` back when a child takes a tile out of a slot.
    @Test func slotsAreNullableAndWritable() {
        var round = SyllableRound(
            word: SyllableWord(word: "CHAPEAU", syllables: ["CHA", "PEAU"], emoji: "🎩"),
            slots: ["CHA", nil],
            locked: [true, false],
            tray: [SyllableTile(id: 0, syllable: "PEAU"), SyllableTile(id: 1, syllable: "CHA")]
        )
        #expect(round.slots[1] == nil)
        round.slots[1] = "PEAU"
        #expect(round.slots == ["CHA", "PEAU"])
        round.slots[1] = nil
        #expect(round.slots[1] == nil)
        #expect(round.slots.count == round.locked.count)
    }

    /// Every tile type is `Identifiable` by its `id` — SwiftUI `ForEach` needs
    /// it for exactly the reason the TSX used these ids as React keys.
    @Test func everyTileIsIdentifiableByItsId() {
        #expect(SyllableTile(id: 7, syllable: "MI").id == 7)
        #expect(SoundTile(id: 8, letter: "L").id == 8)
        #expect(SpellLetterTile(id: 9, letter: "A", glyph: "a", script: .cursive).id == 9)
        #expect(
            TwinTile(id: 10, text: "EAU", sound: "o", word: "eau", emoji: "💧", correct: true).id
                == 10
        )
    }

    @Test func optionalContentFieldsStayOptional() {
        // A SoundTarget with no anchor word is legal (soundPrompt falls back to
        // the bare sound); a BasicSound always has one, but its traps are optional.
        let bare = SoundTarget(sound: "oi", spelling: ["O", "I"])
        #expect(bare.word == nil)
        #expect(bare.emoji == nil)
        let trapless = BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉")
        #expect(trapless.traps == nil)
        let trapped = BasicSound(
            sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉", traps: ["ON", "AN"])
        #expect(trapped.traps == ["ON", "AN"])
    }

    @Test func exerciseMetaMixedDefaultsToFalse() {
        let plain = ExerciseMeta(
            id: .spellSyllable, name: "Complète le mot", emoji: "🔤", levelCount: 4,
            difficulty: .d2, spell: .lettersExact)
        #expect(plain.mixed == false)
        #expect(plain.hint == nil)
        #expect(plain.mode == nil)
        #expect(plain.grid == nil)
        #expect(plain.match == nil)

        let mixed = ExerciseMeta(
            id: .spellSyllablePlusMixed, name: "Écritures mêlées", emoji: "🔤", levelCount: 4,
            difficulty: .d4, spell: .lettersExtra, mixed: true)
        #expect(mixed.mixed)
    }
}
