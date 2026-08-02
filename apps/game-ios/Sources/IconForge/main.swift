import ALUI
import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/* -------------------------------------------------------------------------- */
/* `swift run IconForge [outdir]` — the icon, baked.                           */
/*                                                                             */
/* The app icon is a PNG, but it is not a SOURCE. `AppIcon.swift` is. This      */
/* turns one into the other, for both products at once:                        */
/*                                                                             */
/*   App/AttrapeLettres/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png     */
/*   ../game-web/public/{icon-192,icon-512,apple-touch-icon}.png                */
/*                                                                             */
/* Two things this does that a design tool export does not:                    */
/*                                                                             */
/*   1. FLATTENS ONTO OPAQUE WHITE. `ImageRenderer` hands back an image with an */
/*      alpha channel whether or not anything is transparent, and App Store     */
/*      Connect rejects an icon that has one ("Invalid Image — icon can't be    */
/*      transparent nor contain an alpha channel"). Every write here goes       */
/*      through a `noneSkipLast` context, so the file physically cannot carry   */
/*      one.                                                                   */
/*   2. Downsamples from the 1024 render rather than re-rendering small. Text   */
/*      laid out at 29 pt is not the same shape as text laid out at 1024 and    */
/*      shrunk — and what iOS actually does is shrink. The preview sheet is     */
/*      therefore honest about what the home screen will show.                 */
/*                                                                             */
/* An executable rather than a test: a test that writes into the source tree is */
/* a test that fails on a read-only checkout, and this is a tool, not an        */
/* assertion. The assertion lives next door in `AppIconRasterTests`, which      */
/* re-renders and compares against the committed PNG — so a forgotten re-bake   */
/* fails `swift test` instead of shipping.                                     */
/* -------------------------------------------------------------------------- */

@MainActor
enum IconForge {

    static let packageRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // IconForge/
        .deletingLastPathComponent()  // Sources/
        .deletingLastPathComponent()  // apps/game-ios/

    /// Render a SwiftUI view at exactly `side` × `side` pixels.
    ///
    /// `scale = 1` is load-bearing: `ImageRenderer` otherwise inherits the
    /// Mac's backing scale and quietly hands back 2048 px for a 1024 pt frame.
    static func raster<V: View>(_ view: V, side: CGFloat) -> CGImage? {
        let renderer = ImageRenderer(content: view.frame(width: side, height: side))
        renderer.scale = 1
        renderer.isOpaque = true
        return renderer.cgImage
    }

    /// Draw `image` into an OPAQUE sRGB bitmap of `side` px and write it as PNG.
    static func write(_ image: CGImage, side: Int, to url: URL) throws {
        guard
            let context = CGContext(
                data: nil,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                // No alpha, by construction — see the header.
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            )
        else { throw Failure("could not create a \(side)×\(side) bitmap") }

        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))

        guard
            let flat = context.makeImage(),
            let destination = CGImageDestinationCreateWithURL(
                url as CFURL, UTType.png.identifier as CFString, 1, nil
            )
        else { throw Failure("could not encode \(url.lastPathComponent)") }

        CGImageDestinationAddImage(destination, flat, nil)
        guard CGImageDestinationFinalize(destination) else {
            throw Failure("could not write \(url.path)")
        }
        print("  \(side)×\(side)  \(url.path)")
    }

    struct Failure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    // MARK: - Commands

    /// Every variant at 1024, plus the comparison sheet, into a scratch dir.
    static func contactSheet(into directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        for variant in AppIconVariant.allCases {
            guard let image = raster(AppIconView(variant), side: AppIcon.designSide) else {
                throw Failure("render failed for \(variant.rawValue)")
            }
            try write(
                image,
                side: Int(AppIcon.designSide),
                to: directory.appendingPathComponent("icon-\(variant.rawValue)-1024.png")
            )
        }

        // The sheet is wider than it is tall, so it cannot go through `write`,
        // which is square by contract (every icon this tool makes is).
        let sheet = AppIconSheet()
        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        renderer.isOpaque = true
        guard let image = renderer.cgImage else { throw Failure("sheet render failed") }
        let url = directory.appendingPathComponent("sheet.png")
        guard
            let destination = CGImageDestinationCreateWithURL(
                url as CFURL, UTType.png.identifier as CFString, 1, nil
            )
        else { throw Failure("could not encode the sheet") }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw Failure("could not write the sheet") }
        print("  sheet    \(url.path)")
    }

    /// The shipping variant, into both products.
    static func bake() throws {
        guard let image = raster(AppIconView(AppIcon.shipping), side: AppIcon.designSide) else {
            throw Failure("render failed for \(AppIcon.shipping.rawValue)")
        }

        let appIconSet = packageRoot
            .appendingPathComponent("App/AttrapeLettres/Assets.xcassets/AppIcon.appiconset")
        try write(image, side: 1024, to: appIconSet.appendingPathComponent("AppIcon-1024.png"))
        try Data(Self.contentsJSON.utf8)
            .write(to: appIconSet.appendingPathComponent("Contents.json"))
        print("  json     \(appIconSet.appendingPathComponent("Contents.json").path)")

        // The PWA's icons come off the same render. The two apps are
        // independent implementations on purpose (CLAUDE.md), but a family
        // that has both should not see two different marks.
        let web = packageRoot
            .deletingLastPathComponent()  // apps/
            .appendingPathComponent("game-web/public")
        if FileManager.default.fileExists(atPath: web.path) {
            try write(image, side: 192, to: web.appendingPathComponent("icon-192.png"))
            try write(image, side: 512, to: web.appendingPathComponent("icon-512.png"))
            // 180 is the iPhone @3x home-screen size Safari asks for.
            try write(image, side: 180, to: web.appendingPathComponent("apple-touch-icon.png"))

            // A separate file for `purpose: "maskable"`, because Android may
            // crop to a circle and the flecks live outside the safe zone.
            guard let maskable = raster(AppIconMaskableView(), side: AppIcon.designSide) else {
                throw Failure("render failed for the maskable cut")
            }
            try write(maskable, side: 512, to: web.appendingPathComponent("icon-maskable-512.png"))
        }
    }

    /// Xcode 14+ wants exactly one image; `platform: ios` keeps the single-size
    /// form rather than reverting to the legacy per-size matrix.
    static let contentsJSON = """
        {
          "images" : [
            {
              "filename" : "AppIcon-1024.png",
              "idiom" : "universal",
              "platform" : "ios",
              "size" : "1024x1024"
            }
          ],
          "info" : {
            "author" : "xcode",
            "version" : 1
          }
        }

        """
}

// MARK: - Entry point

MainActor.assumeIsolated {
    do {
        let args = Array(CommandLine.arguments.dropFirst())
        if let index = args.firstIndex(of: "--sheet") {
            let directory = URL(
                fileURLWithPath: index + 1 < args.count ? args[index + 1] : "./icon-candidates"
            )
            print("Rendering every variant + the comparison sheet:")
            try IconForge.contactSheet(into: directory)
        } else {
            print("Baking \(AppIcon.shipping.rawValue) into both products:")
            try IconForge.bake()
        }
    } catch {
        FileHandle.standardError.write(Data("IconForge: \(error)\n".utf8))
        exit(1)
    }
}
