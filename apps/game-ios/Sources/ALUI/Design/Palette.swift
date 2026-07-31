import ALArt
import SwiftUI

/* -------------------------------------------------------------------------- */
/* The fixed chrome's colours.                                                 */
/*                                                                             */
/* Every value here is a literal copied out of the TSX / `index.css`, with the  */
/* call site named in the comment. Two rules from CLAUDE.md and shell.md §5.1   */
/* bound what belongs here:                                                    */
/*                                                                             */
/*  - Colours that come from DATA are NOT tokens. `ExerciseMeta` tints,         */
/*    `MascotConfig.colors[slot]`, `CustomizationOption.value`, and a `Tile`'s  */
/*    `bg`/`ink` when an engine hands them in, are all parsed at runtime with   */
/*    `Color(svgHex:)` — exactly as the PWA applies them via `style` rather     */
/*    than a class. The pick-tile PALETTES are here because the exercises       */
/*    author them as module constants, not because a tile's colour is fixed.    */
/*  - Artwork colours belong to ALArt. Nothing in the mascot rig or the         */
/*    exercise icons is repeated here.                                         */
/*                                                                             */
/* Hexes are stored as strings so a test can assert the literal, and turned     */
/* into `Color` through ALArt's `Color(svgHex:)` — the one parser in the app.   */
/* -------------------------------------------------------------------------- */

/// A colour that keeps the hex string it was authored as.
public struct HexColor: Hashable, Sendable {
    public let hex: String

    public init(_ hex: String) { self.hex = hex }

    public var color: Color { Color(svgHex: hex) }
}

/// A CSS gradient stop: an authored hex plus its position, 0…1.
public struct HexStop: Hashable, Sendable {
    public let hex: String
    public let location: Double

    public init(_ hex: String, _ location: Double) {
        self.hex = hex
        self.location = location
    }
}

/// A CSS `linear-gradient(Ndeg, …)`, kept in CSS terms so a test can assert the
/// authored angle and stops rather than a pair of unit points.
public struct HexGradient: Hashable, Sendable {
    /// The CSS angle: 0° points to the top, 90° to the right, 180° to the bottom.
    public let degrees: Double
    public let stops: [HexStop]

    public init(degrees: Double, _ stops: [HexStop]) {
        self.degrees = degrees
        self.stops = stops
    }

    public var gradient: LinearGradient {
        LinearGradient(
            stops: stops.map { Gradient.Stop(color: Color(svgHex: $0.hex), location: $0.location) },
            startPoint: Self.startPoint(degrees),
            endPoint: Self.endPoint(degrees)
        )
    }

    /// CSS angles → SwiftUI unit points, computed for a SQUARE box.
    ///
    /// For the axis-aligned angles the app uses (90°, 180°) this is exact. For
    /// the two diagonals (135°, 160°) CSS lengthens the gradient line so the
    /// corners land on the end stops, which depends on the box's aspect ratio;
    /// on a non-square box the ramp is therefore slightly steeper here than in
    /// the browser. Both uses are wide, low-contrast card washes where the
    /// difference is invisible; recorded rather than fudged.
    static func startPoint(_ degrees: Double) -> UnitPoint { point(degrees + 180) }
    static func endPoint(_ degrees: Double) -> UnitPoint { point(degrees) }

    private static func point(_ degrees: Double) -> UnitPoint {
        let radians = degrees * .pi / 180
        // CSS 0° = up. SwiftUI's y grows downward, so the y term is negated.
        let x = 0.5 + 0.5 * sin(radians)
        let y = 0.5 - 0.5 * cos(radians)
        return UnitPoint(x: x, y: y)
    }
}

public enum Palette {

    /* ---- The page ------------------------------------------------------- */

    /// `index.css` — `body { background: #efe6da }`. The mat the card sits on.
    public static let page = HexColor("#efe6da")

    /// `STAGE` — the screen wash. TWO variants exist and both are load-bearing:
    /// the play surfaces stop the cream at 38 %, the adult/roster/shop surfaces
    /// at 40 %. Copied as authored; do not unify them.
    ///
    /// 38 %: `App.tsx` (hub), `Dashboard.tsx`, `GameFrame.tsx`.
    public static let stage = HexGradient(degrees: 180, [
        HexStop("#FFE7C9", 0.0),
        HexStop("#FFEFD6", 0.38),
        HexStop("#DCEFFB", 1.0),
    ])

    /// 40 %: `Onboarding.tsx`, `Paywall.tsx`, `WhoIsPlaying.tsx`, `shop/Shop.tsx`,
    /// `shop/Picker.tsx`.
    public static let stageAdult = HexGradient(degrees: 180, [
        HexStop("#FFE7C9", 0.0),
        HexStop("#FFEFD6", 0.40),
        HexStop("#DCEFFB", 1.0),
    ])

    /* ---- Ink ------------------------------------------------------------- */

    /// `INK` — the headline brown. Titles, level numbers, chip labels, tile
    /// glyphs on white. By far the most-used colour in the app.
    public static let ink = HexColor("#5A3A1E")

    /// `text-[#7A5A3A]` — the second voice: the hub subtitle, "Mon copain",
    /// "Qui joue ?", "étoiles à dépenser", the shop's "Habille ton copain !".
    public static let inkSoft = HexColor("#7A5A3A")

    /// `text-[#9A7A5A]` — the third voice: the hub's "· hint" clauses, shop
    /// badges, the Picker's "Tout neuf", the try-on dialog's ✕.
    public static let inkFaint = HexColor("#9A7A5A")

    /// `#6B4A2C` — running prose on the adult screens (Onboarding, Paywall).
    public static let inkProse = HexColor("#6B4A2C")

    /// `#8A6A4A` — the trial pill, the underlined adult links, the Paywall note.
    public static let inkQuiet = HexColor("#8A6A4A")

    /// `#7A5B3C` — the parental gate's one line of explanation.
    public static let inkGate = HexColor("#7A5B3C")

    /// `#8A7B69` — the shop's unaffordable price chip.
    public static let inkUnaffordable = HexColor("#8A7B69")

    /* ---- Gold — the reward vocabulary ------------------------------------ */

    /// `#4A3B00` — the ink INSIDE a gold pill (balance, EarnBadge, wallet, buy).
    public static let goldInk = HexColor("#4A3B00")

    /// `linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)` — the big balance pill
    /// (`Dashboard`) and the `+N ⭐` earn pill (`EarnBadge`).
    public static let goldPill = HexGradient(degrees: 180, [
        HexStop("#FFDE6B", 0.0),
        HexStop("#FFC107", 1.0),
    ])

    /// `0 8px 0 #E0A800` — the hard lip under the gold pill.
    public static let goldLip = HexColor("#E0A800")

    /// `bg-[#FFC107]` — the hub's jackpot (`+10 ⭐`) badge.
    public static let jackpot = HexColor("#FFC107")

    /// `text-[#B07A00]` / `ring-[#FFE08A]` — the hub's small repeat-coin badge.
    public static let coinInk = HexColor("#B07A00")
    public static let coinRing = HexColor("#FFE08A")

    /// `#FFD54F` — the shop wallet chip, the affordable price chip, the buy
    /// button. Also pick-tile #2; same hex, different job.
    public static let wallet = HexColor("#FFD54F")

    /// `linear-gradient(90deg,#FFC107,#FFD54F)` — the savings meter's fill.
    public static let savingsFill = HexGradient(degrees: 90, [
        HexStop("#FFC107", 0.0),
        HexStop("#FFD54F", 1.0),
    ])

    /// `#FFF6E0` / `#FFF9EB` — an equipped shop tile, and one being tried on.
    public static let shopEquipped = HexColor("#FFF6E0")
    public static let shopTrying = HexColor("#FFF9EB")

    /// `0 0 0 4px #FFB300` — the try-on ring.
    public static let tryRing = HexColor("#FFB300")

    /* ---- Green — the "go" vocabulary ------------------------------------- */

    /// `#66BB6A` — every primary button (Suivant, Commencer, Boutique, Continuer,
    /// C'est parti, Voir ma mascotte, Débloquer, Grandir), the equipped ring, the
    /// growth pips, the Tile highlight ring, the Picker's current-friend border.
    public static let green = HexColor("#66BB6A")

    /// `0 8px 0 #43A047` — the hard lip under a primary button.
    public static let greenLip = HexColor("#43A047")

    /// `linear-gradient(90deg,#AED581,#66BB6A)` — the Dashboard growth bar fill.
    public static let growthFill = HexGradient(degrees: 90, [
        HexStop("#AED581", 0.0),
        HexStop("#66BB6A", 1.0),
    ])

    /// `#E9DCC7` — the Dashboard growth bar's track.
    public static let growthTrack = HexColor("#E9DCC7")

    /// `#3E7B3E` — the shop's "Équipé ✓" caption.
    public static let equippedInk = HexColor("#3E7B3E")

    /// `#E6F4E6` / `#2E7D32` — the Picker's "Actuel ✓" badge.
    public static let currentBadge = HexColor("#E6F4E6")
    public static let currentBadgeInk = HexColor("#2E7D32")

    /// `linear-gradient(135deg,#E9F9E0,#D6F0FB)` — the shop's growth card.
    public static let growthCard = HexGradient(degrees: 135, [
        HexStop("#E9F9E0", 0.0),
        HexStop("#D6F0FB", 1.0),
    ])

    /// `linear-gradient(160deg,#EAF7E0,#F4FBEC)` — the shop's « Ton armoire » zone.
    public static let wardrobeZone = HexGradient(degrees: 160, [
        HexStop("#EAF7E0", 0.0),
        HexStop("#F4FBEC", 1.0),
    ])

    /// `linear-gradient(160deg,#E2F0FC,#EBF5FE)` — the shop's « Le magasin » zone.
    public static let storeZone = HexGradient(degrees: 160, [
        HexStop("#E2F0FC", 0.0),
        HexStop("#EBF5FE", 1.0),
    ])

    /* ---- Slots, borders, disabled ---------------------------------------- */

    /// `3px dashed #E4A15E` — an empty assembly slot, the "Nouveau profil" card's
    /// border, the syllable-grid vowel gap, and the rename pencil's ring.
    public static let slotDashed = HexColor("#E4A15E")

    /// `3px dashed #C9A87A` — a PRE-REVEALED (locked) assembly slot, quieter than
    /// the one the child still has to fill.
    public static let slotDashedLocked = HexColor("#C9A87A")

    /// `#FFF3E0` — a pre-revealed letter already printed into the word row
    /// (`SpellSyllableExercise`).
    public static let slotRevealed = HexColor("#FFF3E0")

    /// `#B8A98E` — disabled: the maxed-out Grandir button, the unaffordable buy.
    public static let disabled = HexColor("#B8A98E")

    /// `GHOST` — `shop/ItemPreview.tsx`: the silhouette an item is previewed on.
    public static let ghost = HexColor("#DADCE4")

    /// `#F1F0F5` — the item-preview swatch's backing plate.
    public static let previewPlate = HexColor("#F1F0F5")

    /* ---- The adult surfaces ---------------------------------------------- */

    /// `#FFFDF8` — the parental gate's card. Deliberately not kid-styled.
    public static let gateCard = HexColor("#FFFDF8")

    /// `2px solid #E6D8C6` → `#E5736A` — the gate answer field, at rest and wrong.
    public static let gateField = HexColor("#E6D8C6")
    public static let gateFieldWrong = HexColor("#E5736A")

    /// `#C4544A` — « Ce n'est pas le bon résultat. »
    public static let gateError = HexColor("#C4544A")

    /// `#F0E6D8` — the secondary adult button (Annuler, Restaurer un achat).
    public static let adultSecondary = HexColor("#F0E6D8")

    /// `#EF5350` — the roster's delete (✕) button.
    public static let destructive = HexColor("#EF5350")

    /* ---- Pick tiles ------------------------------------------------------- */

    /// A pick tile's face and its glyph ink, authored as a pair.
    public struct TilePaint: Hashable, Sendable {
        public let bg: HexColor
        public let ink: HexColor

        public init(bg: String, ink: String) {
            self.bg = HexColor(bg)
            self.ink = HexColor(ink)
        }
    }

    /// `TILE_COLORS` — the choice-tile ramp, indexed `i % count`. The
    /// single-pick exercises take a PREFIX of it: 3 for first-letter and
    /// find-sound, 4 for letter-match, all 5 for read-image.
    public static let tileColors: [TilePaint] = [
        TilePaint(bg: "#FF8A65", ink: "#4A2317"),
        TilePaint(bg: "#FFD54F", ink: "#4A3B00"),
        TilePaint(bg: "#4FC3F7", ink: "#062E3D"),
        TilePaint(bg: "#AED581", ink: "#213606"),
        TilePaint(bg: "#BA9EE8", ink: "#2C1846"),
    ]

    /// `TRAY_COLORS` — the same five paints in the assembly-tray order (blue
    /// first). Shared verbatim by Assemble, SpellSound, SpellSyllable and
    /// SoundTwins. The rotation is different from `tileColors`, so a tray tile
    /// and a pick tile at the same index are different colours; that is the
    /// authored behaviour.
    public static let trayColors: [TilePaint] = [
        TilePaint(bg: "#4FC3F7", ink: "#062E3D"),
        TilePaint(bg: "#AED581", ink: "#213606"),
        TilePaint(bg: "#FFD54F", ink: "#4A3B00"),
        TilePaint(bg: "#BA9EE8", ink: "#2C1846"),
        TilePaint(bg: "#FF8A65", ink: "#4A2317"),
    ]

    /// `TILE_COLORS` in `SyllableGridExercise` — the one exception: the first
    /// three are the standard prefix, but the fourth green is a lighter
    /// `#A5D6A7` on a darker `#123B18`, not the `#AED581`/`#213606` pair.
    public static let gridTileColors: [TilePaint] = [
        TilePaint(bg: "#FF8A65", ink: "#4A2317"),
        TilePaint(bg: "#FFD54F", ink: "#4A3B00"),
        TilePaint(bg: "#4FC3F7", ink: "#062E3D"),
        TilePaint(bg: "#A5D6A7", ink: "#123B18"),
    ]

    /* ---- Translucent whites ---------------------------------------------- */

    /// `bg-white/55 … /95` — the frosted chips, cards and buttons. The PWA
    /// writes these as Tailwind opacities on pure white; the numbers are the
    /// exact set in use.
    public enum White {
        public static let o55: Double = 0.55  // "Nouveau profil" card
        public static let o70: Double = 0.70  // trial pill, Écouter, growth meter card
        public static let o80: Double = 0.80  // hub chips, level buttons, Menu
        public static let o85: Double = 0.85  // shop back button
        public static let o90: Double = 0.90  // shop tiles, Picker rows
        public static let o92: Double = 0.92  // ChildCard
        public static let o95: Double = 0.95  // the name field
    }

    /// `rgba(30,20,10,0.55)` — the parental gate's scrim.
    public static let gateScrim = Color(red: 30 / 255, green: 20 / 255, blue: 10 / 255).opacity(0.55)

    /// `rgba(74,48,24,0.45)` — the shop try-on dialog's scrim.
    public static let tryOnScrim = Color(red: 74 / 255, green: 48 / 255, blue: 24 / 255).opacity(0.45)

    /// `rgba(255,244,224,0.82)` — the shop's blurred sticky header.
    public static let shopHeader = Color(red: 255 / 255, green: 244 / 255, blue: 224 / 255).opacity(0.82)

    /* ---- The greyed star -------------------------------------------------- */

    /// `LOST` — `{ filter: grayscale(1); opacity: 0.45 }`. A round's star the
    /// instant a wrong tap lands (GameFrame strip, and the Finished recap).
    /// Kept visible: the round still counts as played (invariants 3 and 8).
    public enum Lost {
        public static let saturation: Double = 0
        public static let opacity: Double = 0.45
    }

    /// The live round's star while it is still winnable: `opacity: 0.8`, pulsing.
    public static let liveStarOpacity: Double = 0.8

    /// A round not yet reached: a `•` at `opacity: 0.28`.
    public static let futureDotOpacity: Double = 0.28
}
