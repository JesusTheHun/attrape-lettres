import SwiftUI
import Testing
@testable import ALUI

// `PageScroll` fixes something no host test can see (D42): a keyboard inset
// sliding the first-run picker under the notch. The fix itself was verified on
// the simulator, and this file does NOT pretend to re-verify it.
//
// It guards the one thing here that is both host-visible and dangerous: THE
// GATE. `alPageScroll(enabled:)` must be the exact identity when disabled,
// because the branch that passes `false` is the roster grid, and `ChildCard` is
// a `LayerHost`. A `UIScrollView` over it sets `delaysContentTouches`, which
// holds a touch back to see whether it becomes a pan — and that is invariant 1,
// "feedback fires on `pointerdown`, before React commits". Anyone who
// "simplifies" the modifier to wrap unconditionally adds pan-recognition latency
// to every tile press on that screen. This test fails when they do.
//
// **Two things this file cannot do, established by measurement, not assumed:**
//
// 1. `ImageRenderer` does not draw `ScrollView` CONTENT on macOS. Sampling the
//    same red block bare gives `(255, 56, 60)`; inside a `ScrollView` it gives
//    `(0, 0, 0)` — the renderer produces a valid `CGImage` with nothing in it.
//    So the enabled path cannot be rasterised at all, and the "wrapped renders
//    identically to bare" test that ought to live here is not writable on the
//    host. The D19 render harness inherits this: any screen with a scroll view —
//    `ShopView`, and now the picker's form branch — needs the simulator, and a
//    pixel differ that does not know this will happily report two blank images
//    as a perfect match.
//
// 2. Never hand one of these pixel buffers to `#expect`. `#expect(bare ==
//    wrapped)` on a 1.12 MB `[UInt8]` does not fail — it HANGS, because the
//    macro captures its operands for failure diagnostics and reflecting a
//    million-element array never finishes. The whole 1423-test suite went from
//    0.85 s to a hard timeout. Compute the comparison first, pass the `Bool`.
//    That is why `GameFrameZOrderTests` only ever hands the macro three `Int`s.
//
// (A third trap, for the harness: a bare greedy `Color` inside a `ScrollView`
// resolves to infinite height and hangs the renderer outright. Bound every stub
// on the scroll axis.)

#if canImport(AppKit) || canImport(UIKit)

/// Top-aligned like the real shell (`RootView` places the stage with
/// `alignment: .top`).
@MainActor
private func raster(_ size: CGSize, @ViewBuilder _ content: () -> some View) -> [UInt8]? {
    let renderer = ImageRenderer(
        content: content().frame(width: size.width, height: size.height, alignment: .top)
    )
    renderer.scale = 1
    guard let cg = renderer.cgImage else { return nil }
    var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
    guard let ctx = CGContext(
        data: &buffer, width: cg.width, height: cg.height,
        bitsPerComponent: 8, bytesPerRow: cg.width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    return buffer
}

/// Stands in for the picker stage: a rigid block with a marker band at its very
/// top playing the part of the 👋. Bounded on the scroll axis — see the traps.
private struct StageStub: View {
    let height: CGFloat
    static let band: CGFloat = 40
    var body: some View {
        VStack(spacing: 0) {
            Color.red.frame(height: Self.band)
            Color.blue.frame(height: max(0, height - Self.band))
        }
        .frame(maxWidth: .infinity, alignment: .top)
    }
}

@Suite("PageScroll — the gate that keeps scroll views off the tiles", .serialized)
@MainActor
struct PageScrollTests {

    private static let frame = CGSize(width: 400, height: 700)

    /// Disabled must be the IDENTITY, pixel for pixel — not "close enough".
    ///
    /// Given trap 1 above, this is also self-checking in the way that matters:
    /// if someone drops the gate and wraps unconditionally, the wrapped render
    /// comes back blank rather than merely shifted, so this fails loudly instead
    /// of drifting.
    @Test("the roster grid never gets a scroll view over its LayerHost tiles")
    func disabledIsIdentity() throws {
        let bare = try #require(raster(Self.frame) { StageStub(height: 620) })
        let off = try #require(
            raster(Self.frame) { StageStub(height: 620).alPageScroll(enabled: false) })
        // Compute FIRST — see trap 2. Never `#expect(bare == off)`.
        let identical = bare == off
        #expect(identical, Comment(rawValue: "enabled:false must not build a ScrollView"))
    }
}

#endif
