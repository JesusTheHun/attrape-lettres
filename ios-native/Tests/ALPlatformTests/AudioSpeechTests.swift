import AVFoundation
import Testing

@testable import ALPlatform

// `voiceScore` / `pickBestFr` and the rate/pitch mapping.
//
// The iOS voice inventory is not the web's, so the NAME heuristics of the TS
// (`/enhanced|premium|neural|siri/ → +5`, a `+3` list of French voice names,
// `−5` for `compact|espeak`) are all proxies for one question that
// `AVSpeechSynthesisVoice.quality` answers outright. What is asserted here is
// the RANKING those proxies were serving, which is what must survive.

@Suite("Speech fallback")
struct AudioSpeechTests {

    private func voice(
        _ name: String,
        _ language: String,
        _ quality: VoiceQuality
    ) -> FrenchVoiceCandidate {
        FrenchVoiceCandidate(identifier: "id.\(name)", name: name, language: language, quality: quality)
    }

    @Test("French is fr, fr-FR, fr_CA — and nothing that merely starts with those letters")
    func frenchDetection() {
        #expect(FrenchVoicePicker.isFrench("fr"))
        #expect(FrenchVoicePicker.isFrench("fr-FR"))
        #expect(FrenchVoicePicker.isFrench("fr-CA"))
        #expect(FrenchVoicePicker.isFrench("fr_CA"))
        #expect(FrenchVoicePicker.isFrench("FR-fr"))
        #expect(FrenchVoicePicker.isFrench("french") == false)
        #expect(FrenchVoicePicker.isFrench("fry") == false)
        #expect(FrenchVoicePicker.isFrench("en-US") == false)
    }

    @Test("premium > enhanced > default, and fr-FR breaks the tie")
    func scoring() {
        // +5 premium / +4 enhanced (web: +5 for the enhanced-or-better name
        // pattern), +1 for France French (web: /^fr-FR/ → +1).
        #expect(FrenchVoicePicker.score(voice("Marie", "fr-FR", .premium)) == 6)
        #expect(FrenchVoicePicker.score(voice("Nicolas", "fr-CA", .premium)) == 5)
        #expect(FrenchVoicePicker.score(voice("Thomas", "fr-FR", .enhanced)) == 5)
        #expect(FrenchVoicePicker.score(voice("Amélie", "fr-CA", .enhanced)) == 4)
        #expect(FrenchVoicePicker.score(voice("Daniel", "fr-FR", .standard)) == 1)
        #expect(FrenchVoicePicker.score(voice("Aurélie", "fr-CA", .standard)) == 0)
    }

    @Test("the best French voice wins; non-French voices are never considered")
    func picking() {
        let inventory = [
            voice("Samantha", "en-US", .premium),
            voice("Daniel", "fr-FR", .standard),
            voice("Amélie", "fr-CA", .enhanced),
            voice("Marie", "fr-FR", .premium),
            voice("Thomas", "fr-FR", .enhanced),
        ]
        #expect(FrenchVoicePicker.pickBest(inventory)?.name == "Marie")
        #expect(FrenchVoicePicker.pickBest([]) == nil)
        #expect(FrenchVoicePicker.pickBest([voice("Samantha", "en-US", .premium)]) == nil)
        // Only French at all, and only the tinny built-in: still picked, because
        // "no French voice" is the only condition that falls back to
        // `AVSpeechSynthesisVoice(language: "fr-FR")`.
        #expect(FrenchVoicePicker.pickBest([voice("Daniel", "fr-FR", .standard)])?.name == "Daniel")
    }

    @Test("ties keep the inventory's own order, so the pick is stable across launches")
    func tiesAreStable() {
        // Both score 5. `Array.prototype.sort` is stable in every engine the PWA
        // runs on; `sorted(by:)` in Swift is not, so this is a real port hazard —
        // the child would get a different voice on some launches.
        let a = voice("Nicolas", "fr-CA", .premium)
        let b = voice("Thomas", "fr-FR", .enhanced)
        #expect(FrenchVoicePicker.score(a) == FrenchVoicePicker.score(b))
        #expect(FrenchVoicePicker.pickBest([a, b])?.name == "Nicolas")
        #expect(FrenchVoicePicker.pickBest([b, a])?.name == "Thomas")
    }

    @Test("rate is mapped onto the AVSpeech scale, never assigned raw")
    func rateMapping() {
        // `SpeechSynthesisUtterance.rate` is 1.0-is-normal;
        // `AVSpeechUtterance.rate` is a 0…1 dial whose DEFAULT is 0.5.
        // Assigning the web's 0.94 straight across would be near-maximum gabble.
        #expect(abs(SpeechRate.avRate(for: 1.0) - AVSpeechUtteranceDefaultSpeechRate) < 1e-6)
        #expect(abs(SpeechRate.avRate(for: 0.94) - AVSpeechUtteranceDefaultSpeechRate * 0.94) < 1e-6)
        #expect(SpeechRate.avRate(for: 0.94) < 0.94)
        // The one call site that is not the default: the balance read-out.
        #expect(abs(SpeechRate.avRate(for: 0.85) - AVSpeechUtteranceDefaultSpeechRate * 0.85) < 1e-6)
        // Clamped both ways.
        #expect(SpeechRate.avRate(for: 100) == AVSpeechUtteranceMaximumSpeechRate)
        #expect(SpeechRate.avRate(for: -1) == AVSpeechUtteranceMinimumSpeechRate)
    }

    @Test("pitch shares the web's 1.0-is-normal scale but clamps to 0.5…2.0")
    func pitchMapping() {
        #expect(SpeechRate.avPitch(for: 1.1) == 1.1)
        #expect(SpeechRate.avPitch(for: 1.0) == 1.0)
        #expect(SpeechRate.avPitch(for: 0) == 0.5)  // web allows 0, AVSpeech does not
        #expect(SpeechRate.avPitch(for: 3) == 2.0)
    }

    @Test("AVSpeechSynthesisVoiceQuality maps onto the scoring ladder")
    func qualityLadderMatchesAVFoundation() {
        // `.default` = 1, `.enhanced` = 2, `.premium` = 3 — the adapter subtracts
        // one to land on VoiceQuality. If Apple ever renumbers, this fails here
        // rather than silently demoting every premium voice.
        #expect(AVSpeechSynthesisVoiceQuality.default.rawValue - 1 == VoiceQuality.standard.rawValue)
        #expect(AVSpeechSynthesisVoiceQuality.enhanced.rawValue - 1 == VoiceQuality.enhanced.rawValue)
        #expect(AVSpeechSynthesisVoiceQuality.premium.rawValue - 1 == VoiceQuality.premium.rawValue)
    }
}
