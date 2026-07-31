import Foundation
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* D53. The paywall's consent card rendered                                     */
/*                                                                             */
/*     « Nous aider à améliorer le jeu — exercice, niveau, réussi ou non.       */
/*       Jamais le prénom de v… »                                              */
/*                                                                             */
/* — an ellipsis three words into the sentence that says what is NOT collected. */
/* The card hosted alone, at the same width, wraps to three lines correctly, so */
/* the two-line height came from the stack around it, not from the text.        */
/* `fixedSize(horizontal: false, vertical: true)` settles it either way: take   */
/* the height this width needs, and never truncate.                            */
/*                                                                             */
/* A source scan rather than a raster assertion, and deliberately: what went    */
/* wrong is not a pixel, it is a MISSING MODIFIER, and no screenshot test tells */
/* a reviewer which line to add. Same mechanism as `MoneySourceScanTests` —     */
/* a review-time rule turned into a build-time one, for a rule that only has to */
/* hold in two places and would otherwise be re-learned from a screenshot.      */
/* -------------------------------------------------------------------------- */

private let uiRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALUITests/Screens/ConsentCopyTests.swift
    .deletingLastPathComponent()  // …/Tests/ALUITests/Screens
    .deletingLastPathComponent()  // …/Tests/ALUITests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/apps/game-ios
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALUI")
    .appendingPathComponent("Screens")

@Suite("consent copy is never truncated")
struct ConsentCopyTests {

    /// The two screens that ask for, or show, analytics consent.
    private static let screens = ["Paywall.swift", "Onboarding.swift"]

    @Test("the scan can find the sources it is meant to scan")
    func rootExists() {
        for screen in Self.screens {
            #expect(
                FileManager.default.fileExists(
                    atPath: uiRoot.appendingPathComponent(screen).path),
                Comment(rawValue: "\(screen) moved; this scan is now asserting nothing"))
        }
    }

    @Test("every consent disclosure takes the height its width needs")
    func consentBodyIsFixedSize() throws {
        for screen in Self.screens {
            let source = try String(
                contentsOf: uiRoot.appendingPathComponent(screen), encoding: .utf8)

            // The `consentBody` reference and the modifier that protects it are
            // in the same expression; anything between them is styling.
            let marker = "consentBody"
            let index = try #require(
                source.range(of: marker),
                Comment(rawValue: "\(screen) no longer renders a consentBody"))
            let tail = source[index.upperBound...]
            // The card ends at its `.toggleStyle` — beyond that is a different
            // view and a match there would prove nothing.
            let cardEnd = tail.range(of: ".toggleStyle")?.lowerBound ?? tail.endIndex
            let card = tail[..<cardEnd]

            #expect(
                card.contains("fixedSize(horizontal: false, vertical: true)"),
                Comment(
                    rawValue:
                        "\(screen)'s consent disclosure can be truncated by its container — see D53"))
        }
    }
}
