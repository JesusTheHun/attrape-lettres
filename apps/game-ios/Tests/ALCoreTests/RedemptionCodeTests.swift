import Foundation
import Testing

@testable import ALCore

/* -------------------------------------------------------------------------- */
/* The code format — the Swift half of a two-implementation contract.          */
/*                                                                             */
/* The other half is `services/api/src/codes/code.ts`, and the VECTORS below    */
/* are duplicated verbatim in `services/api/test/codes.test.ts`. They are       */
/* copied rather than shared because there is no package between Swift and      */
/* TypeScript here, and inventing one for seven strings would be worse than the */
/* duplication.                                                                 */
/*                                                                             */
/* What drift costs: every code we mint is refused on the device before it ever */
/* reaches the network. No log line on either side, no failed request to count, */
/* nothing in the store's numbers. Just "the codes don't work".                 */
/* -------------------------------------------------------------------------- */

/// SHARED WITH `services/api/test/codes.test.ts`.
private let vectors: [(String, Character)] = [
    ("00000000000", "0"),
    ("00000000001", "B"),
    ("10000000000", "1"),
    ("ZZZZZZZZZZZ", "Y"),
    ("7FQ4M2XB9KD", "B"),
    ("ABCDEFGHJKM", "C"),
    ("0123456789A", "R"),
]

/// A valid code built the way `mint` does: eleven payload symbols, then the
/// check symbol for them.
private func code(_ payload: String) -> String {
    payload + String(RedemptionCode.checkSymbol(payload)!)
}

@Suite("The redemption code format")
struct RedemptionCodeFormatTests {

    @Test("Crockford base32 — 32 symbols, no I, L, O or U")
    func alphabet() {
        #expect(RedemptionCode.alphabet.count == 32)
        #expect(Set(RedemptionCode.alphabet).count == 32)
        for banned: Character in ["I", "L", "O", "U"] {
            #expect(!RedemptionCode.alphabet.contains(banned))
        }
    }

    @Test("the check symbols the API produces")
    func checksumVectors() {
        for (payload, expected) in vectors {
            #expect(payload.count == RedemptionCode.payloadLength)
            #expect(RedemptionCode.checkSymbol(payload) == expected)
        }
    }

    @Test("the confusable characters are mapped, not rejected")
    func confusables() throws {
        let valid = code("0123456789A")
        // Someone reading « O » for « 0 », and « I » or « l » for « 1 ».
        let misread = valid.replacingOccurrences(of: "0", with: "O")
            .replacingOccurrences(of: "1", with: "I")
        #expect(RedemptionCode.normalise(misread) == valid)
        #expect(RedemptionCode.accept(misread) == valid)
    }

    @Test("separators and case are decoration")
    func separators() {
        let valid = code("7FQ4M2XB9KD")
        #expect(RedemptionCode.normalise(RedemptionCode.format(valid)) == valid)
        #expect(RedemptionCode.normalise(valid.lowercased()) == valid)
        #expect(RedemptionCode.normalise(" 7FQ4 M2XB\t9KDB ") == valid)
    }

    @Test("it prints as XXXX-XXXX-XXXX")
    func formatting() {
        #expect(RedemptionCode.format("7FQ4M2XB9KDB") == "7FQ4-M2XB-9KDB")
    }

    @Test("a wrong length is not a code")
    func length() {
        #expect(RedemptionCode.normalise("7FQ4M2XB9KD") == nil)
        #expect(RedemptionCode.normalise("7FQ4M2XB9KDBB") == nil)
        #expect(RedemptionCode.normalise("") == nil)
    }

    @Test("every single-symbol typo fails the checksum")
    func singleSymbolTypos() {
        let valid = code("7FQ4M2XB9KD")
        for index in valid.indices {
            let current = valid[index]
            let position = RedemptionCode.alphabet.firstIndex(of: current)!
            let wrong = RedemptionCode.alphabet[(position + 1) % RedemptionCode.alphabet.count]
            var typo = valid
            typo.replaceSubrange(index...index, with: String(wrong))
            #expect(!RedemptionCode.isWellFormed(typo), "\(typo) passed the checksum")
        }
    }

    @Test("swapping two adjacent different symbols fails the checksum")
    func transpositions() {
        let valid = code("0123456789A")
        var characters = Array(valid)
        for i in 0..<(characters.count - 1) where characters[i] != characters[i + 1] {
            characters.swapAt(i, i + 1)
            #expect(!RedemptionCode.isWellFormed(String(characters)))
            characters.swapAt(i, i + 1)
        }
    }

    @Test("accept is normalise plus the checksum, and nothing else")
    func accept() {
        let valid = code("ABCDEFGHJKM")
        #expect(RedemptionCode.accept(valid) == valid)
        // Right length, right alphabet, wrong check symbol.
        let wrongCheck = String(valid.dropLast()) + (valid.last == "0" ? "1" : "0")
        #expect(RedemptionCode.normalise(wrongCheck) != nil)
        #expect(RedemptionCode.accept(wrongCheck) == nil)
    }
}

@Suite("What the field may hold as a parent types")
struct RedemptionCodeInputTests {

    @Test("hyphens appear every four symbols, on their own")
    func hyphenation() {
        #expect(RedemptionCode.sanitiseInput("7fq4m2xb9kdb") == "7FQ4-M2XB-9KDB")
        #expect(RedemptionCode.sanitiseInput("7FQ4-M2XB-9KDB") == "7FQ4-M2XB-9KDB")
        #expect(RedemptionCode.sanitiseInput("7") == "7")
        #expect(RedemptionCode.sanitiseInput("7FQ4") == "7FQ4")
        #expect(RedemptionCode.sanitiseInput("7FQ4M") == "7FQ4-M")
    }

    @Test("it stops at twelve symbols, whatever is pasted")
    func capped() {
        let long = RedemptionCode.sanitiseInput(String(repeating: "7", count: 40))
        #expect(long.filter { $0 != "-" }.count == RedemptionCode.length)
    }

    @Test("anything a code cannot contain never reaches the field")
    func rejects() {
        // Accents, emoji, punctuation, and `U` — which is not in the alphabet
        // and deliberately not mapped.
        #expect(RedemptionCode.sanitiseInput("é🎉 ?!") == "")
        #expect(RedemptionCode.sanitiseInput("UUUU") == "")
        // …but the mapped confusables do, as their canonical form.
        #expect(RedemptionCode.sanitiseInput("oil") == "011")
    }

    @Test("what the field holds is always normalisable once it is long enough")
    func fieldRoundTrips() {
        let valid = code("MNPQRSTVWXY")
        let typed = RedemptionCode.sanitiseInput(valid.lowercased())
        #expect(RedemptionCode.normalise(typed) == valid)
    }
}
