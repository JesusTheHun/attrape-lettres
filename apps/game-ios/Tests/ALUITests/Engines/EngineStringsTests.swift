import Testing

import ALCore
@testable import ALUI

// Every French string the engines emit, snapshot against the TSX verbatim
// (engines.md §6.23). These strings double as VO clip-lookup keys (D17): a
// one-character drift — an apostrophe normalised, a period dropped — silently
// downgrades that line to TTS with no error anywhere. The expected literals
// below were copied from `src/exercises/*.tsx` and `src/levels.ts`, byte for
// byte, and the scalar assertions pin the two characters most at risk.

@Suite("Engine strings — spoken lines")
@MainActor
struct EngineSpokenLineTests {

    private var deps: EngineDeps { EngineHarness().deps }

    @Test func sharedEngineLines() {
        #expect(EngineLines.ohNon == "Oh non ! On recommence.")
        #expect(EngineLines.bravoFound == "Bravo ! Tu as tout trouvé !")
        #expect(EngineLines.bravoSucceeded == "Bravo ! Tu as tout réussi !")
        #expect(EngineLines.announceDelayMs == 350)
        #expect(EngineLines.successRate == 0.98)
        #expect(EngineLines.defaultPitch == 1.1)
    }

    @Test func firstLetterPromptAndSuccess() {
        let model = SinglePickModel.firstLetter(level: 1, deps: deps, rng: .seeded(1))
        let round = FirstLetterRound(
            target: LetterWord(letter: "V", word: "vélo", emoji: "🚲"),
            choices: ["V", "A", "B"])
        #expect(model.descriptor.promptLine(round) == "Trouve la première lettre de vélo.")
        #expect(model.descriptor.successLine(round) == "Oui ! V. vélo.")
        #expect(model.descriptor.targetKey(round) == "V")
        #expect(model.listenAccessibilityLabel == "Répéter le mot")
        #expect(model.finishedTitle == "Tu as tout trouvé !")
        #expect(model.headline == nil)
    }

    @Test func findSoundPromptSuccessAndHeadline() {
        let model = SinglePickModel.findSound(level: 1, deps: deps, rng: .seeded(1))
        let round = FindSoundRound(
            target: BasicSound(sound: "ou", graphy: "OU", word: "hibou", emoji: "🦉"),
            choices: [])
        #expect(model.descriptor.promptLine(round) == "ou, comme dans hibou.")
        #expect(model.descriptor.successLine(round) == "Oui ! hibou.")
        let headline = model.headline
        #expect(headline == "Écoute le son et trouve comment il s'écrit")
        // U+0027 APOSTROPHE, never the typographic U+2019 (D17).
        #expect(headline?.unicodeScalars.contains("\u{0027}") == true)
        #expect(headline?.unicodeScalars.contains("\u{2019}") == false)
        #expect(model.listenText == "🔊 Écouter")
        #expect(model.listenAccessibilityLabel == "Réécouter le son")
    }

    @Test func syllableGridPromptSuccessTitlesAndConsignes() {
        let model = SinglePickModel.syllableGrid(
            exercise: .hearSyllable, mode: .hear, level: 1, deps: deps, rng: .seeded(1))
        let s = GridSyllable(text: "VA", sound: "va", consonant: "V", vowel: "A")
        let round = GridRound(target: s, choices: [])
        #expect(model.descriptor.promptLine(round) == "va", Comment(rawValue: "the bare syllable, no word"))
        #expect(model.descriptor.successLine(round) == "Oui ! va.")
        #expect(model.finishedTitle == "Tu as tout lu !")
        #expect(model.headline == "Écoute la syllabe et trouve son écriture")
        #expect(model.listenAccessibilityLabel == "Réécouter la syllabe")
        let vowel = SinglePickModel.syllableGrid(
            exercise: .pickVowel, mode: .vowel, level: 1, deps: deps, rng: .seeded(1))
        #expect(vowel.headline == "Écoute la syllabe et trouve la voyelle qui manque")
    }

    @Test func letterMatchFourFixedPromptLines() {
        let model = SinglePickModel.letterMatch(
            exercise: .matchCase, kind: .case, level: 1, deps: deps, rng: .seeded(1))
        let upperA = LetterFace(base: "A", glyph: "A", script: .print)
        let lowerA = LetterFace(base: "A", glyph: "a", script: .print)
        let cursiveA = LetterFace(base: "A", glyph: "a", script: .cursive)
        // Prompt upper, answer lower → « petite »; the line NEVER names the letter.
        let toLower = LetterMatchRound(prompt: upperA, choices: [lowerA])
        #expect(model.descriptor.promptLine(toLower) == "Trouve la petite lettre.")
        let toUpper = LetterMatchRound(prompt: lowerA, choices: [upperA])
        #expect(model.descriptor.promptLine(toUpper) == "Trouve la grande lettre.")
        let toCursive = LetterMatchRound(prompt: lowerA, choices: [cursiveA])
        #expect(model.descriptor.promptLine(toCursive) == "Trouve la lettre attachée.")
        let toPrint = LetterMatchRound(prompt: cursiveA, choices: [lowerA])
        #expect(model.descriptor.promptLine(toPrint) == "Trouve la lettre en script.")
        // The success line names it: « Oui ! A. »
        #expect(model.descriptor.successLine(toLower) == "Oui ! A.")
        // The listen button shows the line itself.
        #expect(model.descriptor.listenText(toLower) == "🔊 Trouve la petite lettre.")
        #expect(model.listenAccessibilityLabel == "Répéter la consigne")
    }

    @Test func readImagePromptHeadlineAndListenText() {
        let model = SinglePickModel.readImage(level: 1, deps: deps, rng: .seeded(1))
        #expect(model.promptText == "Trouve la bonne image.")
        let round = ReadImageRound(
            target: LetterWord(letter: "C", word: "canard", emoji: "🦆"), choices: [])
        #expect(model.descriptor.successLine(round) == "Oui ! canard.")
        #expect(model.headline == "Lis le mot et touche la bonne image")
        #expect(model.descriptor.listenText(round) == "🔊 Trouve la bonne image.")
        #expect(model.listenAccessibilityLabel == "Répéter la consigne")
        #expect(model.descriptor.previewGuardedByLock == true)
    }

    @Test func assembleHeadlinesKeepTheTypographicApostrophe() {
        let deps = self.deps
        let fill = AssemblyModel.assemble(
            exercise: .fillBlank, mode: .fillBlank, level: 1, deps: deps, rng: .seeded(1))
        #expect(fill.headline == "Trouve la syllabe manquante")
        let order = AssemblyModel.assemble(
            exercise: .orderSyllables, mode: .order, level: 1, deps: deps, rng: .seeded(1))
        #expect(order.headline == "Remets les syllabes dans l\u{2019}ordre")
        let intruder = AssemblyModel.assemble(
            exercise: .findIntruder, mode: .orderDistractor, level: 1, deps: deps, rng: .seeded(1))
        #expect(intruder.headline == "Range le mot\u{2026} et évite l\u{2019}intrus !")
        #expect(order.finishedTitle == "Tu as tout réussi !")
        #expect(order.listenAccessibilityLabel == "Répéter le mot")
        // Success line off a synthetic round.
        let word = SyllableWord(word: "BATEAU", syllables: ["BA", "TEAU"], emoji: "⛵")
        let round = SyllableRound(word: word, slots: [nil, nil], locked: [false, false], tray: [])
        #expect(order.descriptor.successLine(round) == "Oui ! BATEAU.")
        #expect(order.descriptor.promptLine(word) == "BATEAU")
    }

    @Test func spellSoundPromptsHeadlineAndTitle() {
        let model = AssemblyModel.spellSound(level: 1, deps: deps, rng: .seeded(1))
        #expect(model.headline == "Écoute le son et écris-le avec les lettres")
        #expect(model.finishedTitle == "Tu as tout réussi !")
        #expect(model.listenAccessibilityLabel == "Réécouter le son")
        let anchored = SoundTarget(sound: "ou", spelling: ["O", "U"], word: "hibou", emoji: "🦉")
        #expect(model.descriptor.promptLine(anchored) == "ou, comme dans hibou.")
        let bare = SoundTarget(sound: "a", spelling: ["A"])
        #expect(model.descriptor.promptLine(bare) == "a", Comment(rawValue: "no anchor word → the bare sound"))
        let anchoredRound = SoundRound(target: anchored, slots: [nil, nil], tray: [])
        #expect(model.descriptor.successLine(anchoredRound) == "Oui ! hibou.")
        let bareRound = SoundRound(target: bare, slots: [nil], tray: [])
        #expect(model.descriptor.successLine(bareRound) == "Oui ! a.")
    }

    @Test func spellSyllableHeadlinesPlainAndMixed() {
        let deps = self.deps
        func headline(_ mode: SpellSyllableMode, mixed: Bool) -> String? {
            AssemblyModel.spellSyllable(
                exercise: .spellSyllable, mode: mode, level: 1, mixed: mixed,
                deps: deps, rng: .seeded(1)
            ).headline
        }
        #expect(headline(.lettersExact, mixed: false) == "Complète le mot avec les lettres")
        #expect(headline(.lettersExtra, mixed: false) == "Complète le mot \u{2014} attention aux intrus")
        #expect(headline(.lettersTwo, mixed: false) == "Complète les deux syllabes")
        #expect(headline(.lettersExact, mixed: true) == "Trouve la bonne écriture")
        #expect(headline(.lettersExtra, mixed: true) == "La bonne lettre\u{2026} et la bonne écriture")
        #expect(headline(.lettersTwo, mixed: true) == "Deux syllabes \u{2014} la bonne écriture")
        let model = AssemblyModel.spellSyllable(
            exercise: .spellSyllable, mode: .lettersExact, level: 1, deps: deps, rng: .seeded(1))
        #expect(model.listenAccessibilityLabel == "Réécouter le mot")
    }

    @Test func twinsHeadlinePromptAndSuccess() {
        let model = TwinsModel(level: 1, deps: deps, rng: .seeded(1))
        let headline = model.headline
        #expect(headline == "Un son peut s'écrire de plusieurs façons \u{2014} trouve-les toutes !")
        // ASCII apostrophe + em dash — the exact scalars of the TSX (D17).
        #expect(headline?.unicodeScalars.contains("\u{0027}") == true)
        #expect(headline?.unicodeScalars.contains("\u{2019}") == false)
        #expect(headline?.unicodeScalars.contains("\u{2014}") == true)
        #expect(model.finishedTitle == "Tu as tout trouvé !")
        #expect(model.listenAccessibilityLabel == "Réécouter le son")
        let family = TwinFamily(sound: "ko", graphies: [])
        #expect(Levels.twinPrompt(family) == "Trouve tous les ko !")
        let graphy = TwinGraphy(text: "CO", word: "coq", emoji: "🐓")
        #expect(Levels.twinSuccess(graphy) == "Oui ! coq.")
    }
}
