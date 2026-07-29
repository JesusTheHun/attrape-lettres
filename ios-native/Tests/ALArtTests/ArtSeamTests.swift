import CoreGraphics
import Foundation
import SwiftUI
import Testing

@testable import ALArt
import ALCore

/* -------------------------------------------------------------------------- */
/* Integration seams for ALArt — the joints between the three art packages that */
/* no single agent owned.                                                       */
/*                                                                             */
/* Each package proves its own drawing. These prove the RULES that are true of  */
/* the target as a whole and that no per-file suite is positioned to see:       */
/* invariant 7's exhaustive switch, invariant 6's five French labels, D14's     */
/* single reduced-motion source, and D1's host-testability (no UIKit).          */
/*                                                                             */
/* Four of these are source scans. A source scan is the only shape that can     */
/* fail for the right reason here: `switch` exhaustiveness and "which module    */
/* did you import" are compile-time properties, so by the time a runtime test   */
/* could look, the regression has already been compiled away into a silently    */
/* blank icon.                                                                  */
/* -------------------------------------------------------------------------- */

// MARK: - Source access

private let alArtRoot: URL =
    URL(fileURLWithPath: #filePath)  // …/Tests/ALArtTests/ArtSeamTests.swift
    .deletingLastPathComponent()  // …/Tests/ALArtTests
    .deletingLastPathComponent()  // …/Tests
    .deletingLastPathComponent()  // …/ios-native
    .appendingPathComponent("Sources")
    .appendingPathComponent("ALArt")

private func swiftFiles(under directory: URL) -> [URL] {
    guard let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
    else { return [] }
    return walker.compactMap { $0 as? URL }
        .filter { $0.pathExtension == "swift" }
        .sorted { $0.path < $1.path }
}

/// Source with `//` and `/* */` comments blanked out. Every rig file quotes the
/// original TSX in a doc comment and several explain in prose why a `default:`
/// would be wrong — matching that prose would make these scans pass by reading
/// their own justification.
private func stripComments(_ source: String) -> String {
    var out = ""
    var i = source.startIndex
    var blockDepth = 0
    while i < source.endIndex {
        let rest = source[i...]
        if blockDepth > 0 {
            if rest.hasPrefix("/*") { blockDepth += 1; i = source.index(i, offsetBy: 2); continue }
            if rest.hasPrefix("*/") { blockDepth -= 1; i = source.index(i, offsetBy: 2); continue }
            if source[i] == "\n" { out.append("\n") }
            i = source.index(after: i)
            continue
        }
        if rest.hasPrefix("/*") { blockDepth = 1; i = source.index(i, offsetBy: 2); continue }
        if rest.hasPrefix("//") {
            while i < source.endIndex, source[i] != "\n" { i = source.index(after: i) }
            continue
        }
        out.append(source[i])
        i = source.index(after: i)
    }
    return out
}

/// Import STATEMENTS only, so a comment may name a banned module freely.
private func importedModules(in source: String) -> [String] {
    stripComments(source).split(separator: "\n", omittingEmptySubsequences: false).compactMap { line in
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("import ") else { return nil }
        let rest = String(trimmed.dropFirst("import ".count)).trimmingCharacters(in: .whitespaces)
        guard let last = rest.split(separator: " ").last else { return nil }
        return String(last.split(separator: ".").first ?? last)
    }
}

@Suite("ALArt seams — the scan can see its own sources")
struct ArtScanSanityTests {

    /// Every other test in this file passes trivially against an empty file
    /// list. This is the one that says the list is not empty.
    @Test("the source scan resolves and finds the whole target")
    func rootResolves() {
        #expect(FileManager.default.fileExists(atPath: alArtRoot.path))
        #expect(swiftFiles(under: alArtRoot).count >= 16)
    }

    @Test("comment stripping removes prose but keeps code")
    func stripperWorks() {
        let source = """
            // default: is banned here
            let a = 1
            /* import UIKit
               default: */
            let b = 2
            """
        let stripped = stripComments(source)
        #expect(!stripped.contains("default:"))
        #expect(!stripped.contains("import UIKit"))
        #expect(stripped.contains("let a = 1"))
        #expect(stripped.contains("let b = 2"))
    }
}

// MARK: - Invariant 7 / the ImageKey twin

@Suite("invariant 7 — the icon switch is total by construction")
struct ExhaustiveSwitchSeamTests {

    /// The whole of invariant 7 rests on `exerciseIconSpec` being a `switch`
    /// with no escape hatch: a `default:` compiles a missing exercise into a
    /// blank badge, and `@unknown default:` does the same while looking
    /// conscientious. Neither may exist in the file.
    ///
    /// Not assertable at runtime: with a `default:` present, every
    /// `ExerciseId.allCases` sweep still passes — it returns *a* spec for every
    /// id. The regression is invisible from inside the process.
    @Test("ExerciseIconCatalog contains no default: and no @unknown default:")
    func iconSwitchHasNoDefault() throws {
        let file = alArtRoot.appendingPathComponent("Icons/ExerciseIconCatalog.swift")
        let source = stripComments(try String(contentsOf: file, encoding: .utf8))
        #expect(!source.contains("default:"), "a default: arm defeats invariant 7")
        #expect(source.contains("func exerciseIconSpec"))
        // …and it really is a switch over the enum, not a dictionary lookup
        // wearing a function's clothes (rule 3 in the file header).
        #expect(source.contains("switch id"))
        #expect(!source.contains("[ExerciseId:"))
    }

    /// `ImageKey` likewise — `WordImages.draw` and `.label` are the same shape
    /// for the same reason.
    @Test("WordImages contains no default: and no @unknown default:")
    func imageKeySwitchHasNoDefault() throws {
        let file = alArtRoot.appendingPathComponent("Images/WordImages.swift")
        let source = stripComments(try String(contentsOf: file, encoding: .utf8))
        #expect(!source.contains("default:"))
        #expect(!source.contains("[ImageKey:"))
        #expect(source.contains("switch key"))
    }

    /// The scans above are negative. This is the positive half: the switches
    /// really are total over the live enums today.
    @Test("every ExerciseId and every ImageKey resolves to a drawing")
    func bothSwitchesAreTotalToday() {
        for id in ExerciseId.allCases {
            #expect(!exerciseIconSpec(id).nodes.isEmpty, "\(id.rawValue) has no glyph")
            #expect(!exerciseIconSpec(id).tint.isEmpty)
        }
        for key in ImageKey.allCases {
            var c = SVGCanvas()
            WordImages.draw(key, into: &c)
            #expect(!c.nodes.isEmpty, "\(key) draws nothing")
        }
    }
}

// MARK: - D1 / D14 — what ALArt is allowed to import and read

@Suite("ALArt seams — imports and the reduced-motion source")
struct ArtImportSeamTests {

    /// ALArt builds for macOS 14 as well as iOS 17, which is what lets the whole
    /// art layer be rasterised by `swift test` on the host with no simulator. A
    /// bare `import UIKit` anywhere in the target takes that away — and takes
    /// the macOS build with it.
    @Test("no file in ALArt imports UIKit or AppKit, guarded or not")
    func noPlatformUIImports() throws {
        for file in swiftFiles(under: alArtRoot) {
            for module in importedModules(in: try String(contentsOf: file, encoding: .utf8)) {
                #expect(
                    module != "UIKit" && module != "AppKit",
                    "\(file.lastPathComponent) imports \(module) — ALArt must build on both platforms")
            }
        }
    }

    /// D14: ONE source of truth for reduced motion. Three independent
    /// `@Environment(\.accessibilityReduceMotion)` reads are three chances to
    /// forget one, and invariant 6 needs all three honoured. ALArt takes it as
    /// an injected `ReduceMotionSource` so the render harness and these tests
    /// can force it on.
    @Test("reduced motion is never read from the SwiftUI environment inside ALArt")
    func reducedMotionComesFromALCore() throws {
        var offenders: [String] = []
        for file in swiftFiles(under: alArtRoot) {
            let source = stripComments(try String(contentsOf: file, encoding: .utf8))
            if source.contains("accessibilityReduceMotion") {
                offenders.append(file.lastPathComponent)
            }
        }
        #expect(offenders.isEmpty, "environment reads in \(offenders) — D14 wants ReduceMotionSource")
    }

    /// The positive half of D14: the motion layer really does take the source as
    /// a parameter, and really does obey it.
    @Test("the injected source is what silences the mascot")
    func injectedSourceIsHonoured() {
        let reduced = FixedReduceMotion(true)
        let normal = FixedReduceMotion(false)
        #expect(MascotMotion.plan(mood: .idle, preview: false, reduceMotion: reduced.isReduced) == nil)
        #expect(MascotMotion.plan(mood: .idle, preview: false, reduceMotion: normal.isReduced) != nil)
        // …and the sheen freezes CENTRED rather than hiding (invariant 6).
        #expect(MascotSheen.offsetUnits(at: 1.2, reduceMotion: true) == 0)
        #expect(MascotSheen.offsetUnits(at: 1.2, reduceMotion: false) != 0)
    }
}

// MARK: - One viewBox mapping, one owner

@Suite("ALArt seams — SVGShape and the canvas agree")
struct ViewBoxMappingSeamTests {

    /// `SVGShape` is the one drawing entry point that is a `Shape` rather than a
    /// `Canvas` (ALUI's root view uses it). It maps a viewBox into the rect
    /// SwiftUI hands it — which is exactly what `SVGCanvas.viewBoxTransform`
    /// does, so the two must not be two different implementations of
    /// `preserveAspectRatio="xMidYMid meet"`. D15 wants one drawing model; two
    /// copies of the mapping is how one of them silently stops matching.
    @Test("SVGShape maps through the same transform the canvas uses")
    func shapeAgreesWithCanvas() {
        let d = "M 0 0 L 100 0 L 100 100 L 0 100 Z"
        let boxes = [
            CGRect(x: 0, y: 0, width: 100, height: 100),
            CGRect(x: 0, y: 0, width: 32, height: 32),
            CGRect(x: 10, y: -5, width: 128, height: 64),
        ]
        let rects = [
            CGRect(x: 0, y: 0, width: 100, height: 100),
            CGRect(x: 0, y: 0, width: 240, height: 120),  // wider than tall
            CGRect(x: 0, y: 0, width: 60, height: 300),  // taller than wide
            CGRect(x: 12, y: 30, width: 88, height: 88),  // offset origin
        ]
        for box in boxes {
            for rect in rects {
                let viaShape = SVGShape(d, viewBox: box).path(in: rect).boundingRect
                let viaCanvas = Path(svg: d)
                    .applying(SVGCanvas.viewBoxTransform(box, fitting: rect))
                    .boundingRect
                #expect(abs(viaShape.minX - viaCanvas.minX) < 1e-9, "\(box) → \(rect)")
                #expect(abs(viaShape.minY - viaCanvas.minY) < 1e-9, "\(box) → \(rect)")
                #expect(abs(viaShape.width - viaCanvas.width) < 1e-9, "\(box) → \(rect)")
                #expect(abs(viaShape.height - viaCanvas.height) < 1e-9, "\(box) → \(rect)")
            }
        }
    }

    /// The behaviour that mapping is defined by: uniform scale (no stretch) and
    /// centred on both axes.
    @Test("the fit is uniform and centred, never stretched")
    func fitIsMeetNotSlice() {
        let square = SVGShape("M 0 0 L 100 0 L 100 100 L 0 100 Z", viewBox: CGRect(x: 0, y: 0, width: 100, height: 100))
        let wide = square.path(in: CGRect(x: 0, y: 0, width: 200, height: 100)).boundingRect
        // Fits the short axis, stays square, and is centred on the long one.
        #expect(abs(wide.width - 100) < 1e-9)
        #expect(abs(wide.height - 100) < 1e-9)
        #expect(abs(wide.midX - 100) < 1e-9)
        #expect(abs(wide.midY - 50) < 1e-9)
    }

    /// A degenerate viewBox must not divide by zero or vanish.
    @Test("a zero-sized viewBox falls back to the unmapped path")
    func degenerateViewBox() {
        let path = SVGShape("M 0 0 L 10 10", viewBox: CGRect(x: 0, y: 0, width: 0, height: 0))
            .path(in: CGRect(x: 0, y: 0, width: 50, height: 50))
        #expect(!path.isEmpty)
        #expect(path.boundingRect.width == 10)
    }
}

// MARK: - Invariant 6 — the five French labels

@Suite("invariant 6 — the mascot's accessibility labels")
struct MascotLabelSeamTests {

    /// Byte-for-byte from `src/mascot/Mascot.tsx`:
    ///
    /// ```ts
    /// const LABELS: Record<Species, string> = {
    ///   unicorn: "Ma licorne",
    ///   cat: "Mon chat",
    ///   fox: "Mon renard",
    ///   rabbit: "Mon lapin",
    ///   dragon: "Mon dragon",
    /// };
    /// ```
    ///
    /// Hand-written, not derived from `MascotRig.labels` — a derived oracle
    /// agrees with any edit and cannot fail. All five are plain ASCII; there is
    /// no apostrophe in this table, so unlike the hub copy there is no `’` vs
    /// `'` question to get wrong (D17's second half).
    @Test("all five labels are byte-identical to Mascot.tsx")
    func labelsAreFrozen() {
        #expect(MascotRig.label(for: .unicorn) == "Ma licorne")
        #expect(MascotRig.label(for: .cat) == "Mon chat")
        #expect(MascotRig.label(for: .fox) == "Mon renard")
        #expect(MascotRig.label(for: .rabbit) == "Mon lapin")
        #expect(MascotRig.label(for: .dragon) == "Mon dragon")
    }

    /// The table is total over the closed enum, so the `?? ""` fallback in
    /// `label(for:)` is unreachable. If a species is ever added without a label
    /// this is what goes red — an empty `aria-label` is a silent accessibility
    /// regression no rendering test would notice.
    @Test("every species has a non-empty label and no two share one")
    func labelsAreTotalAndDistinct() {
        let all = Species.allCases.map(MascotRig.label(for:))
        #expect(all.allSatisfy { !$0.isEmpty })
        #expect(Set(all).count == Species.allCases.count)
        #expect(MascotRig.labels.count == Species.allCases.count)
    }

    /// And the label really reaches the view, not just the table.
    @Test("the rendered mascot carries its species' label")
    func viewIsLabelled() {
        for species in Species.allCases {
            let view = MascotRigView(
                config: MascotConfig(species: species, stage: 3, colors: [:], styles: [:], accessories: []),
                mood: .idle
            )
            #expect(MascotRig.label(for: view.config.species) == MascotRig.label(for: species))
        }
    }
}

// MARK: - Every species draws

/// Flatten to the leaves that actually put ink on the canvas.
private func leaves(_ nodes: [SVGDrawNode]) -> [(path: Path, transform: CGAffineTransform)] {
    nodes.flatMap { node -> [(path: Path, transform: CGAffineTransform)] in
        switch node {
        case let .fill(path, t, _, _): return [(path, t)]
        case let .stroke(path, t, _, _, _): return [(path, t)]
        case let .group(_, children): return leaves(children)
        case let .mask(matte, content): return leaves(matte) + leaves(content)
        }
    }
}

@Suite("Mascot rig — all five species, every stage")
struct MascotRigSeamTests {

    private func list(_ species: Species, stage: Int, mood: Mood = .idle, preview: Bool = false) -> [SVGDrawNode] {
        MascotRig.drawList(
            config: MascotConfig(species: species, stage: stage, colors: [:], styles: [:], accessories: []),
            mood: mood,
            preview: preview
        )
    }

    /// `DragonRigTests` covers the dragon in depth; nothing covered the other
    /// four at all. This is the floor: every species, every one of the ten
    /// growth stages, draws real parsed geometry inside a plausible box.
    @Test("every species × stage draws parsed geometry in a plausible box")
    func everySpeciesDraws() {
        for species in Species.allCases {
            for stage in 0..<growthStages {
                let ls = leaves(list(species, stage: stage))
                #expect(!ls.isEmpty, "\(species) stage \(stage) drew nothing")

                var union = CGRect.null
                for leaf in ls {
                    // A `d` the parser rejected yields an empty Path.
                    #expect(!leaf.path.isEmpty, "\(species) stage \(stage): a leaf has no geometry")
                    union = union.union(leaf.path.boundingRect.applying(leaf.transform))
                }
                #expect(!union.isNull && !union.isInfinite, "\(species) stage \(stage): bad bounds")
                // Overflow past the 100-unit viewBox is legitimate and expected
                // — D15 is explicit that top-stage wings and haloes leave the
                // box, which is why `SVGCanvas` never clips at the viewBox. It
                // is still bounded. Measured envelope over all 50 combinations:
                // x ∈ [−55.04, 155.04], y ∈ [−80.27, 112.98], the low y being
                // the unicorn's stage-9 halo. The margin below is deliberately
                // just wide enough to hold that and no wider — a part that
                // escapes to 1e3 is a transform bug, and NaN fails `isInfinite`
                // above.
                #expect(union.minX > -90 && union.maxX < 190, "\(species) stage \(stage): \(union)")
                #expect(union.minY > -90 && union.maxY < 190, "\(species) stage \(stage): \(union)")
            }
        }
    }

    /// A leaf-count snapshot, species × stage, in the shape of the one
    /// `DragonRigTests` keeps for the dragon's wardrobe.
    ///
    /// This is the test that notices a part quietly disappearing. Every other
    /// assertion here is a floor ("something drew, and it was in bounds"), and a
    /// mascot that lost an ear still clears every one of them. The numbers are
    /// the default look — no colours, no styles, no accessories — at each of the
    /// ten growth stages, and they are expected to change when a rig changes:
    /// re-measure and update the row ON PURPOSE, the way the dragon's table is
    /// maintained.
    @Test("leaf counts per species per stage match the frozen table")
    func leafCountSnapshot() {
        let frozen: [Species: [Int]] = [
            .unicorn: [30, 34, 41, 49, 50, 55, 58, 81, 94, 108],
            .cat: [31, 35, 39, 43, 47, 65, 67, 68, 71, 97],
            .fox: [37, 41, 45, 49, 61, 73, 79, 108, 112, 144],
            .rabbit: [37, 41, 45, 48, 50, 51, 54, 57, 60, 72],
            .dragon: [28, 32, 33, 41, 47, 52, 57, 74, 84, 91],
        ]
        #expect(frozen.count == Species.allCases.count)
        for species in Species.allCases {
            let expected = frozen[species] ?? []
            #expect(expected.count == growthStages, "\(species): table is the wrong length")
            let actual = (0..<growthStages).map { leaves(list(species, stage: $0)).count }
            #expect(actual == expected, "\(species) drew \(actual)")
            // Growth only ever adds: every stage draws at least as much as the
            // one before it. True of all five timelines today, and a rig that
            // breaks it has almost certainly lost a part at the wrong stage.
            #expect(zip(actual, actual.dropFirst()).allSatisfy { $0 <= $1 }, "\(species) shrinks: \(actual)")
        }
    }

    /// The ground shadow is the first thing `MascotRig.draw` records, before the
    /// scaled rig group — painter's order, and it is outside the growth scale.
    @Test("every mascot opens with its 10%-black ground shadow")
    func shadowFirst() {
        for species in Species.allCases {
            let first = list(species, stage: 5).first
            guard case let .fill(_, _, _, paint)? = first else {
                Issue.record("\(species): first node is not a fill")
                continue
            }
            // `SVGPaint` is not Equatable (a gradient's stops are not), so the
            // assertion is on the property the shadow is defined by.
            #expect(paint.opacity == 0.1, "\(species)'s shadow lost its fill-opacity")
        }
    }

    /// The rainbow overlay is the one mask in the app, and it must only appear
    /// for the premium accessory that buys it.
    @Test("only the star-clip premium adds the masked rainbow sheen")
    func rainbowIsGated() {
        func hasMask(_ nodes: [SVGDrawNode]) -> Bool {
            nodes.contains { node in
                switch node {
                case .mask: return true
                case let .group(_, children): return hasMask(children)
                default: return false
                }
            }
        }
        let plain = MascotRig.drawList(
            config: MascotConfig(species: .unicorn, stage: 6, colors: [:], styles: [:], accessories: []),
            mood: .idle)
        let premium = MascotRig.drawList(
            config: MascotConfig(
                species: .unicorn, stage: 6, colors: [:], styles: [:],
                accessories: [Accessory.Unicorn.starClip]),
            mood: .idle)
        #expect(!hasMask(plain))
        #expect(hasMask(premium))
        #expect(RAINBOW_IDS == [Accessory.Unicorn.starClip])
    }

    /// Preview pins the growth scale to 1 so a shop tile's `focus` crop lines up
    /// with raw layout coordinates. At a stage whose scale is not 1, that has to
    /// change the recorded transform.
    @Test("preview pins the growth scale to 1")
    func previewPinsScale() {
        let stage = growthStages - 1
        #expect(Growth.stageScale(stage) != 1, "pick a stage whose scale is not already 1")
        for species in Species.allCases {
            let normal = leaves(list(species, stage: stage, preview: false))
            let preview = leaves(list(species, stage: stage, preview: true))
            #expect(normal.count > 0 && preview.count > 0)
            // The shadow is node 0 in both and is outside the scaled group, so
            // compare a leaf that is inside it.
            #expect(
                normal.last?.transform != preview.last?.transform,
                "\(species): preview did not change the CTM")
        }
    }
}
