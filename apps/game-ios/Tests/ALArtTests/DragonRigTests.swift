import CoreGraphics
import SwiftUI
import Testing
@testable import ALArt
import ALCore

// Tests for the « Braise » dragon port (Dragon.swift + DragonParts.swift).
//
// Strategy: build the recorded draw list for every stage × wardrobe the dragon
// supports and assert (1) it draws something, (2) every leaf path parsed (an
// unparseable `d` yields an empty Path), (3) the union bounding box is finite
// and plausible for the 0–100 viewBox (overflow allowed — wings at the top
// stages), and (4) a leaf-count snapshot per configuration, so a refactor that
// silently drops a part fails loudly.

// MARK: - Helpers

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

private func dragonConfig(
    stage: Int,
    styles: [String: String] = [:],
    accessories: [String] = []
) -> MascotConfig {
    MascotConfig(species: .dragon, stage: stage, colors: [:], styles: styles, accessories: accessories)
}

private func dragonList(
    stage: Int,
    styles: [String: String] = [:],
    accessories: [String] = [],
    mood: Mood = .idle,
    preview: Bool = false
) -> [SVGDrawNode] {
    var c = SVGCanvas()
    drawDragonRig(
        into: &c,
        config: dragonConfig(stage: stage, styles: styles, accessories: accessories),
        layout: Growth.layoutFor(Double(stage)),
        stage: stage,
        mood: mood,
        preview: preview
    )
    return c.nodes
}

private func leafCount(
    stage: Int,
    styles: [String: String] = [:],
    accessories: [String] = [],
    mood: Mood = .idle,
    preview: Bool = false
) -> Int {
    leaves(dragonList(stage: stage, styles: styles, accessories: accessories, mood: mood, preview: preview)).count
}

private let ALL_ACCESSORIES = [
    Accessory.Dragon.cape,
    Accessory.Dragon.goggles,
    Accessory.Dragon.fang,
    Accessory.Dragon.treasure,
    Accessory.Dragon.blueFlame,
]

/// Every wardrobe axis the dragon supports (spec §8.3), exercised one at a time
/// plus the everything-on look.
private let WARDROBES: [(name: String, styles: [String: String], accessories: [String])] = [
    ("base", [:], []),
    ("horns-double", [StyleSlot.Dragon.horn: "double"], []),
    ("crest-lava", [StyleSlot.Dragon.crest: "lava"], []),
    ("tail-club", [StyleSlot.Dragon.tail: "club"], []),
    ("tail-flame", [StyleSlot.Dragon.tail: "flame"], []),
    ("cape", [:], [Accessory.Dragon.cape]),
    ("goggles", [:], [Accessory.Dragon.goggles]),
    ("fang", [:], [Accessory.Dragon.fang]),
    ("treasure", [:], [Accessory.Dragon.treasure]),
    ("blue-flame", [:], [Accessory.Dragon.blueFlame]),
    (
        "all",
        [
            StyleSlot.Dragon.horn: "double",
            StyleSlot.Dragon.crest: "lava",
            StyleSlot.Dragon.tail: "flame",
        ],
        ALL_ACCESSORIES
    ),
]

// MARK: - Matrix smoke + geometry sanity

@Suite("Dragon rig — draw-list matrix")
struct DragonRigMatrixTests {

    @Test("every stage × wardrobe × preview draws, parses and stays in plausible bounds")
    func matrix() {
        for stage in 0...9 {
            for wardrobe in WARDROBES {
                for preview in [false, true] {
                    let list = dragonList(
                        stage: stage,
                        styles: wardrobe.styles,
                        accessories: wardrobe.accessories,
                        preview: preview
                    )
                    let ls = leaves(list)
                    #expect(!ls.isEmpty, "empty draw list at stage \(stage) \(wardrobe.name) preview=\(preview)")

                    var union = CGRect.null
                    for leaf in ls {
                        // An unparseable `d` string yields an empty Path (SVGPath's
                        // non-throwing init) — every leaf must carry real geometry.
                        #expect(!leaf.path.isEmpty, "empty path at stage \(stage) \(wardrobe.name)")
                        union = union.union(leaf.path.boundingRect.applying(leaf.transform))
                    }
                    #expect(!union.isNull)
                    #expect(union.minX.isFinite && union.minY.isFinite && union.width.isFinite && union.height.isFinite)
                    // Authored in the 0–100 viewBox; wings/aura overflow by design
                    // but nothing sane leaves this envelope.
                    #expect(union.minX > -40 && union.maxX < 140, "x out of range at stage \(stage) \(wardrobe.name): \(union)")
                    #expect(union.minY > -40 && union.maxY < 140, "y out of range at stage \(stage) \(wardrobe.name): \(union)")
                    #expect(union.width > 20 && union.height > 20, "implausibly small at stage \(stage) \(wardrobe.name): \(union)")
                }
            }
        }
    }

    @Test("moods redraw the face without trapping, and change the list")
    func moods() {
        for stage in [0, 1, 4, 9] {
            let idle = leafCount(stage: stage, mood: .idle)
            let happy = leafCount(stage: stage, mood: .happy)
            let cheer = leafCount(stage: stage, mood: .cheer)
            #expect(idle > 0 && happy > 0 && cheer > 0)
            // idle eyes are pupil stacks (6 fills), happy/cheer arcs/stars (2) —
            // the counts must differ somewhere across moods.
            #expect(!(idle == happy && happy == cheer), "moods drew identical lists at stage \(stage)")
        }
    }
}

// MARK: - Behaviour pinned by the TSX

@Suite("Dragon rig — ported behaviour")
struct DragonRigBehaviourTests {

    @Test("the blue-flame breath is gated at stage 4 (ramp clamps below its first stop)")
    func blueFlameGate() {
        // Below the gate the premium must change NOTHING (spec.flame is 0 and
        // the guard stops the ramp leak).
        #expect(
            leafCount(stage: 3, accessories: [Accessory.Dragon.blueFlame]) == leafCount(stage: 3)
        )
        // At stage 4 the blue breath appears even though spec.flame is 0.
        #expect(
            leafCount(stage: 4, accessories: [Accessory.Dragon.blueFlame]) > leafCount(stage: 4)
        )
        // At stage 7+ the legendary double breath adds a second puff (2 fills)
        // over the single blue breath, plus the ember floor of 4.
        #expect(
            leafCount(stage: 7, accessories: [Accessory.Dragon.blueFlame]) >
            leafCount(stage: 7) + 2
        )
    }

    @Test("preview strips the per-stage magic (and the egg)")
    func previewStripping() {
        // Stage 0 preview: no egg → falls through to the lying branch.
        #expect(leafCount(stage: 0, preview: true) != leafCount(stage: 0))
        // Stage 9 preview: no flame/aura/ember/cracks/smoke/ground/claws/fangs.
        #expect(leafCount(stage: 9, preview: true) < leafCount(stage: 9))
        // But sold styles/accessories stay visible in the ghost.
        #expect(
            leafCount(stage: 9, accessories: [Accessory.Dragon.cape], preview: true) >
            leafCount(stage: 9, preview: true)
        )
    }

    @Test("goggles obey the rig's own stage ≥ 3 gate, independent of the catalog")
    func gogglesGate() {
        #expect(leafCount(stage: 2, accessories: [Accessory.Dragon.goggles]) == leafCount(stage: 2))
        #expect(leafCount(stage: 3, accessories: [Accessory.Dragon.goggles]) > leafCount(stage: 3))
    }

    @Test("a SpadeTail without an edge records no zero-width tip stroke")
    func spadeTailZeroWidthStroke() {
        func strokes(_ nodes: [SVGDrawNode]) -> Int {
            nodes.reduce(0) { acc, n in
                switch n {
                case .stroke: return acc + 1
                case .fill: return acc
                case let .group(_, ch): return acc + strokes(ch)
                case let .mask(m, c): return acc + strokes(m) + strokes(c)
                }
            }
        }
        var edged = SVGCanvas()
        drawSpadeTail(into: &edged, p0: (10, 90), p1: (30, 80), p2: (30, 60), w: 5, color: "#7DB874", edge: "#5A3A1E")
        // edge underlay + colour curve + tip outline
        #expect(strokes(edged.nodes) == 3)

        var bare = SVGCanvas()
        drawSpadeTail(into: &bare, p0: (10, 90), p1: (30, 80), p2: (30, 60), w: 5, color: "#7DB874")
        // colour curve only — strokeWidth={edge ? 1 : 0} must record nothing.
        #expect(strokes(bare.nodes) == 1)
    }

    @Test("an unknown tailStyle draws the curve with no tip, like the TSX cast")
    func unknownTailStyle() {
        // Same list as club/flame minus the tip: strictly fewer leaves than any
        // known tip, but still a full dragon.
        let unknown = leafCount(stage: 5, styles: [StyleSlot.Dragon.tail: "mystery"])
        let spade = leafCount(stage: 5)
        #expect(unknown > 0 && unknown < spade)
    }

    @Test("the flame tail stays a spade before stage 2, outside preview")
    func flameTailLightsUpAtTwo() {
        let flame = [StyleSlot.Dragon.tail: "flame"]
        // Stages 0–1: same list as the default spade tail.
        #expect(leafCount(stage: 0, styles: flame) == leafCount(stage: 0))
        #expect(leafCount(stage: 1, styles: flame) == leafCount(stage: 1))
        // From stage 2 the tip is a flame (circle + 2-fill puff ≠ spade fill).
        #expect(leafCount(stage: 2, styles: flame) != leafCount(stage: 2))
        // In preview the flame tail shows even at stage 1 (it is what the tile sells).
        #expect(leafCount(stage: 1, styles: flame, preview: true) != leafCount(stage: 1, preview: true))
    }

    @Test("the treasure hoard only ever grows with the stage")
    func treasureMonotone() {
        var previous = 0
        for stage in 0...9 {
            var c = SVGCanvas()
            drawTreasure(into: &c, stage: stage, x: 0, groundY: 95)
            let n = leaves(c.nodes).count
            #expect(n >= previous, "treasure shrank at stage \(stage): \(n) < \(previous)")
            previous = n
        }
    }

    @Test("T_STAGES: the chest arrives at stage 7 and element size never changes")
    func treasureSpec() {
        for (stage, spec) in T_STAGES.enumerated() {
            #expect(spec.chest == (stage >= 7))
            #expect(spec.ring == (stage >= 3))
            #expect(spec.crown == (stage >= 5))
            #expect(spec.beads == (stage >= 8))
        }
        #expect(T_STAGES.map(\.coins) == [1, 1, 1, 3, 4, 5, 6, 7, 9, 12])
        #expect(T_STAGES.map(\.gems) == [0, 0, 0, 0, 0, 0, 0, 2, 3, 5])
        #expect(T_STAGES.map(\.spill) == [0, 0, 0, 0, 0, 0, 0, 1, 2, 3])
        // seats for the crown
        #expect(heapTopY(1) == -1.1)
        #expect(heapTopY(3) == -2.9)
        #expect(heapTopY(8) == -4.7)
        #expect(heapTopY(13) == -6.5)
    }

    @Test("coin rows sort back-to-front with STABLE ties, like JS sort")
    func stableCoinSort() {
        // CHEST_SLOTS rows tie on dy; authored order must survive the sort or
        // overlapping coins shingle the wrong way (left drawn first, right on top).
        let sorted = stableSortedByDY(Array(CHEST_SLOTS.prefix(5)))
        #expect(sorted.map(\.0) == [0, -2.9, 2.9, -5.4, 5.4])
        #expect(sorted.map(\.1) == [-0.4, -0.3, -0.3, -0.1, -0.1])
        // COIN_SLOTS row 1 ties four ways.
        let mound = stableSortedByDY(Array(COIN_SLOTS.prefix(5)))
        #expect(mound.map(\.0) == [-1.45, 1.45, 0, -2.9, 2.9])
    }

    @Test("the lying and egg branches never touch layout.legs")
    func lyingBranchesSafe() {
        // Stages 0–1 have legs == [] — the treasure clamp indexes legs[0] and
        // must be unreachable there (guarded by the standing branch).
        for stage in [0, 1] {
            let n = leafCount(stage: stage, accessories: [Accessory.Dragon.treasure])
            #expect(n > leafCount(stage: stage))
        }
    }
}

// MARK: - Leaf-count snapshot

// Captured from the first green run and frozen: a refactor that silently drops
// (or duplicates) a part changes one of these numbers and fails loudly. The
// key is "s<stage>-<wardrobe>".
private let LEAF_SNAPSHOT: [String: Int] = [
    "s0-all": 35,
    "s0-base": 27,
    "s0-preview": 24,
    "s0-treasure": 35,
    "s1-all": 39,
    "s1-base": 31,
    "s1-preview": 24,
    "s1-treasure": 39,
    "s2-all": 56,
    "s2-base": 32,
    "s2-preview": 32,
    "s2-treasure": 40,
    "s3-all": 88,
    "s3-base": 40,
    "s3-preview": 40,
    "s3-treasure": 64,
    "s4-all": 100,
    "s4-base": 46,
    "s4-preview": 46,
    "s4-treasure": 74,
    "s5-all": 116,
    "s5-base": 51,
    "s5-preview": 51,
    "s5-treasure": 90,
    "s6-all": 123,
    "s6-base": 56,
    "s6-preview": 51,
    "s6-treasure": 99,
    "s7-all": 176,
    "s7-base": 73,
    "s7-preview": 53,
    "s7-treasure": 149,
    "s8-all": 218,
    "s8-base": 83,
    "s8-preview": 53,
    "s8-treasure": 192,
    "s9-all": 249,
    "s9-base": 90,
    "s9-preview": 55,
    "s9-treasure": 223,
]

@Suite("Dragon rig — leaf-count snapshot")
struct DragonRigSnapshotTests {

    @Test("leaf counts match the frozen table")
    func snapshot() {
        var actual: [String: Int] = [:]
        for stage in 0...9 {
            for wardrobe in [WARDROBES[0], WARDROBES[8], WARDROBES[10]] { // base, treasure, all
                actual["s\(stage)-\(wardrobe.name)"] = leafCount(
                    stage: stage, styles: wardrobe.styles, accessories: wardrobe.accessories
                )
            }
            actual["s\(stage)-preview"] = leafCount(stage: stage, preview: true)
        }
        if LEAF_SNAPSHOT.isEmpty {
            // Bootstrap: print the table to be frozen into LEAF_SNAPSHOT.
            let dump = actual.sorted { $0.key < $1.key }
                .map { "    \"\($0.key)\": \($0.value)," }
                .joined(separator: "\n")
            print("LEAF_SNAPSHOT bootstrap:\n\(dump)")
            Issue.record("LEAF_SNAPSHOT is empty — freeze the printed table")
            return
        }
        for (key, expected) in LEAF_SNAPSHOT {
            #expect(actual[key] == expected, "\(key): got \(actual[key] ?? -1), expected \(expected)")
        }
        #expect(actual.count == LEAF_SNAPSHOT.count)
    }
}

// MARK: - Rasterisation smoke

#if canImport(AppKit) || canImport(UIKit)

@Suite("Dragon rig — rasterisation", .serialized)
@MainActor
struct DragonRasterTests {

    @Test("stage 9 in full wardrobe rasterises to visible pixels")
    func stageNineDrawsPixels() throws {
        let renderer = ImageRenderer(
            content: Canvas { context, _ in
                var canvas = SVGCanvas(
                    viewBox: CGRect(x: 0, y: 0, width: 100, height: 100),
                    fitting: CGRect(x: 0, y: 0, width: 200, height: 200)
                )
                drawDragonRig(
                    into: &canvas,
                    config: dragonConfig(
                        stage: 9,
                        styles: [StyleSlot.Dragon.crest: "lava"],
                        accessories: ALL_ACCESSORIES
                    ),
                    layout: Growth.layoutFor(9),
                    stage: 9,
                    mood: .idle
                )
                var ctx = context
                canvas.render(into: &ctx)
            }
            .frame(width: 200, height: 200)
        )
        renderer.scale = 1
        let cg = try #require(renderer.cgImage)
        var buffer = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        let ctx = try #require(CGContext(
            data: &buffer, width: cg.width, height: cg.height, bitsPerComponent: 8,
            bytesPerRow: cg.width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        let opaque = stride(from: 3, to: buffer.count, by: 4).filter { buffer[$0] > 16 }.count
        // The dragon fills a healthy share of a 200×200 canvas.
        #expect(opaque > 4000, "only \(opaque) visible pixels")
    }
}

#endif
