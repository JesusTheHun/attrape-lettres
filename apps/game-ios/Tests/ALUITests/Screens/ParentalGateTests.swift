import Foundation
import Testing

import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* `src/components/ParentalGate.tsx`, asserted against the TypeScript.          */
/*                                                                             */
/* This file exists because `ParentalGate.swift` shipped in the previous run    */
/* with no test at all. Every expected value below was read out of the TSX, not */
/* out of the Swift — the whole hazard with testing already-written code is     */
/* copying its constants across and proving that a number equals itself.        */
/*                                                                             */
/* The TypeScript, in full, for the parts that have behaviour:                  */
/*                                                                             */
/* ```tsx                                                                       */
/* function roll(): [number, number] {                                          */
/*   const d = () => 3 + Math.floor(Math.random() * 7);                         */
/*   return [d(), d()];                                                         */
/* }                                                                            */
/*                                                                             */
/* const [[a, b]] = useState(roll);                                             */
/* const answer = useMemo(() => a * b, [a, b]);                                 */
/*                                                                              */
/* const submit = (e) => {                                                      */
/*   e.preventDefault();                                                        */
/*   if (Number(value) === answer) return onPass();                             */
/*   setWrong(true);                                                            */
/*   setValue("");                                                              */
/*   inputRef.current?.focus();                                                 */
/* };                                                                           */
/*                                                                              */
/* onChange={(e) => {                                                           */
/*   setValue(e.target.value.replace(/\D/g, "").slice(0, 3));                    */
/*   setWrong(false);                                                           */
/* }}                                                                           */
/*                                                                              */
/* <button type="submit" disabled={value.length === 0}>Continuer</button>       */
/* ```                                                                          */
/* -------------------------------------------------------------------------- */

// MARK: - The roll

@Suite("ParentalGate — the challenge")
struct GateChallengeTests {

    /// `3 + Math.floor(Math.random() * 7)` — the operand bounds, written out of
    /// the TypeScript rather than read off the Swift constants.
    private static let tsLowest = 3
    private static let tsHighest = 9

    @Test("the authored range is 3…9, i.e. lowest 3 over a spread of 7")
    func authoredRange() {
        // `3 + floor(random() * 7)` yields 3, 4, 5, 6, 7, 8, 9 — seven values.
        #expect(GateChallenge.lowestOperand == Self.tsLowest)
        #expect(GateChallenge.operandSpread == Self.tsHighest - Self.tsLowest + 1)
    }

    @Test("every seeded roll stays inside 3…9 on both operands")
    func seededRollsStayInRange() {
        for seed in UInt64(1)...UInt64(400) {
            let c = GateChallenge.roll(using: .seeded(seed))
            #expect(
                c.a >= Self.tsLowest && c.a <= Self.tsHighest,
                Comment(rawValue: "seed \(seed): a = \(c.a) is outside 3…9"))
            #expect(
                c.b >= Self.tsLowest && c.b <= Self.tsHighest,
                Comment(rawValue: "seed \(seed): b = \(c.b) is outside 3…9"))
        }
    }

    @Test("the whole of 3…9 is reachable — the spread is 7, not something narrower")
    func wholeRangeIsReachable() {
        var seen = Set<Int>()
        for seed in UInt64(1)...UInt64(400) {
            let c = GateChallenge.roll(using: .seeded(seed))
            seen.insert(c.a)
            seen.insert(c.b)
        }
        #expect(seen == Set(Self.tsLowest...Self.tsHighest))
    }

    @Test("answer is the product, and the product is never a trivial ×1 or ×2")
    func answerIsTheProduct() {
        // TSX: `const answer = useMemo(() => a * b, [a, b])`, and the comment
        // "product 9..81, never a trivial ×1 or ×2".
        #expect(GateChallenge(a: 3, b: 3).answer == 9)
        #expect(GateChallenge(a: 9, b: 9).answer == 81)
        #expect(GateChallenge(a: 7, b: 4).answer == 28)
        for seed in UInt64(1)...UInt64(200) {
            let c = GateChallenge.roll(using: .seeded(seed))
            #expect(c.answer == c.a * c.b)
            #expect(c.answer >= 9 && c.answer <= 81)
        }
    }

    @Test("the biggest possible answer still fits the field's three digits")
    func maximumAnswerFitsTheField() {
        // `.slice(0, 3)` must never make a correct answer untypeable: 9 × 9 = 81.
        #expect(String(GateChallenge(a: 9, b: 9).answer).count <= 3)
    }

    @Test("the question reads « Combien font a × b ? » with the operands in order")
    func questionText() {
        // U+00D7 MULTIPLICATION SIGN, not the letter x.
        #expect(GateChallenge(a: 7, b: 4).question == "Combien font 7 \u{00D7} 4 ?")
        #expect(GateChallenge(a: 4, b: 7).question == "Combien font 4 \u{00D7} 7 ?")
    }

    @Test("the gate re-rolls on every open, so watching once teaches nothing")
    func rerollsPerOpen() {
        // `useState(roll)` is per mount; two mounts are two independent draws.
        // Asserted through the seam the app uses: different sources, different
        // challenges (over a sample, not for any single pair).
        let rolls = (UInt64(1)...UInt64(60)).map { GateChallenge.roll(using: .seeded($0)) }
        #expect(Set(rolls.map { "\($0.a)x\($0.b)" }).count > 1)
    }
}

// MARK: - The input filter

@Suite("ParentalGate — sanitizeGateInput")
struct SanitizeGateInputTests {

    /// `e.target.value.replace(/\D/g, "").slice(0, 3)`.
    ///
    /// `\D` without the `u` flag matches per UTF-16 CODE UNIT: anything that is
    /// not U+0030…U+0039 is deleted, including every non-ASCII digit and every
    /// combining mark. `.slice(0, 3)` then counts code units, and after the
    /// filter every survivor is one unit wide.
    @Test("ASCII digits survive, in order")
    func keepsDigits() {
        #expect(sanitizeGateInput("42") == "42")
        #expect(sanitizeGateInput("0") == "0")
        #expect(sanitizeGateInput("81") == "81")
    }

    @Test("everything that is not 0-9 is deleted")
    func stripsNonDigits() {
        #expect(sanitizeGateInput("4a2") == "42")
        #expect(sanitizeGateInput(" 7 ") == "7")
        #expect(sanitizeGateInput("-5") == "5")          // the minus is \D
        #expect(sanitizeGateInput("3.5") == "35")        // the point is \D
        #expect(sanitizeGateInput("1e3") == "13")        // JS would parse 1e3; the field never sees it
        #expect(sanitizeGateInput("abc") == "")
        #expect(sanitizeGateInput("") == "")
        #expect(sanitizeGateInput("   ") == "")
    }

    @Test("non-ASCII digits are deleted too — \\D is not Unicode-aware")
    func stripsNonASCIIDigits() {
        // Arabic-Indic ٤٢ and full-width ４２ are both `\D` as far as the PWA's
        // regex is concerned, so the web field never accepted them.
        #expect(sanitizeGateInput("\u{0664}\u{0662}") == "")
        #expect(sanitizeGateInput("\u{FF14}\u{FF12}") == "")
        #expect(sanitizeGateInput("\u{0661}2\u{0663}") == "2")
        // Superscript two: `Character.isNumber` is true for it, `\D` is not.
        #expect(sanitizeGateInput("\u{00B2}") == "")
    }

    @Test("an ASCII digit carrying a combining mark still yields its digit")
    func keepsDigitsInsideGraphemeClusters() {
        // "3" + COMBINING ACUTE is ONE Swift Character but TWO UTF-16 units;
        // JS keeps the "3" and deletes the mark. A grapheme-cluster filter drops
        // the whole cluster and answers "", which the web never does.
        #expect(sanitizeGateInput("3\u{0301}") == "3")
        // The keycap sequence "1️⃣" is U+0031 U+FE0F U+20E3 — JS keeps the 1.
        #expect(sanitizeGateInput("1\u{FE0F}\u{20E3}") == "1")
    }

    @Test("the field is capped at three digits, counted after the filter")
    func capsAtThree() {
        #expect(sanitizeGateInput("12345") == "123")
        // `.replace` runs BEFORE `.slice`, so junk does not eat the budget.
        #expect(sanitizeGateInput("a1b2c3d4") == "123")
        #expect(sanitizeGateInput("999") == "999")
    }
}

// MARK: - The state machine

@Suite("ParentalGate — the model")
@MainActor
struct ParentalGateModelTests {

    /// 7 × 4 = 28 — the example the TSX's own copy uses.
    private func model() -> ParentalGateModel {
        ParentalGateModel(challenge: GateChallenge(a: 7, b: 4))
    }

    @Test("« Continuer » is disabled exactly while the field is empty")
    func canSubmit() {
        // `disabled={value.length === 0}`.
        let m = model()
        #expect(m.canSubmit == false)
        m.type("2")
        #expect(m.canSubmit == true)
        m.type("")
        #expect(m.canSubmit == false)
    }

    @Test("typing sanitises and clears the error line")
    func typingClearsTheError() {
        // `onChange` does BOTH: `setValue(sanitised)` and `setWrong(false)`.
        let m = model()
        _ = m.submit()  // empty ≠ 28 → wrong
        #expect(m.wrong == true)
        m.type("2x")
        #expect(m.value == "2")
        #expect(m.wrong == false)
    }

    @Test("the right answer passes and changes nothing else")
    func rightAnswerPasses() {
        // `if (Number(value) === answer) return onPass();` — an early return, so
        // neither `wrong` nor `value` is touched on the way out.
        let m = model()
        m.type("28")
        #expect(m.submit() == true)
        #expect(m.wrong == false)
        #expect(m.value == "28")
    }

    @Test("leading zeros parse, exactly as Number(\"028\") does")
    func leadingZeros() {
        let m = model()
        m.type("028")
        #expect(m.submit() == true)
    }

    @Test("an empty field is not the answer — JS Number(\"\") is 0 and 0 is never a product here")
    func emptyFieldFails() {
        // The button is disabled, but Enter still submits the form. `Number("")`
        // is `0`; the product is at least 9, so it can never match. The Swift
        // `Int("")` is nil and fails the same test for the same outcome.
        let m = model()
        #expect(m.submit() == false)
        #expect(m.wrong == true)
        #expect(m.value == "")
    }

    @Test("a wrong answer clears the field, warms the border, and locks NOTHING")
    func wrongAnswerCostsOneRetry() {
        // Invariant 3: there is no fail state. Invariant 11: nothing here can
        // resolve to "locked" — the model has no attempt counter, no lockout,
        // and no persistence to hold one in.
        let m = model()
        m.type("27")
        #expect(m.submit() == false)
        #expect(m.wrong == true)
        #expect(m.value == "")
        #expect(m.canSubmit == false)  // because the field is empty, not because it is locked

        // …and the very next attempt is a full attempt.
        m.type("28")
        #expect(m.canSubmit == true)
        #expect(m.submit() == true)
    }

    @Test("retries are unlimited — twenty wrong answers still leave the right one working")
    func retriesAreUnlimited() {
        let m = model()
        for _ in 0..<20 {
            m.type("11")
            #expect(m.submit() == false)
        }
        m.type("28")
        #expect(m.submit() == true)
    }

    @Test("the challenge never changes under the adult's hands")
    func challengeIsStable() {
        // `useState(roll)` — rolled once per mount. A model that re-rolled after
        // a miss would make the gate unpassable for a parent reading it aloud.
        let m = model()
        let asked = m.challenge
        m.type("11")
        _ = m.submit()
        m.type("28")
        _ = m.submit()
        #expect(m.challenge == asked)
        #expect(m.challenge.question == "Combien font 7 \u{00D7} 4 ?")
    }
}

// MARK: - Invariant 11, structurally

@Suite("ParentalGate — persists nothing (invariant 11)")
struct ParentalGateSourceScanTests {

    private static let source: URL =
        URL(fileURLWithPath: #filePath)  // …/Tests/ALUITests/Screens/ParentalGateTests.swift
        .deletingLastPathComponent()  // …/Tests/ALUITests/Screens
        .deletingLastPathComponent()  // …/Tests/ALUITests
        .deletingLastPathComponent()  // …/Tests
        .deletingLastPathComponent()  // …/apps/game-ios
        .appendingPathComponent("Sources/ALUI/Screens/ParentalGate.swift")

    @Test("the scan can find the file it is meant to scan")
    func fileExists() {
        #expect(FileManager.default.fileExists(atPath: Self.source.path))
    }

    /// The TSX reads and writes no storage anywhere in the file: the challenge
    /// lives in `useState`, so passing once buys nothing and a child who watched
    /// an adult type 28 gets a different sum next time. A "gate passed" flag
    /// would be a behaviour change AND a security regression.
    @Test("the gate touches no storage, no license and no entitlement")
    func noPersistence() throws {
        let text = try String(contentsOf: Self.source, encoding: .utf8)
        // Strip the comment banner: the header discusses persistence at length,
        // and this scan is about code.
        let code = text.split(separator: "\n")
            .filter { line in
                let t = line.trimmingCharacters(in: .whitespaces)
                return !t.hasPrefix("//") && !t.hasPrefix("/*") && !t.hasPrefix("*")
                    && !t.hasPrefix("///")
            }
            .joined(separator: "\n")
        for forbidden in [
            "KVStore", "UserDefaults", "LicenseStore", "EntitlementModel", "ProfileStore",
            "Telemetry",
        ] {
            #expect(
                !code.contains(forbidden),
                Comment(rawValue: "ParentalGate.swift names \(forbidden); the gate must hold no state"))
        }
    }
}
