import SwiftUI
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* D52. The hub's level buttons rendered 28.67 pt squares in 60 pt columns and  */
/* nothing caught it: the layout was legal, the screen looked busy rather than  */
/* broken, and 1435 tests had no way to ask "how big is that button".           */
/*                                                                             */
/* `ImageRenderer` answers exactly that. The raster it produces is the size the */
/* view RESOLVED to, so a test can read a laid-out dimension off it without a   */
/* window, a simulator or a `GeometryReader` — on the host, in `swift test`.    */
/* -------------------------------------------------------------------------- */

#if canImport(AppKit) || canImport(UIKit)

    @Suite("aspect-square")
    @MainActor
    struct AspectSquareTests {

        /// Resolve `view` and report the size it took.
        private func resolvedSize(_ view: some View) throws -> CGSize {
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            let image = try #require(renderer.cgImage)
            return CGSize(width: image.width, height: image.height)
        }

        @Test("a square cell takes its height from its WIDTH, not from its content")
        func heightFollowsWidth() throws {
            // A 24 pt digit is ~28.67 pt tall — the number that used to win.
            let cell = AspectSquare {
                Text(verbatim: "1").font(.system(size: 24, weight: .black))
            }
            .frame(width: 60)

            let size = try resolvedSize(cell)
            #expect(
                size == CGSize(width: 60, height: 60),
                Comment(rawValue: "a 60 pt-wide square cell resolved to \(size)"))
        }

        @Test("the content does not shrink the box, whatever it is")
        func contentDoesNotDriveTheBox() throws {
            // Two very different contents, one box size: this is what makes the
            // cell a TAP TARGET rather than a text bounding box.
            let tiny = try resolvedSize(
                AspectSquare { Text(verbatim: ".").font(.system(size: 8)) }.frame(width: 72))
            let large = try resolvedSize(
                AspectSquare { Text(verbatim: "88").font(.system(size: 28, weight: .black)) }
                    .frame(width: 72))

            #expect(tiny == CGSize(width: 72, height: 72), Comment(rawValue: "tiny → \(tiny)"))
            #expect(large == CGSize(width: 72, height: 72), Comment(rawValue: "large → \(large)"))
        }

        @Test("the level button clears Apple's 44 pt floor at the narrowest phone")
        func levelButtonIsATapTarget() throws {
            // The narrowest device the app ships to (iPhone SE, 320 pt) through
            // the real shell arithmetic: card, stage gutter, grid gaps.
            let viewport: CGFloat = 320
            let card = min(Shell.cardMaxWidth, viewport - 2 * Shell.minimumInset)
            let row = min(HubMetrics.rowMaxWidth, card - 2 * HubMetrics.stagePaddingX)
            let column =
                (row - HubMetrics.gridGap * CGFloat(HubMetrics.gridColumns - 1))
                / CGFloat(HubMetrics.gridColumns)

            let size = try resolvedSize(
                AspectSquare {
                    Text(verbatim: "8").font(
                        Typography.rounded(Typography.Size.xxl, Typography.Weight.black))
                }
                .frame(width: column))

            #expect(
                size.height >= 44,
                Comment(
                    rawValue:
                        "a level button is \(size.height) pt tall at \(viewport) pt — under the 44 pt floor"))
        }
    }

#endif
