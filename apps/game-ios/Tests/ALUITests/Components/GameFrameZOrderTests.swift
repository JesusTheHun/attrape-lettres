import SwiftUI
import Testing
@testable import ALUI

// The confetti stacking order, rasterised.
//
// Two engine agents independently reported that `GameFrame` painted the confetti
// overlay ON TOP of the exercise content, where the web paints it behind. They
// were right, and the reason it survived a full phase is worth recording: z-order
// is invisible to every assertion that does not actually draw. The draw list, the
// view type, the modifier chain and all 1115 other tests are equally happy either
// way. Only pixels can tell.
//
// So this file rasterises a `GameFrame` whose overlay and content are two opaque
// blocks of known colour occupying the same rectangle, and reads back which one
// won. `ImageRenderer` runs on macOS (D23), so it runs in the host suite with
// everything else — no simulator.
//
// The web order, bottom to top (measured, not assumed):
//   stage gradient  <  canvas zIndex 40  <  header z-[41]  <  children z-[41]
// with the last tie broken by DOM order in the children's favour.

#if canImport(AppKit) || canImport(UIKit)

@MainActor
private func firstOpaquePixel(
    _ size: CGSize,
    at point: CGPoint,
    @ViewBuilder _ content: () -> some View
) -> (r: Int, g: Int, b: Int)? {
    let renderer = ImageRenderer(content: content().frame(width: size.width, height: size.height))
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
    let x = Int(point.x), y = Int(point.y)
    guard x >= 0, y >= 0, x < cg.width, y < cg.height else { return nil }
    let i = (y * cg.width + x) * 4
    return (Int(buffer[i]), Int(buffer[i + 1]), Int(buffer[i + 2]))
}

@Suite("GameFrame — confetti stacking order", .serialized)
@MainActor
struct GameFrameZOrderTests {

    /// Sampled well below the header band so only the overlay and the content
    /// compete for the pixel.
    private static let sample = CGPoint(x: 200, y: 400)
    private static let frame = CGSize(width: 400, height: 700)

    @Test("the content paints OVER the confetti overlay, as z-[41] over zIndex 40 does on the web")
    func contentWinsOverConfetti() throws {
        let px = try #require(firstOpaquePixel(Self.frame, at: Self.sample) {
            GameFrame(
                onBack: {},
                done: 0,
                total: 4,
                stars: [true, true, true, true],
                overlay: {
                    // Stand-in for the confetti canvas: opaque red, full-bleed.
                    Color.red
                },
                content: {
                    // Stand-in for an exercise column: opaque green, full-bleed.
                    Color.green.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            )
            .alViewport(width: Self.frame.width)
        })

        // Green must win. If the overlay is on top this reads red, which is
        // exactly the defect the engine agents found.
        #expect(
            px.g > px.r,
            Comment(rawValue: "confetti painted over the game content: got r=\(px.r) g=\(px.g) b=\(px.b)")
        )
    }

    @Test("the overlay still paints over the stage gradient — it is not behind everything")
    func confettiWinsOverTheBackground() throws {
        // The complementary half: pushing the overlay too far down the stack
        // would hide the burst entirely, which no test above would notice.
        let px = try #require(firstOpaquePixel(Self.frame, at: Self.sample) {
            GameFrame(
                onBack: {},
                done: 0,
                total: 4,
                stars: [true, true, true, true],
                overlay: { Color.red },
                content: { Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity) }
            )
            .alViewport(width: Self.frame.width)
        })

        #expect(
            px.r > px.g && px.r > px.b,
            Comment(rawValue: "the overlay is hidden by the stage gradient: got r=\(px.r) g=\(px.g) b=\(px.b)")
        )
    }
}

#endif
