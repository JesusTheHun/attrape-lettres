import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* The app icon, asserted where it actually fails.                             */
/*                                                                             */
/* Three different things can go wrong with an icon, and only one of them is    */
/* visible while looking at it:                                                */
/*                                                                             */
/*   1. App Store Connect refuses the BINARY, hours after a green build, for    */
/*      an alpha channel or a wrong size. That is a property of the committed   */
/*      file, so it is checked on the committed file — not on a fresh render.   */
/*   2. Somebody changes a Palette hex and never re-runs `IconForge`. The       */
/*      source and the shipped PNG drift apart silently and for good.           */
/*   3. The mark stops reading at 29 pt. Nobody notices, because nobody looks   */
/*      at an icon at 29 pt on purpose — they look at it at 1024.               */
/*                                                                             */
/* The drift check (2) compares SIGNATURES, not pixels: text rasterisation      */
/* moves between OS versions, and a strict pixel diff on a glyph is a test      */
/* that fails on somebody else's Mac for no reason. A 16×16 mean-channel        */
/* comparison catches a changed colour, a changed layout and a changed variant  */
/* while ignoring hinting noise.                                               */
/* -------------------------------------------------------------------------- */

private let packageRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()  // AppIcon/
    .deletingLastPathComponent()  // ALUITests/
    .deletingLastPathComponent()  // Tests/
    .deletingLastPathComponent()  // apps/game-ios/

private let appIconPNG = packageRoot.appendingPathComponent(
    "App/AttrapeLettres/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
)

private let webPublic = packageRoot
    .deletingLastPathComponent()
    .appendingPathComponent("game-web/public")

private func load(_ url: URL) throws -> CGImage {
    guard
        let source = CGImageSourceCreateWithURL(url as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else {
        throw Failure("could not read \(url.lastPathComponent) — has `swift run IconForge` been run?")
    }
    return image
}

private struct Failure: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// Every pixel of `image`, resampled to `side`×`side` RGB. The common currency
/// of the comparisons below.
private func samples(_ image: CGImage, side: Int) throws -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: side * side * 4)
    try bytes.withUnsafeMutableBytes { raw in
        guard
            let context = CGContext(
                data: raw.baseAddress,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: side * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            )
        else { throw Failure("could not sample at \(side)") }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
    }
    return bytes
}

@MainActor
private func render<V: View>(_ view: V, side: CGFloat) throws -> CGImage {
    let renderer = ImageRenderer(content: view.frame(width: side, height: side))
    renderer.scale = 1
    renderer.isOpaque = true
    guard let image = renderer.cgImage else { throw Failure("render failed at \(side)") }
    return image
}

/// Mean absolute per-channel difference, 0…255.
private func distance(_ a: [UInt8], _ b: [UInt8]) -> Double {
    precondition(a.count == b.count)
    var total = 0
    var counted = 0
    for index in stride(from: 0, to: a.count, by: 4) {
        for channel in 0..<3 {
            total += abs(Int(a[index + channel]) - Int(b[index + channel]))
            counted += 1
        }
    }
    return Double(total) / Double(counted)
}

@Suite("The app icon file, as App Store Connect will read it")
struct AppIconFileTests {

    @Test("the asset catalog carries a 1024×1024 icon")
    func sizeIsRight() throws {
        let image = try load(appIconPNG)
        #expect(image.width == 1024)
        #expect(image.height == 1024)
    }

    @Test("it has no alpha channel — the upload rejection nobody sees coming")
    func noAlphaChannel() throws {
        let image = try load(appIconPNG)
        let alpha = CGImageAlphaInfo(rawValue: image.alphaInfo.rawValue)
        // "Invalid Image — the icon can't be transparent nor contain an alpha
        // channel." An icon that LOOKS opaque still carries one unless it was
        // flattened, which is exactly what `IconForge.write` exists to do.
        #expect(
            alpha == .none || alpha == .noneSkipLast || alpha == .noneSkipFirst,
            "AppIcon-1024.png carries alpha (\(alpha as Any)); App Store Connect will refuse the build"
        )
    }

    @Test("the appiconset points at the file that is actually there")
    func contentsJSONAgrees() throws {
        let json = try String(
            contentsOf: appIconPNG.deletingLastPathComponent().appendingPathComponent("Contents.json"),
            encoding: .utf8
        )
        #expect(json.contains("\"filename\" : \"AppIcon-1024.png\""))
        #expect(json.contains("\"size\" : \"1024x1024\""))
        #expect(FileManager.default.fileExists(atPath: appIconPNG.path))
    }
}

@Suite("The committed icon still is what the source draws")
@MainActor
struct AppIconDriftTests {

    /// 3.0 of 255 per channel: comfortably below any colour or layout change,
    /// comfortably above glyph-rasterisation noise between OS versions.
    static let tolerance = 3.0

    @Test("the shipped PNG matches a fresh render of AppIcon.shipping")
    func noDrift() throws {
        let committed = try samples(try load(appIconPNG), side: 16)
        let fresh = try samples(try render(AppIconView(AppIcon.shipping), side: 1024), side: 16)
        let delta = distance(committed, fresh)
        #expect(
            delta < Self.tolerance,
            """
            AppIcon-1024.png is \(String(format: "%.2f", delta)) away from what AppIcon.swift renders. \
            Someone changed the source and did not re-bake: run `swift run IconForge`.
            """
        )
    }

    @Test("it is the coral variant, not one of the others")
    func rightVariant() throws {
        // A corner is the field, the centre is the tile. Cheap, and it fails
        // loudly if `shipping` is changed without a re-bake — the drift test
        // would too, but this one says WHY in one line.
        let pixels = try samples(try load(appIconPNG), side: 32)
        func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
            let i = (y * 32 + x) * 4
            return (Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2]))
        }
        let corner = rgb(1, 30)  // deep coral, well outside the tile
        // NOT the centre: that is where the A is, and the first run of this
        // test read (135, 106, 95) — the ink, correctly, at the one point on
        // the tile that is never its face. A third of the way in is inside the
        // tile and outside the glyph, at any rotation.
        let face = rgb(10, 10)
        #expect(corner.0 > 200 && corner.1 < 160 && corner.2 < 110, "corner is not the coral field: \(corner)")
        #expect(face.0 > 240 && face.1 > 230 && face.2 > 215, "the tile's face is not cream: \(face)")
    }

    @Test("the web icons come off the same render")
    func webAgrees() throws {
        // The two apps are independent implementations (CLAUDE.md) — the ONE
        // thing they may not disagree about is what the family recognises.
        let fresh = try samples(try render(AppIconView(AppIcon.shipping), side: 1024), side: 16)
        for name in ["icon-192.png", "icon-512.png", "apple-touch-icon.png"] {
            let delta = distance(try samples(try load(webPublic.appendingPathComponent(name)), side: 16), fresh)
            #expect(delta < AppIconDriftTests.tolerance, "\(name) is \(String(format: "%.2f", delta)) off the iOS icon")
        }
    }

    @Test("the maskable cut is a different file, and has no confetti in it")
    func maskableIsSeparate() throws {
        let maskable = try samples(try load(webPublic.appendingPathComponent("icon-maskable-512.png")), side: 16)
        let flecked = try samples(try render(AppIconView(AppIcon.shipping), side: 1024), side: 16)
        // Same mark, so they are close; the flecks are the whole difference,
        // so they are not identical. Both halves matter: equal would mean the
        // manifest's `purpose: "maskable"` is pointing at a croppable icon
        // again, and far apart would mean it is a different design.
        let delta = distance(maskable, flecked)
        #expect(delta > 0.4, "the maskable icon still carries the flecks Android will crop")
        #expect(delta < 12.0, "the maskable icon is not the same mark any more: \(delta)")

        let fresh = try samples(try render(AppIconMaskableView(), side: 1024), side: 16)
        #expect(distance(maskable, fresh) < AppIconDriftTests.tolerance)
    }
}

@Suite("It still reads at the sizes a home screen uses")
@MainActor
struct AppIconLegibilityTests {

    /// The share of pixels dark enough to be the glyph, at `side`.
    ///
    /// The ink is #4A2317 (luma ≈ 51) on a cream face (luma ≈ 245) on a coral
    /// field (luma ≈ 150). A threshold of 110 catches the letter and nothing
    /// else on the icon.
    private func inkFraction(at side: CGFloat) throws -> Double {
        let pixels = try samples(try render(AppIconView(AppIcon.shipping), side: side), side: Int(side))
        var dark = 0
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let luma =
                0.2126 * Double(pixels[index]) + 0.7152 * Double(pixels[index + 1])
                + 0.0722 * Double(pixels[index + 2])
            if luma < 110 { dark += 1 }
        }
        return Double(dark) / Double(Int(side) * Int(side))
    }

    @Test("the A survives 29 pt — Settings, Spotlight, the notification badge")
    func readableAt29() throws {
        let fraction = try inkFraction(at: 29)
        // At 1024 the glyph covers ~5.6 % of the square. Downsampling blurs it
        // into the cream, so the count falls; below ~2 % there is no letter
        // left, only a smudge. This is the assertion that killed « Renard »:
        // a mascot at 29 pt scores here and still reads as an orange blob,
        // which is why the number is a floor and not the whole argument.
        #expect(fraction > 0.02, "the glyph is \(String(format: "%.1f%%", fraction * 100)) of a 29 pt icon — too faint")
    }

    @Test("and 60 pt — the home screen")
    func readableAt60() throws {
        #expect(try inkFraction(at: 60) > 0.03)
    }

    @Test("the mark is never near the edge the mask cuts")
    func insideTheMask() throws {
        // iOS masks with a rounded square whose corner arcs sit at (±283, ±283)
        // in the 1024 space. Sample the four corners: each must still be the
        // coral field, i.e. nothing of the subject reaches them.
        let pixels = try samples(try render(AppIconView(AppIcon.shipping), side: 1024), side: 64)
        func isField(_ x: Int, _ y: Int) -> Bool {
            let i = (y * 64 + x) * 4
            let (r, g, b) = (Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2]))
            return r > 195 && g > 85 && g < 165 && b < 120
        }
        for (x, y) in [(1, 1), (62, 1), (1, 62), (62, 62)] {
            #expect(isField(x, y), "the corner at (\(x), \(y)) is not the field — something reaches into the mask")
        }
    }
}
