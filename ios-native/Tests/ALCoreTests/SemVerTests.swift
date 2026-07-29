import Testing

@testable import ALCore

// 1:1 port of `src/updates.test.ts` (money.md §6.5).
//
// The native-version guard is the one piece of the update path that can brick a
// tablet: content calling a feature the installed binary lacks is a white screen
// with no way back. It is also the only piece testable without a device.

@Suite("versionAtLeast — the native compatibility guard")
struct SemVerTests {

    @Test("accepts an exact match")
    func exactMatch() {
        #expect(versionAtLeast("1.3.0", "1.3.0"))
    }

    @Test("accepts a newer shell")
    func newerShell() {
        #expect(versionAtLeast("1.4.0", "1.3.0"))
        #expect(versionAtLeast("2.0.0", "1.9.9"))
        #expect(versionAtLeast("1.3.1", "1.3.0"))
    }

    @Test("refuses an older shell")
    func olderShell() {
        #expect(!versionAtLeast("1.2.9", "1.3.0"))
        #expect(!versionAtLeast("0.9.0", "1.0.0"))
    }

    @Test("compares numerically, not as strings")
    func numericNotLexical() {
        // The classic bug: "1.10.0" < "1.9.0" under lexical comparison.
        #expect(versionAtLeast("1.10.0", "1.9.0"))
        #expect(!versionAtLeast("1.9.0", "1.10.0"))
    }

    @Test("treats missing segments as zero")
    func missingSegmentsAreZero() {
        #expect(versionAtLeast("1.3", "1.3.0"))
        #expect(versionAtLeast("2", "1.9.9"))
        #expect(!versionAtLeast("1.3", "1.3.1"))
    }

    /// money.md §3.6 / R12. `Number("x")` is `NaN`; `NaN !== a` is true and
    /// `NaN > a` is false, so the TS bails out **false** at the first non-numeric
    /// segment on either side. `Int("x") ?? 0` would map it to zero and let the
    /// loop continue — the other branch. There is no TS test for this and no real
    /// manifest will contain one; it is pinned so nobody silently flips it.
    @Test("a non-numeric segment is neither equal nor greater — it loses, on either side")
    func nonNumericSegmentLoses() {
        #expect(!versionAtLeast("1.x.0", "1.0.0"))
        #expect(!versionAtLeast("1.0.0", "1.x.0"))
        #expect(!versionAtLeast("1.x.0", "1.x.0"))
        // …but only from the segment where it appears: earlier segments still decide.
        #expect(versionAtLeast("2.x.0", "1.0.0"))
        #expect(!versionAtLeast("0.x.0", "1.0.0"))
    }

    /// `Number("")` is `0`, not `NaN`, so an empty segment is a zero.
    @Test("an empty segment is zero, as Number(\"\") is")
    func emptySegmentIsZero() {
        #expect(versionAtLeast("1..0", "1.0.0"))
        #expect(versionAtLeast("", "0"))
        #expect(!versionAtLeast("", "0.0.1"))
    }
}
