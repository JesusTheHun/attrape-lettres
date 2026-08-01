import CoreImage
import Foundation
import Testing

@testable import ALCore
@testable import ALUI

/* -------------------------------------------------------------------------- */
/* A QR code that renders but does not decode is the perfect silent failure:    */
/* it looks exactly right on screen and simply never works in a kitchen. So     */
/* the test reads it back with a detector rather than checking a size.          */
/* -------------------------------------------------------------------------- */

private let household = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

private func decode(_ image: CGImage) -> [String] {
    let detector = CIDetector(
        ofType: CIDetectorTypeQRCode,
        context: CIContext(),
        options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
    )
    let features = detector?.features(in: CIImage(cgImage: image)) ?? []
    return features.compactMap { ($0 as? CIQRCodeFeature)?.messageString }
}

@Suite("QRCode")
struct QRCodeTests {

    @Test("the code on screen decodes back to the pairing link")
    func roundTrip() throws {
        let link = try #require(PairingLink.url(household: household))
        let image = try #require(QRCode.cgImage(for: link.absoluteString))
        #expect(decode(image) == [link.absoluteString])
    }

    @Test("what the other phone reads is a household this app will accept")
    func decodesToAJoinableHousehold() throws {
        // The whole loop in one test: mint → render → scan → parse → join.
        // Each half is covered elsewhere; this is the only place they meet.
        let link = try #require(PairingLink.url(household: household))
        let image = try #require(QRCode.cgImage(for: link.absoluteString))
        let scanned = try #require(decode(image).first)
        let url = try #require(URL(string: scanned))
        #expect(PairingLink.household(from: url) == household)
    }

    @Test("the code is scaled up, because one pixel per module is unscannable")
    func scaled() throws {
        let image = try #require(QRCode.cgImage(for: "attrape-lettres://pair?h=\(household)"))
        // CIQRCodeGenerator emits one pixel per module; a 25-module code would
        // be 25 pixels wide and no camera would resolve it off a phone screen.
        #expect(image.width > 200)
        #expect(image.width == image.height)
    }
}
