import CoreGraphics
import Testing

@testable import ALUI

// FitLine — the pure fit rule, asserted against `src/components/FitLine.tsx`:
//
//   const k = natural > 0 && natural > available ? available / natural : 1;
//   inner.style.transform = k < 1 ? `scale(${k})` : "";
//   outer.style.height    = k < 1 ? `${naturalHeight * k}px` : "";
//
// The view itself needs a renderer (shell.md §6); what the host CAN prove is
// the derivation — including every boundary where the TSX changes behaviour —
// and the shape of the "collapse onto the tray" fix: the fit is always derived
// from the NATURAL size, so re-fitting never compounds.

@Suite("FitLineFit — the scale rule and its boundaries")
struct FitLineScaleTests {

    @Test("a row that fits is left at natural size")
    func fitsUntouched() {
        #expect(FitLineFit.scale(natural: 200, available: 320) == 1)
    }

    @Test("natural == available is NOT shrunk — the TSX comparison is strict >")
    func exactFitBoundary() {
        #expect(FitLineFit.scale(natural: 320, available: 320) == 1)
    }

    @Test("one point of overflow starts shrinking: k = available / natural")
    func overflowShrinks() {
        let expected: CGFloat = 320.0 / 321.0 // the TSX formula, computed independently
        #expect(FitLineFit.scale(natural: 321, available: 320) == expected)
        #expect(FitLineFit.scale(natural: 500, available: 320) == 0.64)
    }

    @Test("nothing measured yet (natural 0) → 1, the natural > 0 guard")
    func unmeasuredIsIdentity() {
        #expect(FitLineFit.scale(natural: 0, available: 320) == 1)
    }

    @Test("a zero-width slot scales to zero — the web's scale(0), kept identical")
    func zeroAvailable() {
        #expect(FitLineFit.scale(natural: 100, available: 0) == 0)
    }

    @Test("the row is never ENLARGED, only shrunk — k clamps at 1")
    func neverEnlarges() {
        #expect(FitLineFit.scale(natural: 100, available: 500) == 1)
    }
}

@Suite("FitLineFit — the pinned wrapper height")
struct FitLinePinnedHeightTests {

    @Test("shrunk (k < 1): the wrapper is pinned to naturalHeight · k, snug against the row")
    func pinnedWhileShrunk() throws {
        let pinned = try #require(FitLineFit.pinnedHeight(naturalHeight: 100, scale: 0.64))
        #expect(abs(pinned - 64) < 1e-9)
        #expect(FitLineFit.pinnedHeight(naturalHeight: 80, scale: 0.5) == 40)
    }

    @Test("unshrunk (k == 1): no height override — the TSX writes an empty style")
    func unpinnedAtNatural() {
        #expect(FitLineFit.pinnedHeight(naturalHeight: 100, scale: 1) == nil)
    }

    @Test("re-fitting from the same natural size is stable — the anti-collapse property")
    func refitDoesNotCompound() throws {
        // The 2024 bug: the wrapper's pinned height fed back into the next
        // measurement, so each re-fit shrank the row again until it collapsed
        // onto the tray. The fix measures the NATURAL (untransformed) size
        // every time — fitting twice from the same inputs must be the fixed
        // point, not a second shrink.
        let natural: CGFloat = 500
        let naturalHeight: CGFloat = 100
        let available: CGFloat = 320

        let k1 = FitLineFit.scale(natural: natural, available: available)
        let h1 = FitLineFit.pinnedHeight(naturalHeight: naturalHeight, scale: k1)

        // A second fit pass still sees natural = 500 (fixedSize keeps the row's
        // measured size independent of the pinned wrapper), NOT 500·k.
        let k2 = FitLineFit.scale(natural: natural, available: available)
        let h2 = FitLineFit.pinnedHeight(naturalHeight: naturalHeight, scale: k2)

        #expect(k1 == k2)
        #expect(h1 == h2)
        let pinned = try #require(h1)
        #expect(abs(pinned - 64) < 1e-9)

        // And the failure mode, for contrast: were the row allowed to stretch
        // to the pinned height (the web's missing `items-start`), the next
        // measurement would read 64 instead of 100 and pin 64·0.64 = 40.96 —
        // the second step of the collapse the natural-size measurement forbids.
        let compounded = try #require(FitLineFit.pinnedHeight(naturalHeight: pinned, scale: k1))
        #expect(abs(compounded - 40.96) < 1e-9)
    }
}
