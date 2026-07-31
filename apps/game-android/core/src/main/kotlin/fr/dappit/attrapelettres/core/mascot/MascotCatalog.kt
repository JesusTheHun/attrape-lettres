package fr.dappit.attrapelettres.core.mascot

import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.CustomizationOption
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.domain.Species.CAT
import fr.dappit.attrapelettres.core.domain.Species.DRAGON
import fr.dappit.attrapelettres.core.domain.Species.FOX
import fr.dappit.attrapelettres.core.domain.Species.RABBIT
import fr.dappit.attrapelettres.core.domain.Species.UNICORN

// Port of `src/mascot/catalog.ts`.
//
// Invariant 4 (touches): CATALOG and DEFAULT_LOOKS are LITERAL DATA. Nothing
// here is generated, and no gate is derived — a rule that computed `minStage`
// from the species timeline would be shorter and would silently change what a
// child can buy the next time a rig moves a part.
//
// Every `id` string is a persistence contract (see MascotIds.kt).

/**
 * Shop inventory — the single source of what's buyable. Owned by AGENT A.
 *
 * Every option's slot/value matches exactly what Mascot.tsx reads:
 *  - colours write config.colors[slot] = value (hex),
 *  - styles  write config.styles[slot] = value (variant id),
 *  - accessories are matched verbatim by option id (see ids.ts / ACCESSORY).
 *
 * Cost bands (first exercise clear = 10 pts, repeats decay): colours 15–30,
 * styles 30–60, accessories 40–150, one premium per species = 200.
 */

private fun color(
    species: Species,
    slot: String,
    token: String,
    value: String,
    name: String,
    emoji: String,
    cost: Int,
    minStage: Int? = null,
): CustomizationOption = CustomizationOption(
    id = "${species.wire}.color.$slot.$token",
    species = species,
    category = CustomizationCategory.COLOR,
    slot = slot,
    value = value,
    name = name,
    emoji = emoji,
    cost = cost,
    minStage = minStage,
)

private fun style(
    species: Species,
    slot: String,
    value: String,
    name: String,
    emoji: String,
    cost: Int,
    minStage: Int? = null,
): CustomizationOption = CustomizationOption(
    id = "${species.wire}.style.$slot.$value",
    species = species,
    category = CustomizationCategory.STYLE,
    slot = slot,
    value = value,
    name = name,
    emoji = emoji,
    cost = cost,
    minStage = minStage,
)

private fun accessory(
    id: String,
    species: Species,
    name: String,
    emoji: String,
    cost: Int,
    minStage: Int? = null,
): CustomizationOption = CustomizationOption(
    id = id,
    species = species,
    category = CustomizationCategory.ACCESSORY,
    slot = "accessory",
    value = id,
    name = name,
    emoji = emoji,
    cost = cost,
    minStage = minStage,
)

// NB: the TS aliases the slot tables to two-letter locals (U, CA, FO, RA, DR,
// US, CS, FS, RS, DS) purely so the rows fit on one line. Kept, same names.
private val U = ColorSlot.Unicorn
private val CA = ColorSlot.Cat
private val FO = ColorSlot.Fox
private val RA = ColorSlot.Rabbit
private val DR = ColorSlot.Dragon
private val US = StyleSlot.Unicorn
private val CS = StyleSlot.Cat
private val FS = StyleSlot.Fox
private val RS = StyleSlot.Rabbit
private val DS = StyleSlot.Dragon

val CATALOG: List<CustomizationOption> = listOf(
    /* ---- Unicorn ------------------------------------------------------- */
    color(UNICORN, U.body, "rose", "#FFD6E8", "Corps rose", "🌸", 18),
    color(UNICORN, U.body, "ciel", "#DCEFFB", "Corps bleu ciel", "💧", 18),
    color(UNICORN, U.body, "menthe", "#DDF3D8", "Corps menthe", "🌿", 18),
    // Horn only sprouts at stade 2 (nub) → 3 (real horn); gate horn recolours and
    // the twist so nobody buys a look a hornless baby unicorn can't show.
    color(UNICORN, U.horn, "rose", "#FF8FB1", "Corne rose", "🦄", 20, 2),
    color(UNICORN, U.horn, "turquoise", "#7FD1D8", "Corne turquoise", "💠", 20, 2),
    color(UNICORN, U.mane, "corail", "#FF8A65", "Crinière corail", "🔥", 22),
    color(UNICORN, U.mane, "turquoise", "#7FD1D8", "Crinière turquoise", "🌊", 22),
    color(UNICORN, U.tail, "menthe", "#AED581", "Queue menthe", "🍃", 20),
    style(UNICORN, US.tail, "curly", "Queue bouclée", "🌀", 40),
    style(UNICORN, US.horn, "spiral", "Corne torsadée", "🐚", 45, 3),
    accessory(Accessory.Unicorn.ribbon, UNICORN, "Nœud", "🎀", 45),
    accessory(Accessory.Unicorn.flowerCrown, UNICORN, "Couronne de fleurs", "🌸", 95, 2),
    accessory(Accessory.Unicorn.starClip, UNICORN, "Arc-en-ciel magique", "🌈", 200, 4),
    accessory(Accessory.Unicorn.swimsuit, UNICORN, "Maillot de bain", "🩱", 60),
    accessory(Accessory.Unicorn.swimRing, UNICORN, "Bouée", "🛟", 75),

    /* ---- Cat ----------------------------------------------------------- */
    color(CAT, CA.body, "gris", "#C9CCD6", "Pelage gris", "🩶", 18),
    color(CAT, CA.body, "blanc", "#FFF3E6", "Pelage blanc", "🤍", 18),
    color(CAT, CA.body, "noir", "#6A6A72", "Pelage noir", "🖤", 20),
    // Stade 0 is a curled sleeping loaf — belly and tail are tucked out of sight,
    // so gate their looks until the kitten sits up at stade 1.
    color(CAT, CA.belly, "rose", "#FFE1EC", "Ventre rose", "🌸", 16, 1),
    color(CAT, CA.tail, "roux", "#E08A4E", "Queue rousse", "🦊", 18, 1),
    color(CAT, CA.body, "creme", "#F3E4D0", "Pelage crème", "🍦", 18),
    color(CAT, CA.body, "lilas", "#E6DDF5", "Pelage lilas", "💜", 20),
    color(CAT, CA.tail, "noire", "#565660", "Queue noire", "🖤", 18, 1),
    style(CAT, CS.hair, "fluffy", "Poil touffu", "☁️", 40),
    style(CAT, CS.tail, "short", "Petite queue", "🐈", 32, 1),
    accessory(Accessory.Cat.bow, CAT, "Nœud", "🎀", 45),
    accessory(Accessory.Cat.bellCollar, CAT, "Collier grelot", "🔔", 55),
    accessory(Accessory.Cat.partyHat, CAT, "Chapeau de fête", "🎉", 200, 4),
    accessory(Accessory.Cat.swimsuit, CAT, "Maillot de bain", "🩱", 60),
    accessory(Accessory.Cat.swimRing, CAT, "Bouée", "🛟", 75),

    /* ---- Fox ----------------------------------------------------------- */
    color(FOX, FO.body, "roux", "#E96B4A", "Pelage roux", "🍂", 18),
    color(FOX, FO.body, "miel", "#F4A259", "Pelage miel", "🍯", 18),
    color(FOX, FO.body, "cendre", "#B7A99A", "Pelage cendré", "🌫️", 20),
    color(FOX, FO.belly, "creme", "#FFDCB4", "Ventre crème", "🤍", 16),
    color(FOX, FO.tailTip, "brun", "#5A3A1E", "Bout de queue brun", "🟤", 18),
    color(FOX, FO.tailTip, "dore", "#FFD54F", "Bout de queue doré", "⭐", 22),
    color(FOX, FO.body, "arctique", "#EDE7DE", "Pelage arctique", "❄️", 20),
    style(FOX, FS.fur, "spots", "Taches", "🐆", 48),
    style(FOX, FS.fur, "stripes", "Rayures", "🐯", 48),
    style(FOX, FS.tail, "short", "Petite queue", "🦊", 32),
    accessory(Accessory.Fox.scarf, FOX, "Écharpe", "🧣", 45),
    accessory(Accessory.Fox.beanie, FOX, "Bonnet", "🧢", 60),
    accessory(Accessory.Fox.boots, FOX, "Bottes", "🥾", 200, 4),
    accessory(Accessory.Fox.swimsuit, FOX, "Maillot de bain", "🩱", 60),
    accessory(Accessory.Fox.swimRing, FOX, "Bouée", "🛟", 75),

    /* ---- Rabbit --------------------------------------------------------- */
    color(RABBIT, RA.body, "souris", "#D6D3DE", "Pelage gris souris", "🐭", 18),
    color(RABBIT, RA.body, "caramel", "#EFC9A0", "Pelage caramel", "🍮", 18),
    color(RABBIT, RA.body, "peche", "#F8D3BC", "Pelage pêche", "🍑", 18),
    color(RABBIT, RA.body, "lilas", "#E4DCF2", "Pelage lilas", "💜", 20),
    // The inner ears only "bloom" their colour at stade 3 — before that the
    // tint is nearly invisible on the pale baby ear.
    color(RABBIT, RA.inner, "rose", "#F5A8C0", "Oreilles rose poudré", "🌸", 20, 3),
    color(RABBIT, RA.inner, "menthe", "#A8DDB8", "Oreilles menthe", "🌿", 20, 3),
    color(RABBIT, RA.belly, "creme", "#FFE8BC", "Ventre crème", "🍦", 16),
    // Ears lie flat on the back until the rabbit stands at stade 2 — the fold
    // wouldn't show on a lying baby.
    style(RABBIT, RS.ear, "pliees", "Oreilles pliées", "🐰", 45, 2),
    style(RABBIT, RS.tail, "etoile", "Queue étoile", "🌟", 50),
    style(RABBIT, RS.fur, "flocons", "Flocons d'étoiles", "❄️", 48),
    accessory(Accessory.Rabbit.bow, RABBIT, "Nœud étoilé", "🎀", 45),
    accessory(Accessory.Rabbit.nightcap, RABBIT, "Bonnet de nuit", "🌙", 60),
    accessory(Accessory.Rabbit.stardust, RABBIT, "Poussière d'étoiles", "🌠", 200, 4),
    // Worn standing only (stade 2+): the lying nappy-culotte read as a backpack.
    accessory(Accessory.Rabbit.swimsuit, RABBIT, "Maillot de bain", "🩱", 60, 2),
    accessory(Accessory.Rabbit.swimRing, RABBIT, "Bouée", "🛟", 75),

    /* ---- Dragon --------------------------------------------------------- */
    color(DRAGON, DR.body, "braise", "#D97B6C", "Écailles rouge braise", "🔥", 20),
    color(DRAGON, DR.body, "charbon", "#8A8D96", "Écailles charbon", "🪨", 20),
    color(DRAGON, DR.body, "nuit", "#7E8FB5", "Écailles bleu nuit", "🌙", 20),
    color(DRAGON, DR.body, "terre", "#B08968", "Écailles brun terre", "🤎", 18),
    // The belly is hidden inside the stade-0 egg; wings sprout at 3; horn nubs at 2.
    color(DRAGON, DR.belly, "magma", "#FFB27A", "Ventre magma", "🌋", 22, 1),
    color(DRAGON, DR.wing, "nuit", "#5F6470", "Ailes nuit", "🦇", 24, 3),
    color(DRAGON, DR.wing, "dorees", "#F2C14E", "Ailes dorées", "⭐", 24, 3),
    color(DRAGON, DR.horn, "or", "#F2C14E", "Cornes d'or", "✨", 22, 2),
    color(DRAGON, DR.horn, "noires", "#4E5560", "Cornes noires", "🖤", 22, 2),
    style(DRAGON, DS.horn, "double", "Cornes doubles", "🐉", 45, 3),
    style(DRAGON, DS.crest, "lava", "Crête de lave", "🌋", 50, 4),
    style(DRAGON, DS.tail, "club", "Queue massue", "🔨", 40, 1),
    style(DRAGON, DS.tail, "flame", "Queue de feu", "☄️", 55, 2),
    accessory(Accessory.Dragon.cape, DRAGON, "Cape de chevalier", "🦸", 55, 2),
    accessory(Accessory.Dragon.goggles, DRAGON, "Lunettes d'aviateur", "🥽", 65, 3),
    accessory(Accessory.Dragon.fang, DRAGON, "Collier de croc", "🦷", 45, 2),
    accessory(Accessory.Dragon.treasure, DRAGON, "Petit trésor", "🪙", 70),
    accessory(Accessory.Dragon.blueFlame, DRAGON, "Flamme bleue", "💙", 200, 4),
)

/**
 * The mascot's factory look per colour/style slot. Each `value` MUST match the
 * corresponding `pick(..., fallback)` default in the species rig, so selecting
 * it (which just clears the slot) reproduces exactly what a fresh mascot shows.
 * Surfaced in the shop as an already-owned, NAMED tile — no "reset/default"
 * wording — so a child can simply pick it to return to the original look.
 * `minStage` mirrors the slot's part visibility so a default for a not-yet-grown
 * part (unicorn horn, curled-kitten belly/tail) stays gated like its variants.
 */
data class DefaultLook(
    // NB: the TS narrows this to `"color" | "style"` — an accessory has no
    // factory look. Kotlin reuses `CustomizationCategory` so the shop can compare
    // `(category, slot)` against a catalog row without a second mapping;
    // `MascotCatalogTest` asserts no default look is ACCESSORY.
    val category: CustomizationCategory,
    val slot: String,
    val value: String,
    val name: String,
    val emoji: String? = null,
    val minStage: Int? = null,
)

private val COLOR = CustomizationCategory.COLOR
private val STYLE = CustomizationCategory.STYLE

val DEFAULT_LOOKS: Map<Species, List<DefaultLook>> = mapOf(
    UNICORN to listOf(
        DefaultLook(COLOR, U.body, "#F5ECFF", "Corps lilas"),
        DefaultLook(COLOR, U.horn, "#FFD54F", "Corne dorée", minStage = 2),
        DefaultLook(COLOR, U.mane, "#BA9EE8", "Crinière parme"),
        DefaultLook(COLOR, U.tail, "#F49AC2", "Queue rose"),
        DefaultLook(STYLE, US.tail, "straight", "Queue lisse", "〰️"),
        DefaultLook(STYLE, US.horn, "smooth", "Corne lisse", "🔺", 2),
    ),
    CAT to listOf(
        DefaultLook(COLOR, CA.body, "#F6A96B", "Pelage roux"),
        DefaultLook(COLOR, CA.belly, "#FFF3E4", "Ventre crème", minStage = 1),
        DefaultLook(COLOR, CA.tail, "#F6A96B", "Queue assortie", minStage = 1),
        DefaultLook(STYLE, CS.hair, "short", "Poil court", "🐱"),
        DefaultLook(STYLE, CS.tail, "long", "Grande queue", "🐈", 1),
    ),
    FOX to listOf(
        DefaultLook(COLOR, FO.body, "#FF8A65", "Pelage roux"),
        DefaultLook(COLOR, FO.belly, "#FFFFFF", "Ventre blanc"),
        DefaultLook(COLOR, FO.tailTip, "#FFFFFF", "Bout blanc"),
        DefaultLook(STYLE, FS.fur, "plain", "Pelage uni", "🟠"),
        DefaultLook(STYLE, FS.tail, "long", "Grande queue", "🦊"),
    ),
    RABBIT to listOf(
        DefaultLook(COLOR, RA.body, "#F6EFE3", "Pelage ivoire"),
        DefaultLook(COLOR, RA.inner, "#D9CCEE", "Oreilles lavande", minStage = 3),
        DefaultLook(COLOR, RA.belly, "#FFFFFF", "Ventre blanc"),
        DefaultLook(STYLE, RS.ear, "hautes", "Oreilles hautes", "🐇", 2),
        DefaultLook(STYLE, RS.tail, "pompon", "Queue pompon", "⚪"),
        DefaultLook(STYLE, RS.fur, "uni", "Pelage uni", "🤍"),
    ),
    DRAGON to listOf(
        DefaultLook(COLOR, DR.body, "#7DB874", "Écailles vertes"),
        DefaultLook(COLOR, DR.belly, "#E9DFB2", "Ventre sable", minStage = 1),
        DefaultLook(COLOR, DR.wing, "#E2694F", "Ailes braise", minStage = 3),
        DefaultLook(COLOR, DR.horn, "#EDE3CE", "Cornes ivoire", minStage = 2),
        DefaultLook(STYLE, DS.horn, "straight", "Cornes droites", "🔺", 2),
        DefaultLook(STYLE, DS.crest, "charbon", "Crête charbon", "🪨", 4),
        DefaultLook(STYLE, DS.tail, "spade", "Queue flèche", "🏹", 1),
    ),
)
