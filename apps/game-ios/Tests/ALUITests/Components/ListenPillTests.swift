import Foundation
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* D55. Two claims about the 🔊 « Écouter » button, neither of which a renderer */
/* can settle: `LayerHost` is a passthrough on the host (macOS has no hosted    */
/* layer, so `Anim.press` is a no-op there), and `ImageRenderer` cannot press   */
/* anything. What CAN be held still is the composition — that the button is     */
/* built once and that it goes through the press path — so that is what this    */
/* asserts, the same way `ConsentCopyTests` holds a missing modifier still.     */
/*                                                                             */
/* The behaviour underneath is covered for real: `TileTests` exercises          */
/* `TilePress.previewDown` directly (press fires, then the action; a disabled   */
/* target does neither).                                                       */
/* -------------------------------------------------------------------------- */

private let uiRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALUITests/Components/ListenPillTests.swift
    .deletingLastPathComponent()  // …/Tests/ALUITests/Components
    .deletingLastPathComponent()  // …/Tests/ALUITests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/apps/game-ios
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALUI")

@Suite("the 🔊 pill")
struct ListenPillTests {

    private func source(_ relative: String) throws -> String {
        try String(contentsOf: uiRoot.appendingPathComponent(relative), encoding: .utf8)
    }

    @Test("the scan can find the sources it is meant to scan")
    func rootExists() throws {
        let pill = try source("Components/ListenPill.swift")
        #expect(pill.contains("struct ListenPill"))
        let engines = try FileManager.default.contentsOfDirectory(
            atPath: uiRoot.appendingPathComponent("Engines").path)
        #expect(engines.count >= 8, Comment(rawValue: "found \(engines.count) engine sources"))
    }

    @Test("the pill presses at touch-down, like a tile")
    func thePillPresses() throws {
        // The reported defect: tapping it did nothing visible, and the voice it
        // asks for can be a clip decode away — so a child taps again and cuts
        // the line they just requested.
        let pill = try source("Components/ListenPill.swift")
        #expect(
            pill.contains("TilePress.previewDown"),
            Comment(rawValue: "the 🔊 pill no longer runs the press animation — see D55"))
    }

    @Test("no engine builds a second 🔊 pill of its own")
    func onlyOneImplementation() throws {
        // `bg-white/70` is the pill's fill and nothing else in an engine wears
        // it. Four copies of this button existed; a fifth would go unnoticed
        // exactly as the first four did.
        let dir = uiRoot.appendingPathComponent("Engines")
        for name in try FileManager.default.contentsOfDirectory(atPath: dir.path)
        where name.hasSuffix(".swift") {
            let text = try String(
                contentsOf: dir.appendingPathComponent(name), encoding: .utf8)
            #expect(
                !text.contains("White.o70"),
                Comment(
                    rawValue:
                        "\(name) paints its own bg-white/70 pill — use `ListenPill` (D55)"))
        }
    }
}
