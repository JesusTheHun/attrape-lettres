package fr.dappit.attrapelettres.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import fr.dappit.attrapelettres.art.svg.SvgColor
import fr.dappit.attrapelettres.art.svg.toComposeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The fixed chrome's colours (iOS Sources/ALUI/Design/Palette.swift).
//
// Every value here is a literal copied out of the TSX / `index.css`, with the
// call site named in the comment. Two rules from the root CLAUDE.md bound what
// belongs in this file:
//
//  - Colours that come from DATA are NOT tokens. `ExerciseMeta` tints,
//    `MascotConfig` colour slots, a `CustomizationOption`'s value, and a Tile's
//    bg/ink when an engine hands them in, are all parsed at runtime with
//    `SvgColor.hex` -- exactly as the PWA applies them via `style` rather than a
//    Tailwind class. The pick-tile PALETTES are here because the exercises
//    author them as module constants, not because a tile's colour is fixed.
//  - Artwork colours belong to :art. Nothing in the mascot rig or the exercise
//    icons is repeated here; the per-exercise tints live in
//    `art/icons/ExerciseIconCatalog.kt` and duplicating them would let the two
//    drift (invariant 7 is enforced there, by an exhaustive `when`).
//
// Hexes are stored as strings so a test can assert the literal, and turned into
// a Compose `Color` through :art's `SvgColor.hex` -- the ONE hex parser in the
// app. `android.graphics.Color.parseColor` is never used: it is unavailable to a
// host test, and a second parser is a second set of edge cases.

/** A colour that keeps the hex string it was authored as. */
data class HexColor(val hex: String) {

    /** The parsed colour, straight sRGB. */
    val svg: SvgColor get() = SvgColor.hex(hex)

    val color: Color get() = svg.toComposeColor()
}

/** A CSS gradient stop: an authored hex plus its position, 0..1. */
data class HexStop(val hex: String, val location: Float)

/**
 * A CSS `linear-gradient(Ndeg, ...)`, kept in CSS terms so a test can assert the
 * authored angle and stops rather than a pair of pixel offsets.
 */
data class HexGradient(val degrees: Float, val stops: List<HexStop>) {

    constructor(degrees: Float, vararg stops: HexStop) : this(degrees, stops.toList())

    /**
     * The brush for a box of [size]. Compose's `Brush.linearGradient` takes
     * ABSOLUTE offsets, not SwiftUI's unit points, so the fractions below are
     * multiplied by the box the caller is painting -- typically
     * `Modifier.drawBehind { drawRect(spec.brush(size)) }`.
     *
     * The fractions are computed for a SQUARE box. For the axis-aligned angles
     * the app uses (90 deg, 180 deg) that is exact at any aspect ratio. For the
     * two diagonals (135 deg, 160 deg) CSS lengthens the gradient line so the
     * corners land on the end stops, which depends on the box's aspect ratio; on
     * a non-square box the ramp is therefore slightly steeper here than in the
     * browser. Both uses are wide, low-contrast card washes where the difference
     * is invisible; recorded rather than fudged, exactly as iOS recorded it.
     */
    fun brush(size: Size): Brush {
        val start = startFraction(degrees)
        val end = endFraction(degrees)
        return Brush.linearGradient(
            colorStops = stops.map { it.location to HexColor(it.hex).color }.toTypedArray(),
            start = Offset(start.x * size.width, start.y * size.height),
            end = Offset(end.x * size.width, end.y * size.height),
        )
    }

    companion object {
        // CSS angles -> unit-square fractions. CSS 0 deg points UP, 90 deg to the
        // right, 180 deg down. Compose's y grows downward (as SwiftUI's does), so
        // the y term is negated. Getting that sign wrong flips every screen's
        // wash upside down and is invisible in a hex assertion, which is why the
        // test pins the two axis-aligned angles.
        fun startFraction(degrees: Float): Offset = fraction(degrees + 180f)

        fun endFraction(degrees: Float): Offset = fraction(degrees)

        private fun fraction(degrees: Float): Offset {
            val radians = degrees.toDouble() * PI / 180.0
            return Offset(
                (0.5 + 0.5 * sin(radians)).toFloat(),
                (0.5 - 0.5 * cos(radians)).toFloat(),
            )
        }
    }
}

/** A pick tile's face and its glyph ink, authored as a pair. */
data class TilePaint(val bg: HexColor, val ink: HexColor) {
    constructor(bg: String, ink: String) : this(HexColor(bg), HexColor(ink))
}

object Palette {

    // The page ---------------------------------------------------------------

    /** `index.css` -- `body { background: #efe6da }`. The mat the card sits on. */
    val page = HexColor("#efe6da")

    // `STAGE` -- the screen wash. TWO variants exist and both are load-bearing:
    // the play surfaces stop the cream at 38 %, the adult/roster/shop surfaces at
    // 40 %. Copied as authored; do not unify them.
    //
    // 38 %: `App.tsx` (hub), `Dashboard.tsx`, `GameFrame.tsx`.
    val stage = HexGradient(
        180f,
        HexStop("#FFE7C9", 0.0f),
        HexStop("#FFEFD6", 0.38f),
        HexStop("#DCEFFB", 1.0f),
    )

    /** 40 %: `Onboarding.tsx`, `Paywall.tsx`, `WhoIsPlaying.tsx`, `shop/Shop.tsx`, `shop/Picker.tsx`. */
    val stageAdult = HexGradient(
        180f,
        HexStop("#FFE7C9", 0.0f),
        HexStop("#FFEFD6", 0.40f),
        HexStop("#DCEFFB", 1.0f),
    )

    // Ink ---------------------------------------------------------------------

    /**
     * `INK` -- the headline brown. Titles, level numbers, chip labels, tile
     * glyphs on white. By far the most-used colour in the app.
     */
    val ink = HexColor("#5A3A1E")

    /**
     * `text-[#7A5A3A]` -- the second voice: the hub subtitle, « Mon copain »,
     * « Qui joue ? », « étoiles à dépenser », the shop's « Habille ton copain ! ».
     */
    val inkSoft = HexColor("#7A5A3A")

    /**
     * `text-[#9A7A5A]` -- the third voice: the hub's « · hint » clauses, shop
     * badges, the Picker's « Tout neuf », the try-on dialog's cross.
     */
    val inkFaint = HexColor("#9A7A5A")

    /** `#6B4A2C` -- running prose on the adult screens (Onboarding, Paywall). */
    val inkProse = HexColor("#6B4A2C")

    /** `#8A6A4A` -- the trial pill, the underlined adult links, the Paywall note. */
    val inkQuiet = HexColor("#8A6A4A")

    /** `#7A5B3C` -- the parental gate's one line of explanation. */
    val inkGate = HexColor("#7A5B3C")

    /** `#8A7B69` -- the shop's unaffordable price chip. */
    val inkUnaffordable = HexColor("#8A7B69")

    // Gold -- the reward vocabulary --------------------------------------------

    /** `#4A3B00` -- the ink INSIDE a gold pill (balance, EarnBadge, wallet, buy). */
    val goldInk = HexColor("#4A3B00")

    /**
     * `linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)` -- the big balance pill
     * (`Dashboard`) and the `+N etoile` earn pill (`EarnBadge`).
     */
    val goldPill = HexGradient(
        180f,
        HexStop("#FFDE6B", 0.0f),
        HexStop("#FFC107", 1.0f),
    )

    /** `0 8px 0 #E0A800` -- the hard lip under the gold pill. */
    val goldLip = HexColor("#E0A800")

    /** `bg-[#FFC107]` -- the hub's jackpot (+10 stars) badge. */
    val jackpot = HexColor("#FFC107")

    /** `text-[#B07A00]` / `ring-[#FFE08A]` -- the hub's small repeat-coin badge. */
    val coinInk = HexColor("#B07A00")
    val coinRing = HexColor("#FFE08A")

    /**
     * `#FFD54F` -- the shop wallet chip, the affordable price chip, the buy
     * button. Also pick tile #2; same hex, different job.
     */
    val wallet = HexColor("#FFD54F")

    /** `linear-gradient(90deg,#FFC107,#FFD54F)` -- the savings meter's fill. */
    val savingsFill = HexGradient(
        90f,
        HexStop("#FFC107", 0.0f),
        HexStop("#FFD54F", 1.0f),
    )

    /** `#FFF6E0` / `#FFF9EB` -- an equipped shop tile, and one being tried on. */
    val shopEquipped = HexColor("#FFF6E0")
    val shopTrying = HexColor("#FFF9EB")

    /** `0 0 0 4px #FFB300` -- the try-on ring. */
    val tryRing = HexColor("#FFB300")

    // Green -- the "go" vocabulary ---------------------------------------------

    /**
     * `#66BB6A` -- every primary button (Suivant, Commencer, Boutique, Continuer,
     * C'est parti, Voir ma mascotte, Débloquer, Grandir), the equipped ring, the
     * growth pips, the Tile highlight ring, the Picker's current-friend border.
     */
    val green = HexColor("#66BB6A")

    /** `0 8px 0 #43A047` -- the hard lip under a primary button. */
    val greenLip = HexColor("#43A047")

    /** `linear-gradient(90deg,#AED581,#66BB6A)` -- the Dashboard growth bar fill. */
    val growthFill = HexGradient(
        90f,
        HexStop("#AED581", 0.0f),
        HexStop("#66BB6A", 1.0f),
    )

    /** `#E9DCC7` -- the Dashboard growth bar's track. */
    val growthTrack = HexColor("#E9DCC7")

    /** `#3E7B3E` -- the shop's « Équipé ✓ » caption. */
    val equippedInk = HexColor("#3E7B3E")

    /** `#E6F4E6` / `#2E7D32` -- the Picker's « Actuel ✓ » badge. */
    val currentBadge = HexColor("#E6F4E6")
    val currentBadgeInk = HexColor("#2E7D32")

    /** `linear-gradient(135deg,#E9F9E0,#D6F0FB)` -- the shop's growth card. */
    val growthCard = HexGradient(
        135f,
        HexStop("#E9F9E0", 0.0f),
        HexStop("#D6F0FB", 1.0f),
    )

    /** `linear-gradient(160deg,#EAF7E0,#F4FBEC)` -- the shop's « Ton armoire » zone. */
    val wardrobeZone = HexGradient(
        160f,
        HexStop("#EAF7E0", 0.0f),
        HexStop("#F4FBEC", 1.0f),
    )

    /** `linear-gradient(160deg,#E2F0FC,#EBF5FE)` -- the shop's « Le magasin » zone. */
    val storeZone = HexGradient(
        160f,
        HexStop("#E2F0FC", 0.0f),
        HexStop("#EBF5FE", 1.0f),
    )

    // Slots, borders, disabled -------------------------------------------------

    /**
     * `3px dashed #E4A15E` -- an empty assembly slot, the « Nouveau profil » card's
     * border, the syllable-grid vowel gap, and the rename pencil's ring.
     */
    val slotDashed = HexColor("#E4A15E")

    /**
     * `3px dashed #C9A87A` -- a PRE-REVEALED (locked) assembly slot, quieter than
     * the one the child still has to fill.
     */
    val slotDashedLocked = HexColor("#C9A87A")

    /**
     * `#FFF3E0` -- a pre-revealed letter already printed into the word row
     * (`SpellSyllableExercise`).
     */
    val slotRevealed = HexColor("#FFF3E0")

    /** `#B8A98E` -- disabled: the maxed-out Grandir button, the unaffordable buy. */
    val disabled = HexColor("#B8A98E")

    /** `GHOST` -- `shop/ItemPreview.tsx`: the silhouette an item is previewed on. */
    val ghost = HexColor("#DADCE4")

    /** `#F1F0F5` -- the item-preview swatch's backing plate. */
    val previewPlate = HexColor("#F1F0F5")

    // The adult surfaces -------------------------------------------------------

    /** `#FFFDF8` -- the parental gate's card. Deliberately not kid-styled. */
    val gateCard = HexColor("#FFFDF8")

    /** `2px solid #E6D8C6` -> `#E5736A` -- the gate answer field, at rest and wrong. */
    val gateField = HexColor("#E6D8C6")
    val gateFieldWrong = HexColor("#E5736A")

    /** `#C4544A` -- « Ce n'est pas le bon résultat. » */
    val gateError = HexColor("#C4544A")

    /** `#F0E6D8` -- the secondary adult button (Annuler, Restaurer un achat). */
    val adultSecondary = HexColor("#F0E6D8")

    /** `#EF5350` -- the roster's delete button. */
    val destructive = HexColor("#EF5350")

    // Pick tiles ---------------------------------------------------------------

    /**
     * `TILE_COLORS` -- the choice-tile ramp, indexed `i % count`. The single-pick
     * exercises take a PREFIX of it: 3 for first-letter and find-sound, 4 for
     * letter-match, all 5 for read-image.
     */
    val tileColors: List<TilePaint> = listOf(
        TilePaint(bg = "#FF8A65", ink = "#4A2317"),
        TilePaint(bg = "#FFD54F", ink = "#4A3B00"),
        TilePaint(bg = "#4FC3F7", ink = "#062E3D"),
        TilePaint(bg = "#AED581", ink = "#213606"),
        TilePaint(bg = "#BA9EE8", ink = "#2C1846"),
    )

    /**
     * `TRAY_COLORS` -- the same five paints in the assembly-tray order (blue
     * first). Shared verbatim by Assemble, SpellSound, SpellSyllable and
     * SoundTwins. The rotation differs from [tileColors], so a tray tile and a
     * pick tile at the same index are different colours; that is the authored
     * behaviour, not an oversight, and the two lists must not be collapsed.
     */
    val trayColors: List<TilePaint> = listOf(
        TilePaint(bg = "#4FC3F7", ink = "#062E3D"),
        TilePaint(bg = "#AED581", ink = "#213606"),
        TilePaint(bg = "#FFD54F", ink = "#4A3B00"),
        TilePaint(bg = "#BA9EE8", ink = "#2C1846"),
        TilePaint(bg = "#FF8A65", ink = "#4A2317"),
    )

    /**
     * `TILE_COLORS` in `SyllableGridExercise` -- the one exception: the first
     * three are the standard prefix, but the fourth green is a lighter `#A5D6A7`
     * on a darker `#123B18`, not the `#AED581`/`#213606` pair.
     */
    val gridTileColors: List<TilePaint> = listOf(
        TilePaint(bg = "#FF8A65", ink = "#4A2317"),
        TilePaint(bg = "#FFD54F", ink = "#4A3B00"),
        TilePaint(bg = "#4FC3F7", ink = "#062E3D"),
        TilePaint(bg = "#A5D6A7", ink = "#123B18"),
    )

    // Translucent whites -------------------------------------------------------

    /**
     * `bg-white/55 ... /95` -- the frosted chips, cards and buttons. The PWA writes
     * these as Tailwind opacities (or `rgba(255,255,255,a)`) on pure white; the
     * numbers below are the exact set in shipped code. `0.72` appears only in
     * `*.stories.tsx`, which never ships, so it is deliberately absent.
     */
    object White {
        const val o55 = 0.55f // « Nouveau profil » card
        const val o70 = 0.70f // trial pill, Écouter, growth meter card
        const val o80 = 0.80f // hub chips, level buttons, Menu
        const val o85 = 0.85f // shop back button
        const val o90 = 0.90f // shop tiles, Picker rows
        const val o92 = 0.92f // ChildCard
        const val o95 = 0.95f // the name field
    }

    /** `rgba(30,20,10,0.55)` -- the parental gate's scrim. */
    val gateScrim = Color(red = 30 / 255f, green = 20 / 255f, blue = 10 / 255f, alpha = 0.55f)

    /** `rgba(74,48,24,0.45)` -- the shop try-on dialog's scrim. */
    val tryOnScrim = Color(red = 74 / 255f, green = 48 / 255f, blue = 24 / 255f, alpha = 0.45f)

    /** `rgba(255,244,224,0.82)` -- the shop's blurred sticky header. */
    val shopHeader = Color(red = 255 / 255f, green = 244 / 255f, blue = 224 / 255f, alpha = 0.82f)

    // The greyed star ----------------------------------------------------------

    /**
     * `LOST` -- `{ filter: grayscale(1); opacity: 0.45 }` in `GameFrame.tsx` and
     * `Finished.tsx`. A round's star the instant a wrong tap lands. Kept VISIBLE:
     * the round still counts as played (invariants 3 and 8). Dropping the opacity
     * to 0 would erase the round from the strip and turn a miss into a loss.
     */
    object Lost {
        const val saturation = 0f
        const val opacity = 0.45f
    }

    /** The live round's star while it is still winnable: `opacity: 0.8`, pulsing. */
    const val liveStarOpacity = 0.8f

    /** A round not yet reached: a dot at `opacity: 0.28`. */
    const val futureDotOpacity = 0.28f
}
