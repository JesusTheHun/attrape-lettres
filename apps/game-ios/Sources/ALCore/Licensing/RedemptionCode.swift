/* -------------------------------------------------------------------------- */
/* What a redemption code is, on the device.                                   */
/*                                                                             */
/* THE SECOND OF TWO IMPLEMENTATIONS. The first is                             */
/* `services/api/src/codes/code.ts`, and the two must agree exactly: this one   */
/* decides whether a code is worth sending, that one decides whether it exists. */
/* If the checksums drift, every code we mint is refused on the device before   */
/* it ever reaches the network — which produces no log line on either side and  */
/* looks precisely like "the codes do not work".                               */
/*                                                                             */
/* `RedemptionCodeTests` carries the same vectors as `test/codes.test.ts`. They */
/* are duplicated rather than shared because there is no package between Swift  */
/* and TypeScript here and inventing one for seven strings would be worse.     */
/*                                                                             */
/* ── Why the device validates at all. ─────────────────────────────────────────*/
/*                                                                             */
/* A parent is typing twelve characters off a card, one-handed, with a child    */
/* leaning on their arm. The checksum turns the commonest mistakes — one wrong  */
/* symbol, two swapped — into an instant answer instead of a round trip and a   */
/* server refusal that reads as "your code is no good". It also means 31 of     */
/* every 32 malformed guesses never reach the store, which is the cheap half of */
/* the brute-force defence (`code.ts` has the arithmetic).                      */
/* -------------------------------------------------------------------------- */

public enum RedemptionCode {

    /// Crockford base32: no I, no L, no O, no U.
    public static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// Eleven payload symbols plus one check symbol.
    public static let payloadLength = 11
    public static let length = payloadLength + 1

    /// Crockford's confusion map. `U` is left out of the alphabet and is
    /// deliberately NOT mapped — mapping it to `V` would silently turn one
    /// valid-looking code into a different valid one.
    private static let confusable: [Character: Character] = ["O": "0", "I": "1", "L": "1"]

    /**
     * Anything a parent typed → the twelve canonical symbols, or nil.
     *
     * Hyphens, spaces and case are decoration. Everything that is not an
     * alphabet symbol after the confusion map is dropped, so `7fq4-m2xb-9kdb`,
     * `7FQ4 M2XB 9KDB` and `7FQ4M2XB9KDB` are one code.
     */
    public static func normalise(_ raw: String) -> String? {
        var out = ""
        out.reserveCapacity(length)
        for character in raw.uppercased() {
            let mapped = confusable[character] ?? character
            if alphabet.contains(mapped) { out.append(mapped) }
        }
        return out.count == length ? out : nil
    }

    /// The check symbol for eleven payload symbols: a position-weighted sum
    /// mod 32. Catches every single-symbol error and every transposition of two
    /// adjacent different symbols.
    public static func checkSymbol(_ payload: some StringProtocol) -> Character? {
        var sum = 0
        for (offset, character) in payload.enumerated() {
            guard let value = alphabet.firstIndex(of: character) else { return nil }
            sum += (offset + 1) * value
        }
        return alphabet[sum % alphabet.count]
    }

    /// Does this normalised code carry its own check symbol?
    public static func isWellFormed(_ normalised: String) -> Bool {
        guard normalised.count == length else { return false }
        let payload = normalised.prefix(payloadLength)
        guard let expected = checkSymbol(payload) else { return false }
        return normalised.last == expected
    }

    /// One call: normalise, then verify. nil means "do not send this".
    public static func accept(_ raw: String) -> String? {
        guard let normalised = normalise(raw), isWellFormed(normalised) else { return nil }
        return normalised
    }

    /// `XXXX-XXXX-XXXX`, for display. Purely cosmetic; nothing parses it.
    public static func format(_ normalised: String) -> String {
        var out = ""
        for (offset, character) in normalised.enumerated() {
            if offset > 0 && offset % 4 == 0 { out.append("-") }
            out.append(character)
        }
        return out
    }

    /// What a text field may keep as the parent types: alphabet symbols and the
    /// hyphens we print, capped at the length of a formatted code. The
    /// counterpart of `sanitizeGateInput` — the field never holds anything a
    /// code could not contain.
    public static func sanitiseInput(_ raw: String) -> String {
        var out = ""
        var symbols = 0
        for character in raw.uppercased() {
            let mapped = confusable[character] ?? character
            if alphabet.contains(mapped) {
                if symbols > 0 && symbols % 4 == 0 { out.append("-") }
                out.append(mapped)
                symbols += 1
                if symbols == length { break }
            }
        }
        return out
    }
}
