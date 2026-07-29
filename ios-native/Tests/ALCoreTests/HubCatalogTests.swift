import Testing

@testable import ALCore

/// Port of the "hub placement of the grid drills" block of `src/levels.test.ts`,
/// plus the catalog properties the TypeScript got from `Record<ExerciseId, …>`
/// for free and Swift has to assert.
@Suite struct HubCatalogTests {

    private static func at(_ id: ExerciseId) -> Int {
        Levels.exercises.firstIndex { $0.id == id }!
    }

    /// The ONLY ordering the TypeScript itself pins. Deliberately no full-order
    /// golden test: that would freeze something the source leaves loose.
    @Test func putsBothCombinatoireDrillsBeforeEveryWordExercise() {
        for id in [ExerciseId.hearSyllable, .pickVowel] {
            #expect(Self.at(id) > Self.at(.findSound))
            #expect(Self.at(id) < Self.at(.spellSyllable))
            #expect(Self.at(id) < Self.at(.spellSound))
        }
    }

    @Test func catalogCoversEveryExerciseExactlyOnce() {
        #expect(Set(Levels.exercises.map(\.id)) == Set(ExerciseId.allCases))
        #expect(Levels.exercises.count == ExerciseId.allCases.count)
    }

    /// `levelCount` is DERIVED from the ladders, never inlined — adding a level
    /// must update the hub for free.
    @Test func levelCountIsDerivedFromTheLadderTheRowClaims() {
        for e in Levels.exercises {
            let expected: Int
            switch e.id {
            case .firstLetter: expected = Levels.firstLetterLevels.count
            case .findSound: expected = Levels.findSoundLevelCount
            case .hearSyllable, .pickVowel: expected = Levels.syllableGridLevelCount
            case .fillBlank, .orderSyllables, .findIntruder: expected = Levels.syllableLevelCount
            case .spellSound: expected = Levels.soundLevelCount
            case .spellSyllable, .spellSyllablePlus, .spellTwoSyllables,
                .spellSyllablePlusMixed, .spellTwoSyllablesMixed:
                expected = Levels.spellSyllableLevelCount
            case .readImage: expected = Levels.readImageLevelCount
            case .matchCase, .matchScript: expected = Levels.letterMatchLevelCount
            case .soundTwins: expected = Levels.twinLevelCount
            }
            #expect(e.levelCount == expected, "\(e.id.rawValue)")
            #expect(e.levelCount > 0)
        }
    }

    @Test func exerciseDifficultyReadsTheCatalog() {
        for e in Levels.exercises {
            #expect(Levels.exerciseDifficulty(e.id) == e.difficulty)
        }
    }

    /// The training exercises — the only rows that pay nothing.
    @Test func onlyFirstLetterAndFillBlankAreTraining() {
        let training = Levels.exercises.filter { $0.difficulty == .d0 }.map(\.id)
        #expect(Set(training) == [.firstLetter, .fillBlank])
    }

    /// Recovers the exhaustiveness `Record<K, V>` gave the TypeScript for free.
    @Test func hintDictionariesAreTotal() {
        #expect(Levels.modeHint.count == SyllableMode.allCases.count)
        #expect(Levels.matchHint.count == LetterMatchKind.allCases.count)
        #expect(Levels.spellHint.count == SpellSyllableMode.allCases.count)
        #expect(Levels.gridConsigne.count == SyllableGridMode.allCases.count)
        for m in SyllableMode.allCases { #expect(Levels.modeHint[m] != nil) }
        for m in LetterMatchKind.allCases { #expect(Levels.matchHint[m] != nil) }
        for m in SpellSyllableMode.allCases { #expect(Levels.spellHint[m] != nil) }
        for m in SyllableGridMode.allCases { #expect(Levels.gridConsigne[m] != nil) }
    }

    /// French copy is a VO lookup key — the typographic apostrophe and the
    /// ellipsis character are load-bearing and must never be normalised.
    @Test func frenchCopyKeepsItsTypographicPunctuation() {
        #expect(Levels.exercises[Self.at(.findIntruder)].name == "Trouve l’intrus")
        #expect(Levels.modeHint[.order] == "Remets les syllabes dans l’ordre")
        #expect(Levels.modeHint[.orderDistractor] == "Range le mot… et évite l’intrus !")
        #expect(Levels.spellHint[.lettersExtra] == "Range les lettres… évite les intrus")
        #expect(Levels.mixedHint == "GRANDE, petite ou attachée — trouve la bonne")
        #expect(Levels.readImagePrompt == "Trouve la bonne image.")
    }

    /// The mode / grid / spell / match columns are what the router switches on.
    @Test func modeColumnsMatchTheEngineEachRowRuns() {
        #expect(Levels.exercises[Self.at(.fillBlank)].mode == .fillBlank)
        #expect(Levels.exercises[Self.at(.orderSyllables)].mode == .order)
        #expect(Levels.exercises[Self.at(.findIntruder)].mode == .orderDistractor)
        #expect(Levels.exercises[Self.at(.hearSyllable)].grid == .hear)
        #expect(Levels.exercises[Self.at(.pickVowel)].grid == .vowel)
        #expect(Levels.exercises[Self.at(.matchCase)].match == .case)
        #expect(Levels.exercises[Self.at(.matchScript)].match == .script)
        #expect(Levels.exercises[Self.at(.spellSyllable)].spell == .lettersExact)
        #expect(Levels.exercises[Self.at(.spellSyllablePlusMixed)].mixed)
        #expect(Levels.exercises[Self.at(.spellTwoSyllablesMixed)].mixed)
        // Only the two "écritures mêlées" twins carry `mixed`.
        #expect(Levels.exercises.filter(\.mixed).count == 2)
    }
}
