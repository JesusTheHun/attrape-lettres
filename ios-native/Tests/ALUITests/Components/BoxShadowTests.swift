import Foundation
import SwiftUI
import Testing

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* D54 — a shadow is cast by the BOX, never by the glyphs inside it.           */
/*                                                                             */
/* SwiftUI's `.shadow` is a per-LAYER effect. Applied to a composed view it     */
/* runs on every drawing primitive separately, so a `Text` over a filled        */
/* rectangle casts its own drop shadow — and, being drawn above the fill, that  */
/* shadow lands ON the fill. Reported from a device as « the shadow under the   */
/* letters, the letters themselves, not the tile ».                            */
/*                                                                             */
/* CSS has no such behaviour: `box-shadow` is cast by the border box and        */
/* `filter: drop-shadow` by the element's flattened alpha. `.compositingGroup()`*/
/* is what restores either one.                                                */
/*                                                                             */
/* Two tests, because the defect has two halves. The first RENDERS a tile and   */
/* looks at its face — the reported symptom, in pixels. The second is a source  */
/* scan, because the same mistake was live at twenty call sites and no raster   */
/* test tells a reviewer which line to add (same tool, and same reason, as      */
/* `ConsentCopyTests`).                                                        */
/* -------------------------------------------------------------------------- */

#if canImport(AppKit) || canImport(UIKit)

    @Suite("box shadows")
    @MainActor
    struct BoxShadowRasterTests {

        /// A tile side that is viewport-independent: `clamp(100, 0vw, 100)`.
        private static let side: CGFloat = 100
        /// The canvas margin, wide enough to hold the tile's own drop shadow
        /// (12 pt down, 10 pt of blur) without changing the layout.
        private static let margin: CGFloat = 50

        /// #4FC3F7 — the fill. Any face pixel DARKER than this on every channel
        /// can only be a shadow: the ink is white, so a glyph's antialiasing can
        /// blend the face towards white and never away from it. That asymmetry
        /// is the whole test.
        private static let fill = (r: 79, g: 195, b: 247)

        private func face(of view: some View) throws -> [(r: Int, g: Int, b: Int)] {
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            let image = try #require(renderer.cgImage)
            let (w, h) = (image.width, image.height)
            #expect(w == Int(Self.side + 2 * Self.margin))
            #expect(h == Int(Self.side + 2 * Self.margin))

            var pixels = [UInt8](repeating: 0, count: w * h * 4)
            let context = try #require(
                CGContext(
                    data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

            // The face interior: the tile's 100 pt box, inset past its 28 pt
            // rounded corners so only fill-or-glyph pixels are sampled.
            let inset = Int(Self.margin) + 12
            var out: [(r: Int, g: Int, b: Int)] = []
            for y in inset..<(h - inset) {
                for x in inset..<(w - inset) {
                    let i = (y * w + x) * 4
                    out.append((Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2])))
                }
            }
            return out
        }

        /// `TileFace`, not `Tile`: a `Tile` carries the `touchDown` surface, and
        /// `ImageRenderer` renders any tree containing a platform-view
        /// representable as a placeholder — a solid red rectangle, in which
        /// every pixel assertion below would pass or fail for the wrong reason.
        /// (`theProbeIsNotLookingAtNothing` is what caught that.)
        private var tile: some View {
            TileFace(
                bg: HexColor("#4FC3F7"),
                ink: HexColor("#FFFFFF"),
                highlight: false,
                side: Self.side,
                fontSize: 48,
                horizontalPadding: 16
            ) {
                Text(verbatim: "A")
            }
            .padding(Self.margin)
        }

        @Test("no letter casts a shadow onto the tile it sits on")
        func theFaceIsClean() throws {
            let f = Self.fill
            let smudged = try face(of: tile).filter { p in
                // Darker than the fill on EVERY channel — never a white blend.
                p.r < f.r - 4 && p.g < f.g - 4 && p.b < f.b - 4
            }
            let darkest = smudged.min { $0.r + $0.g + $0.b < $1.r + $1.g + $1.b } ?? f
            #expect(
                smudged.count == 0,
                Comment(
                    rawValue:
                        "\(smudged.count) face pixels are darker than the \(f) fill — the glyph is casting its own shadow (D54). Darkest: \(darkest)"
                ))
        }

        @Test("the probe can see the face it is meant to be checking")
        func theProbeIsNotLookingAtNothing() throws {
            // Guards the test above against the way it could pass for free: an
            // empty raster, a transparent tile, a rect sampled off the face. The
            // face must be mostly the fill, and must contain some white glyph.
            let pixels = try face(of: tile)
            let f = Self.fill
            let onFill = pixels.filter {
                abs($0.r - f.r) < 6 && abs($0.g - f.g) < 6 && abs($0.b - f.b) < 6
            }
            let onGlyph = pixels.filter { $0.r > 240 && $0.g > 240 && $0.b > 240 }
            #expect(onFill.count > pixels.count / 2, Comment(rawValue: "fill pixels: \(onFill.count)/\(pixels.count)"))
            #expect(onGlyph.count > 50, Comment(rawValue: "glyph pixels: \(onGlyph.count)"))
        }
    }

#endif

/* -------------------------------------------------------------------------- */

private let uiRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALUITests/Components/BoxShadowTests.swift
    .deletingLastPathComponent()  // …/Tests/ALUITests/Components
    .deletingLastPathComponent()  // …/Tests/ALUITests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/ios-native
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALUI")

@Suite("every shadow is cast by one layer")
struct BoxShadowScanTests {

    /// What makes a `.shadow` safe, in the six lines above it:
    ///
    /// * `.compositingGroup()` — the composed view was flattened first;
    /// * `.fill(` / `.stroke(` / `.strokeBorder(` — it is applied to a bare
    ///   shape, which is already one layer (`liftedCapsule`, `ListenPill`, the
    ///   twins and grid slots all take this route);
    /// * `.shadow(` — a second shadow stacked on an already-guarded first.
    private static let guards = [
        ".compositingGroup()", ".fill(", ".stroke(", ".strokeBorder(", ".shadow(",
    ]
    /// How far back to look. Long enough to reach past an argument list, short
    /// enough that an unrelated `.fill` in a sibling expression cannot vouch.
    private static let window = 6

    private func swiftSources() throws -> [URL] {
        let enumerator = try #require(
            FileManager.default.enumerator(at: uiRoot, includingPropertiesForKeys: nil))
        return enumerator.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    @Test("the scan can find the sources it is meant to scan")
    func rootExists() throws {
        let sources = try swiftSources()
        #expect(sources.count >= 20, Comment(rawValue: "found \(sources.count) ALUI sources"))
        let shadows = try sources.reduce(0) { total, url in
            try total + String(contentsOf: url, encoding: .utf8)
                .split(separator: "\n").filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix(".shadow(") }.count
        }
        #expect(shadows >= 25, Comment(rawValue: "found \(shadows) shadow call sites"))
    }

    @Test("no shadow is applied to a view that was never flattened")
    func everyShadowIsGuarded() throws {
        for url in try swiftSources() {
            let lines = try String(contentsOf: url, encoding: .utf8).split(
                separator: "\n", omittingEmptySubsequences: false
            ).map { $0.trimmingCharacters(in: .whitespaces) }

            for (index, line) in lines.enumerated() where line.hasPrefix(".shadow(") {
                let above = lines[max(0, index - Self.window)..<index]
                    .filter { !$0.isEmpty && !$0.hasPrefix("//") && !$0.hasPrefix("/*") && !$0.hasPrefix("*") }
                #expect(
                    above.contains(where: { candidate in
                        Self.guards.contains(where: candidate.contains)
                    }),
                    Comment(
                        rawValue:
                            "\(url.lastPathComponent):\(index + 1) casts a shadow from a composed view — its glyphs will each cast one of their own. Add `.compositingGroup()` (D54)."
                    ))
            }
        }
    }
}
