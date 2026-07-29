import ALCore
import SwiftUI
import Testing

@testable import ALUI

@Suite("Typography — the ramp and the letter forms")
struct TypographyTests {

    /// The Tailwind classes the shell actually uses, with their computed px.
    /// Read off the scale, not out of Typography.swift.
    @Test("the size ramp is the computed Tailwind scale")
    func sizes() {
        #expect(Typography.Size.xxs == 11)   // text-[11px]
        #expect(Typography.Size.xs == 12)    // text-xs
        #expect(Typography.Size.sm == 14)    // text-sm
        #expect(Typography.Size.base == 16)  // text-base
        #expect(Typography.Size.lg == 18)    // text-lg
        #expect(Typography.Size.xl == 20)    // text-xl
        #expect(Typography.Size.xxl == 24)   // text-2xl
    }

    /// The trap: CSS `font-extrabold` is 800 and `font-black` is 900. SwiftUI
    /// calls 800 `.heavy` and 900 `.black`, so mapping `extrabold` to `.black`
    /// reads plausibly and makes every "Suivant"/"Boutique" button too heavy.
    @Test("extrabold is .heavy, not .black")
    func weights() {
        #expect(Typography.Weight.semibold == .semibold)
        #expect(Typography.Weight.bold == .bold)
        #expect(Typography.Weight.extrabold == .heavy)
        #expect(Typography.Weight.black == .black)
        #expect(Typography.Weight.extrabold != Typography.Weight.black)
    }

    @Test("the CSS stacks are recorded verbatim")
    func stacks() {
        #expect(Typography.cssFontStack == "ui-rounded,'SF Pro Rounded',system-ui,sans-serif")
        #expect(
            Typography.cssCursiveStack
                == "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive"
        )
        #expect(
            Typography.cursiveFamilies
                == ["Snell Roundhand", "Apple Chancery", "Segoe Script", "Bradley Hand"]
        )
    }

    /// CSS line-height is the whole line box; SwiftUI's lineSpacing is the gap
    /// between lines. `leading-snug` (1.375) at 16 px is 22 px of line box, i.e.
    /// 6 pt of extra gap.
    @Test("line-height ratios convert to extra spacing")
    func lineHeights() {
        #expect(Typography.LineHeight.none == 1.0)
        #expect(Typography.LineHeight.tight == 1.25)
        #expect(Typography.LineHeight.snug == 1.375)

        #expect(Typography.lineSpacing(size: 16, ratio: Typography.LineHeight.snug) == 6)
        #expect(Typography.lineSpacing(size: 14, ratio: Typography.LineHeight.snug) == 5.25)
        #expect(Typography.lineSpacing(size: 16, ratio: Typography.LineHeight.tight) == 4)
        // leading-none adds nothing, and a ratio below 1 must not go negative.
        #expect(Typography.lineSpacing(size: 40, ratio: Typography.LineHeight.none) == 0)
        #expect(Typography.lineSpacing(size: 40, ratio: 0.5) == 0)
    }

    @Test("the app font is SF Pro Rounded at a fixed size")
    func roundedFont() {
        #expect(Typography.rounded(24, .black) == .system(size: 24, weight: .black, design: .rounded))
        #expect(Typography.rounded(24, .black) != .system(size: 24, weight: .black))
        #expect(Typography.rounded(24, .black) != Typography.rounded(24, .heavy))
    }

    /* ---- Letter forms ----------------------------------------------------- */

    /// A printed letter is drawn in the same rounded face as everything else —
    /// deliberately, so the letter on a tile is the letter the child sees in the
    /// hub. `SCRIPT_FONT.print` is that same stack.
    @Test("print letters use the app font")
    func printLetters() {
        #expect(Typography.letterFont(.print, size: 48) == Typography.rounded(48, .black))
        #expect(Typography.letterFont(.print, size: 48, weight: .bold) == Typography.rounded(48, .bold))
    }

    /// The stack is tried in CSS order, and Snell Roundhand — the first entry —
    /// ships on both iOS and macOS. If the candidate order were wrong, Bradley
    /// Hand (also present on macOS) would win and the letters would be printed,
    /// not joined: the "attachée" exercises would teach the wrong shape.
    @Test("cursive resolves to a Snell Roundhand face, not a later fallback")
    func cursiveResolution() throws {
        let name = try #require(
            Typography.resolvedCursiveFontName,
            Comment(rawValue: "no cursive face found on this platform")
        )
        #expect(
            name.hasPrefix("SnellRoundhand") || name == "Snell Roundhand",
            Comment(rawValue: "resolved \(name), expected the first family in the CSS stack")
        )
        // CSS asks for weight 900 on the tile, so the heaviest Snell face wins.
        #expect(name == "SnellRoundhand-Black")
    }

    @Test("a cursive letter is not drawn in the print face")
    func cursiveDiffersFromPrint() {
        #expect(Typography.letterFont(.cursive, size: 48) != Typography.letterFont(.print, size: 48))
    }

    /// `faceLabel` belongs to ALCore and is NOT re-implemented here. Asserted so
    /// a future refactor cannot quietly grow a second copy in the view layer.
    @Test("the letter's screen-reader label still comes from ALCore")
    func faceLabelIsALCores() {
        #expect(
            faceLabel(LetterFace(base: "A", glyph: "a", script: .cursive))
                == "Lettre A minuscule attachée"
        )
        #expect(
            faceLabel(LetterFace(base: "A", glyph: "A", script: .print))
                == "Lettre A majuscule"
        )
    }
}
