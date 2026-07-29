import AVFoundation
import Foundation

// `speechSynthesis` → `AVSpeechSynthesizer`.
//
// Exactly ONE product path reaches this in normal play: tapping the score to
// hear the balance read out (`String(profile.balance)`, `App.tsx:44`) — the only
// utterance in the app with no baked clip. Its real job is the safety net: a
// missing or corrupt clip degrades to the OS voice rather than to silence, which
// is what keeps invariant 3 true.
//
// NOTHING here may reach the network. The web scores `localService === false`
// (+2 for network voices) and that is deliberately NOT ported: any server-side
// TTS would send a child's progress number to a third party. Personal Voice is
// likewise untouched — it prompts, it is a privacy surface, and the PWA has no
// analogue.

/// The `AVSpeechSynthesisVoice.Quality` ladder, as a value type so the picker is
/// host-testable without an installed voice inventory.
public enum VoiceQuality: Int, Comparable, Sendable {
    case standard = 0
    case enhanced = 1
    case premium = 2

    public static func < (lhs: VoiceQuality, rhs: VoiceQuality) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

public struct FrenchVoiceCandidate: Sendable, Equatable {
    public let identifier: String
    public let name: String
    public let language: String
    public let quality: VoiceQuality

    public init(identifier: String, name: String, language: String, quality: VoiceQuality) {
        self.identifier = identifier
        self.name = name
        self.language = language
        self.quality = quality
    }
}

/**
 * Port of `voiceScore` / `pickBestFr`, in iOS terms.
 *
 * The web scores NAMES because the browser gives it nothing better:
 * `/enhanced|premium|neural|siri/ → +5`, a `+3` list of French voice names
 * (Amélie, Thomas, Aurélie…), `−5` for `compact|espeak`. Every one of those is a
 * proxy for the same question — "is this the good download or the tinny
 * built-in?" — and on iOS `AVSpeechSynthesisVoice.quality` answers it directly.
 * So the name heuristics disappear and the RANKING is preserved:
 *
 * ```
 * +5  premium        (web: /enhanced|premium|neural|siri/ → +5)
 * +4  enhanced
 * +1  language is fr-FR  (web: /^fr-FR/ → +1, France French for a 6yo in fr)
 * ```
 *
 * `localService === false → +2` is not ported (see the file header).
 */
public enum FrenchVoicePicker {

    /// `/fr($|[-_])/i` — `fr`, `fr-FR`, `fr_CA`. Not `fry`, not `frr`.
    public static func isFrench(_ language: String) -> Bool {
        let lower = language.lowercased()
        guard lower.hasPrefix("fr") else { return false }
        if lower.count == 2 { return true }
        let third = lower[lower.index(lower.startIndex, offsetBy: 2)]
        return third == "-" || third == "_"
    }

    /// `/^fr-FR/i`.
    public static func isFranceFrench(_ language: String) -> Bool {
        language.lowercased().hasPrefix("fr-fr") || language.lowercased().hasPrefix("fr_fr")
    }

    public static func score(_ candidate: FrenchVoiceCandidate) -> Int {
        var score = 0
        switch candidate.quality {
        case .premium: score += 5
        case .enhanced: score += 4
        case .standard: break
        }
        if isFranceFrench(candidate.language) { score += 1 }
        return score
    }

    /// Highest score wins; `nil` when nothing French is installed, in which case
    /// the caller falls back to `AVSpeechSynthesisVoice(language: "fr-FR")`.
    ///
    /// Ties keep the inventory's own order. `Array.prototype.sort` is stable in
    /// every engine the PWA runs on and `sorted(by:)` in Swift is not, so the
    /// index is the tiebreak — otherwise the chosen voice could differ between
    /// two launches with the same inventory.
    public static func pickBest(_ candidates: [FrenchVoiceCandidate]) -> FrenchVoiceCandidate? {
        let french = candidates.filter { isFrench($0.language) }
        guard !french.isEmpty else { return nil }
        return french.enumerated()
            .max { lhs, rhs in
                let a = score(lhs.element)
                let b = score(rhs.element)
                return a != b ? a < b : lhs.offset > rhs.offset
            }?
            .element
    }
}

/**
 * `u.rate` / `u.pitch` are NOT the same scale as the web's.
 *
 * `SpeechSynthesisUtterance.rate` is 1.0-is-normal;
 * `AVSpeechUtterance.rate` is a 0…1 dial whose *default* is 0.5. Assigning the
 * web's `0.94` straight across would produce near-maximum gabble — the single
 * most likely way to get this file wrong.
 */
public enum SpeechRate {

    public static func avRate(for webRate: Double) -> Float {
        let scaled = AVSpeechUtteranceDefaultSpeechRate * Float(webRate)
        return min(max(scaled, AVSpeechUtteranceMinimumSpeechRate), AVSpeechUtteranceMaximumSpeechRate)
    }

    /// Both scales are 1.0-is-normal; AVSpeech accepts 0.5…2.0, the web 0…2.
    public static func avPitch(for webPitch: Double) -> Float {
        min(max(Float(webPitch), 0.5), 2.0)
    }
}

/// `SpeechPlayback` over `AVSpeechSynthesizer`.
@MainActor
public final class SpeechFallback: NSObject, SpeechPlayback, AVSpeechSynthesizerDelegate {

    private let synthesizer = AVSpeechSynthesizer()
    private var handler: ((Bool) -> Void)?
    private var live: AVSpeechUtterance?
    private var resolvedVoice: AVSpeechSynthesisVoice??  // outer: resolved yet; inner: found one

    public override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// The web warms the engine in `unlock()` with a silent `" "` utterance to
    /// satisfy the autoplay policy. iOS has no such requirement, so that is
    /// dropped; the voice lookup is what is worth doing early.
    public func prewarm() {
        _ = voice()
    }

    public func speak(_ text: String, rate: Double, pitch: Double, onEnd: @escaping (Bool) -> Void) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = SpeechRate.avRate(for: rate)
        utterance.pitchMultiplier = SpeechRate.avPitch(for: pitch)
        utterance.voice = voice()
        handler = onEnd
        live = utterance
        synthesizer.speak(utterance)
    }

    public func cancel() {
        // Detach BEFORE stopping: `stopSpeaking` synthesises a `didCancel`, and
        // the channel has already settled this line. Same ordering the TS gets
        // from nulling `pendingRef` before `speechSynthesis.cancel()`.
        handler = nil
        live = nil
        if synthesizer.isSpeaking || synthesizer.isPaused {
            synthesizer.stopSpeaking(at: .immediate)
        }
    }

    private func voice() -> AVSpeechSynthesisVoice? {
        if let cached = resolvedVoice { return cached }
        // `voiceschanged` is not ported: `speechVoices()` is stable per launch,
        // and the language is fixed `fr`.
        let candidates = AVSpeechSynthesisVoice.speechVoices().map { voice in
            FrenchVoiceCandidate(
                identifier: voice.identifier,
                name: voice.name,
                language: voice.language,
                quality: VoiceQuality(rawValue: voice.quality.rawValue - 1) ?? .standard
            )
        }
        let picked = FrenchVoicePicker.pickBest(candidates)
        let resolved = picked.flatMap { AVSpeechSynthesisVoice(identifier: $0.identifier) }
            ?? AVSpeechSynthesisVoice(language: "fr-FR")
        resolvedVoice = .some(resolved)
        return resolved
    }

    // MARK: - AVSpeechSynthesizerDelegate

    public nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didFinish utterance: AVSpeechUtterance
    ) {
        finish(utterance, ok: true)
    }

    public nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didCancel utterance: AVSpeechUtterance
    ) {
        finish(utterance, ok: false)
    }

    /// The utterance identity, not the utterance: `AVSpeechUtterance` is not
    /// `Sendable`, and all we need across the hop is "was this the line we are
    /// still waiting on?".
    private nonisolated func finish(_ utterance: AVSpeechUtterance, ok: Bool) {
        let identity = ObjectIdentifier(utterance)
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard let live = self.live, ObjectIdentifier(live) == identity,
                      let handler = self.handler
                else { return }
                self.handler = nil
                self.live = nil
                handler(ok)
            }
        }
    }
}
