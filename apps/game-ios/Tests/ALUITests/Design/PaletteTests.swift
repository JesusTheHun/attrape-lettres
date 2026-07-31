import SwiftUI
import Testing

@testable import ALUI

/* -------------------------------------------------------------------------- */
/* Every hex asserted here was read out of the TSX / index.css, not out of      */
/* Palette.swift. The point of the file is that a token drifting from its       */
/* source literal fails loudly instead of shipping a slightly-off brown.        */
/* -------------------------------------------------------------------------- */

@Suite("Palette — the fixed chrome's hexes")
struct PaletteTests {

    @Test("the page mat")
    func page() {
        #expect(Palette.page.hex == "#efe6da")  // index.css, body
    }

    /// TWO stage gradients exist and the difference is a single stop position:
    /// the play surfaces break the cream at 38 %, the adult/roster/shop ones at
    /// 40 %. Unifying them would be a silent redesign of five screens.
    @Test("both stage gradients, including the 38 % / 40 % split")
    func stageGradients() {
        #expect(Palette.stage.degrees == 180)
        #expect(Palette.stage.stops.map(\.hex) == ["#FFE7C9", "#FFEFD6", "#DCEFFB"])
        #expect(Palette.stage.stops.map(\.location) == [0.0, 0.38, 1.0])

        #expect(Palette.stageAdult.degrees == 180)
        #expect(Palette.stageAdult.stops.map(\.hex) == ["#FFE7C9", "#FFEFD6", "#DCEFFB"])
        #expect(Palette.stageAdult.stops.map(\.location) == [0.0, 0.40, 1.0])

        #expect(Palette.stage != Palette.stageAdult)
    }

    @Test("ink")
    func ink() {
        #expect(Palette.ink.hex == "#5A3A1E")
        #expect(Palette.inkSoft.hex == "#7A5A3A")
        #expect(Palette.inkFaint.hex == "#9A7A5A")
        #expect(Palette.inkProse.hex == "#6B4A2C")
        #expect(Palette.inkQuiet.hex == "#8A6A4A")
        #expect(Palette.inkGate.hex == "#7A5B3C")
        #expect(Palette.inkUnaffordable.hex == "#8A7B69")
    }

    @Test("gold — the reward vocabulary")
    func gold() {
        #expect(Palette.goldInk.hex == "#4A3B00")
        #expect(Palette.goldPill.degrees == 180)
        #expect(Palette.goldPill.stops.map(\.hex) == ["#FFDE6B", "#FFC107"])
        #expect(Palette.goldPill.stops.map(\.location) == [0.0, 1.0])
        #expect(Palette.goldLip.hex == "#E0A800")
        #expect(Palette.jackpot.hex == "#FFC107")
        #expect(Palette.coinInk.hex == "#B07A00")
        #expect(Palette.coinRing.hex == "#FFE08A")
        #expect(Palette.wallet.hex == "#FFD54F")
        #expect(Palette.savingsFill.degrees == 90)
        #expect(Palette.savingsFill.stops.map(\.hex) == ["#FFC107", "#FFD54F"])
        #expect(Palette.shopEquipped.hex == "#FFF6E0")
        #expect(Palette.shopTrying.hex == "#FFF9EB")
        #expect(Palette.tryRing.hex == "#FFB300")
    }

    @Test("green — the go vocabulary")
    func green() {
        #expect(Palette.green.hex == "#66BB6A")
        #expect(Palette.greenLip.hex == "#43A047")
        #expect(Palette.growthFill.degrees == 90)
        #expect(Palette.growthFill.stops.map(\.hex) == ["#AED581", "#66BB6A"])
        #expect(Palette.growthTrack.hex == "#E9DCC7")
        #expect(Palette.equippedInk.hex == "#3E7B3E")
        #expect(Palette.currentBadge.hex == "#E6F4E6")
        #expect(Palette.currentBadgeInk.hex == "#2E7D32")
        #expect(Palette.growthCard.degrees == 135)
        #expect(Palette.growthCard.stops.map(\.hex) == ["#E9F9E0", "#D6F0FB"])
        #expect(Palette.wardrobeZone.degrees == 160)
        #expect(Palette.wardrobeZone.stops.map(\.hex) == ["#EAF7E0", "#F4FBEC"])
        #expect(Palette.storeZone.degrees == 160)
        #expect(Palette.storeZone.stops.map(\.hex) == ["#E2F0FC", "#EBF5FE"])
    }

    @Test("slots, borders, disabled")
    func chrome() {
        #expect(Palette.slotDashed.hex == "#E4A15E")
        #expect(Palette.slotDashedLocked.hex == "#C9A87A")
        #expect(Palette.slotRevealed.hex == "#FFF3E0")
        #expect(Palette.disabled.hex == "#B8A98E")
        #expect(Palette.ghost.hex == "#DADCE4")
        #expect(Palette.previewPlate.hex == "#F1F0F5")
    }

    @Test("the adult surfaces")
    func adult() {
        #expect(Palette.gateCard.hex == "#FFFDF8")
        #expect(Palette.gateField.hex == "#E6D8C6")
        #expect(Palette.gateFieldWrong.hex == "#E5736A")
        #expect(Palette.gateError.hex == "#C4544A")
        #expect(Palette.adultSecondary.hex == "#F0E6D8")
        #expect(Palette.destructive.hex == "#EF5350")
    }

    /* ---- Pick tiles ------------------------------------------------------- */

    @Test("TILE_COLORS, in the order the single-pick exercises rotate through")
    func tileColors() {
        #expect(Palette.tileColors.map(\.bg.hex) == ["#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8"])
        #expect(Palette.tileColors.map(\.ink.hex) == ["#4A2317", "#4A3B00", "#062E3D", "#213606", "#2C1846"])
    }

    /// The tray ramp starts on blue, not orange. A tray tile and a pick tile at
    /// the same index are therefore DIFFERENT colours — authored that way in
    /// four engines, so the two arrays must not be collapsed into one.
    @Test("TRAY_COLORS is the same five paints in a different rotation")
    func trayColors() {
        #expect(Palette.trayColors.map(\.bg.hex) == ["#4FC3F7", "#AED581", "#FFD54F", "#BA9EE8", "#FF8A65"])
        #expect(Palette.trayColors.map(\.ink.hex) == ["#062E3D", "#213606", "#4A3B00", "#2C1846", "#4A2317"])

        #expect(Set(Palette.trayColors) == Set(Palette.tileColors))
        #expect(Palette.trayColors != Palette.tileColors)
        #expect(Palette.trayColors[0] != Palette.tileColors[0])
    }

    /// `SyllableGridExercise` is the one engine with a fourth colour of its own:
    /// a lighter green on a darker ink than every other exercise's fourth tile.
    @Test("the syllable grid's fourth tile is its own green")
    func gridTileColors() {
        #expect(Palette.gridTileColors.count == 4)
        #expect(Array(Palette.gridTileColors.prefix(3)) == Array(Palette.tileColors.prefix(3)))
        #expect(Palette.gridTileColors[3].bg.hex == "#A5D6A7")
        #expect(Palette.gridTileColors[3].ink.hex == "#123B18")
        #expect(Palette.gridTileColors[3] != Palette.tileColors[3])
    }

    /// Every pick tile must be readable: the whole point of the (bg, ink) pair.
    /// A dark ink on a light face, never the reverse.
    @Test("every tile paint pairs a light face with a dark ink")
    func tilesAreLegible() {
        for paint in Palette.tileColors + Palette.trayColors + Palette.gridTileColors {
            #expect(
                luminance(paint.bg.hex) > luminance(paint.ink.hex) + 0.4,
                Comment(rawValue: "\(paint.bg.hex) on \(paint.ink.hex) has too little contrast")
            )
        }
    }

    /* ---- Translucent whites and the greyed star --------------------------- */

    @Test("the white opacities in use")
    func whites() {
        #expect(Palette.White.o55 == 0.55)
        #expect(Palette.White.o70 == 0.70)
        #expect(Palette.White.o80 == 0.80)
        #expect(Palette.White.o85 == 0.85)
        #expect(Palette.White.o90 == 0.90)
        #expect(Palette.White.o92 == 0.92)
        #expect(Palette.White.o95 == 0.95)
    }

    /// Invariant 8's visible half: a lost star greys but stays VISIBLE — the
    /// round still counts as played. Dropping the opacity to 0 would erase it.
    @Test("LOST greys the star without hiding it")
    func lostStar() {
        #expect(Palette.Lost.saturation == 0)
        #expect(Palette.Lost.opacity == 0.45)
        #expect(Palette.Lost.opacity > 0)
        #expect(Palette.liveStarOpacity == 0.8)
        #expect(Palette.futureDotOpacity == 0.28)
        // The live star must read as brighter than a lost one, and a future dot
        // as fainter than both.
        #expect(Palette.liveStarOpacity > Palette.Lost.opacity)
        #expect(Palette.futureDotOpacity < Palette.Lost.opacity)
    }

    /* ---- Gradient geometry ------------------------------------------------ */

    /// CSS 180° runs top → bottom; 90° left → right. Getting the sign of the y
    /// term wrong flips every screen's wash upside down and is invisible in a
    /// hex assertion.
    @Test("CSS angles map to the right SwiftUI unit points")
    func gradientDirection() {
        let down = HexGradient(degrees: 180, [HexStop("#000000", 0), HexStop("#FFFFFF", 1)])
        #expect(unitNear(HexGradient.startPoint(down.degrees), UnitPoint(x: 0.5, y: 0)))
        #expect(unitNear(HexGradient.endPoint(down.degrees), UnitPoint(x: 0.5, y: 1)))

        let right = HexGradient(degrees: 90, [HexStop("#000000", 0), HexStop("#FFFFFF", 1)])
        #expect(unitNear(HexGradient.startPoint(right.degrees), UnitPoint(x: 0, y: 0.5)))
        #expect(unitNear(HexGradient.endPoint(right.degrees), UnitPoint(x: 1, y: 0.5)))

        // 135° is down-and-right: the end point must be past the centre on both
        // axes, and further right than down is not required — only the sign is.
        let diagonal = HexGradient.endPoint(135)
        #expect(diagonal.x > 0.5)
        #expect(diagonal.y > 0.5)
    }
}

/* -------------------------------------------------------------------------- */

private func unitNear(_ a: UnitPoint, _ b: UnitPoint) -> Bool {
    abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9
}

/// Rec. 709 relative luminance of a `#RRGGBB` string, 0…1.
private func luminance(_ hex: String) -> Double {
    var s = hex
    if s.hasPrefix("#") { s.removeFirst() }
    let chars = Array(s)
    guard chars.count == 6 else { return 0 }
    func channel(_ i: Int) -> Double {
        Double(UInt8(String(chars[i...(i + 1)]), radix: 16) ?? 0) / 255
    }
    return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
}
