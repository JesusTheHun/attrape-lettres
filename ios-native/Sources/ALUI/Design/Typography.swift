import ALCore
import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/* -------------------------------------------------------------------------- */
/* The type ramp.                                                              */
/*                                                                             */
/* The PWA sets ONE font family on every screen root —                          */
/*   ui-rounded,'SF Pro Rounded',system-ui,sans-serif                           */
/* — and everything inside inherits it. On Apple platforms that resolves to SF  */
/* Pro Rounded, which SwiftUI reaches natively as `Font.Design.rounded`, so     */
/* there is no font file to ship and no stack to emulate.                      */
/*                                                                             */
/* Sizes are the computed Tailwind numbers, not class names (shell.md §5.1):    */
/* a class is shorthand for a px value and the Swift code carries the value.    */
/* Sizes are FIXED, not Dynamic-Type-scaled: `Font.system(size:)` does not      */
/* scale, and CSS px does not either, so the two agree. The accessibility floor */
/* (invariant 6) is met by tap-target size and labels, exactly as on the web —  */
/* see `Copy.swift` and `Tile`'s 92 pt minimum.                                */
/* -------------------------------------------------------------------------- */

public enum Typography {

    /// The CSS stack every screen root sets. Kept for the record (and for the
    /// pixel-diff harness); the app renders through `Font.Design.rounded`.
    public static let cssFontStack = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif"

    /// `SCRIPT_FONT.cursive` in `letterForms.ts` — the OS handwriting stack.
    /// Zero-dependency by design: no école-cursive webfont is shipped, and the
    /// joined "attaché" shape a French six-year-old learns is close enough.
    public static let cssCursiveStack = "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive"

    /// The cursive stack, in order, as font families a browser would try. Only
    /// the first two exist on Apple platforms; the rest are the Windows/Android
    /// entries, kept so the order is auditable against the CSS.
    public static let cursiveFamilies = [
        "Snell Roundhand",
        "Apple Chancery",
        "Segoe Script",
        "Bradley Hand",
    ]

    /* ---- Sizes ----------------------------------------------------------- */

    /// The Tailwind text scale actually used in this app.
    public enum Size {
        /// `text-[11px]` — the hub's repeat-coin badge.
        public static let xxs: CGFloat = 11
        /// `text-xs` — shop price chips.
        public static let xs: CGFloat = 12
        /// `text-sm` — hints, captions, the gate's prose.
        public static let sm: CGFloat = 14
        /// `text-base` — adult body copy.
        public static let base: CGFloat = 16
        /// `text-lg` — hub chips, "← Menu", section labels.
        public static let lg: CGFloat = 18
        /// `text-xl` — headings, the "🏠 Menu" end button.
        public static let xl: CGFloat = 20
        /// `text-2xl` — level numbers, "🎉 Suivant", the name field.
        public static let xxl: CGFloat = 24
    }

    /* ---- Weights --------------------------------------------------------- */

    /// CSS numeric weights → SwiftUI. `font-black` (900) is the app's default
    /// voice; `extrabold` (800) is SwiftUI's `.heavy`, which is the trap here —
    /// `.black` is 900 and `.heavy` is 800, so `font-extrabold` must NOT become
    /// `.black`.
    public enum Weight {
        public static let semibold: Font.Weight = .semibold   // 600
        public static let bold: Font.Weight = .bold           // 700
        public static let extrabold: Font.Weight = .heavy      // 800
        public static let black: Font.Weight = .black          // 900
    }

    /* ---- Line height ------------------------------------------------------ */

    /// Tailwind `leading-*` multipliers, as CSS ratios.
    public enum LineHeight {
        public static let none: CGFloat = 1.0     // leading-none
        public static let tight: CGFloat = 1.25   // leading-tight
        public static let snug: CGFloat = 1.375   // leading-snug
    }

    /// CSS `line-height` is the TOTAL height of a line box; SwiftUI's
    /// `.lineSpacing` is the EXTRA gap between two lines. The conversion is
    /// therefore `size * (ratio - 1)`, which is exact only when the font's own
    /// natural line height equals its point size. SF Pro's is slightly larger,
    /// so a multi-line paragraph runs a hair looser here than in the browser.
    /// Recorded rather than fudged; single-line copy (the overwhelming majority)
    /// is unaffected.
    public static func lineSpacing(size: CGFloat, ratio: CGFloat) -> CGFloat {
        Swift.max(0, size * (ratio - 1))
    }

    /* ---- Fonts ------------------------------------------------------------ */

    /// The app font: SF Pro Rounded at a fixed point size.
    public static func rounded(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    /// The font a `LetterFace` must be drawn in.
    ///
    /// `print` is the app's rounded sans — the same face as every other glyph,
    /// which is the point: a printed letter on a tile is the letter the child
    /// sees everywhere else. `cursive` falls to the OS handwriting face, and
    /// falls BACK to the rounded sans if the device has none, because a missing
    /// font must never render blank (invariant 3's spirit: nothing about a
    /// letter game may dead-end).
    ///
    /// The letter's screen-reader label is NOT built here — `ALCore.faceLabel(_:)`
    /// owns it and is unit-tested there.
    public static func letterFont(
        _ script: LetterScript,
        size: CGFloat,
        weight: Font.Weight = .black
    ) -> Font {
        switch script {
        case .print:
            return rounded(size, weight)
        case .cursive:
            guard let name = resolvedCursiveFontName else { return rounded(size, weight) }
            // `fixedSize:` — `Font.custom(_:size:)` scales with Dynamic Type by
            // default and `Font.system(size:)` does not, so the printed and
            // cursive forms of the same letter would drift apart at any non-
            // default text size. The web scales neither.
            return .custom(name, fixedSize: size)
        }
    }

    /// The first cursive family the platform actually has, or `nil`.
    /// Resolved once: font lookup hits the font manager, and this is read on the
    /// tile-render path.
    public static let resolvedCursiveFontName: String? = firstAvailableCursiveFont()

    private static func firstAvailableCursiveFont() -> String? {
        // Snell Roundhand ships three faces; CSS `font-weight: 900` on the tile
        // selects the heaviest, so try it before the family name.
        let candidates = ["SnellRoundhand-Black", "SnellRoundhand-Bold"] + cursiveFamilies
        for name in candidates where fontExists(name) { return name }
        return nil
    }

    private static func fontExists(_ name: String) -> Bool {
        #if canImport(UIKit)
        return UIFont(name: name, size: 12) != nil
        #elseif canImport(AppKit)
        return NSFont(name: name, size: 12) != nil
        #else
        return false
        #endif
    }
}
