package fr.dappit.attrapelettres.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dappit.attrapelettres.art.svg.SvgRect
import fr.dappit.attrapelettres.core.domain.CustomizationCategory
import fr.dappit.attrapelettres.core.domain.MascotConfig
import fr.dappit.attrapelettres.core.domain.Mood
import fr.dappit.attrapelettres.core.domain.Species
import fr.dappit.attrapelettres.core.mascot.Accessory
import fr.dappit.attrapelettres.core.mascot.ColorSlot
import fr.dappit.attrapelettres.core.mascot.DEFAULT_LOOKS
import fr.dappit.attrapelettres.core.mascot.StyleSlot
import fr.dappit.attrapelettres.core.mascot.accessoryAnchors
import fr.dappit.attrapelettres.core.mascot.layoutFor
import fr.dappit.attrapelettres.core.platform.ReduceMotionSource
import fr.dappit.attrapelettres.ui.components.Ollie
import fr.dappit.attrapelettres.ui.design.Palette

// ---------------------------------------------------------------------------
// `src/shop/ItemPreview.tsx` — the shop tile thumbnail.
//
// A GHOST mini-mascot previews the real reward: the whole creature draws in
// neutral grey and ONLY the thing the tile sells is shown for real — the
// recoloured part in its hex, the restyled part in its new shape, or the
// accessory drawn where it lands. A fixed "showcase" stage plus the rig's
// `preview` flag (which suppresses the per-stage magic) keep the frame calm.
//
// `focusFor` decides where to ZOOM: a small part or a worn accessory is
// unreadable inside a whole-body thumbnail, so the drawing is cropped to a
// square around it. The coordinates come from the SAME layout/anchor maths the
// rig draws with (`layoutFor` / `accessoryAnchors`, both in `:core`), at the
// fixed showcase stage, so the box lands true.
//
// NOTHING HERE AUTHORS GEOMETRY. Every number is the TSX's, applied to :core's
// layout, and every shape is :art's — this file redraws not one path. Invariant
// 4's spirit and A10's split, both.
// ---------------------------------------------------------------------------

object ItemPreviewModel {

    /** `GHOST` — the neutral silhouette every part that ISN'T being sold wears. */
    val GHOST: String = Palette.ghost.hex

    /** `SHOWCASE_STAGE` — standing youngster: legs plus a real horn/tail visible. */
    const val SHOWCASE_STAGE: Int = 4

    /**
     * `Object.values(COLOR_SLOT[species])` — the slots `ghostColors` greys out,
     * in the TS object's declaration order.
     */
    fun colorSlots(species: Species): List<String> = when (species) {
        Species.UNICORN -> listOf(
            ColorSlot.Unicorn.body,
            ColorSlot.Unicorn.horn,
            ColorSlot.Unicorn.mane,
            ColorSlot.Unicorn.tail,
        )
        Species.CAT -> listOf(ColorSlot.Cat.body, ColorSlot.Cat.belly, ColorSlot.Cat.tail)
        Species.FOX -> listOf(ColorSlot.Fox.body, ColorSlot.Fox.belly, ColorSlot.Fox.tailTip)
        Species.RABBIT -> listOf(
            ColorSlot.Rabbit.body,
            ColorSlot.Rabbit.belly,
            ColorSlot.Rabbit.inner,
        )
        Species.DRAGON -> listOf(
            ColorSlot.Dragon.body,
            ColorSlot.Dragon.belly,
            ColorSlot.Dragon.wing,
            ColorSlot.Dragon.horn,
        )
    }

    /** `ghostColors(species)` — every colour slot → GHOST. */
    fun ghostColors(species: Species): Map<String, String> =
        colorSlots(species).associateWith { GHOST }

    /**
     * `STYLE_COLOR_SLOT` — a shape-style lives on ONE part; tinting that part
     * with its factory colour makes the shape read. Body-wide styles are
     * omitted (their change draws in its own contrasting colour).
     */
    fun styleColorSlot(species: Species, styleSlot: String): String? = when (species) {
        Species.UNICORN -> when (styleSlot) {
            StyleSlot.Unicorn.tail -> ColorSlot.Unicorn.tail
            StyleSlot.Unicorn.horn -> ColorSlot.Unicorn.horn
            else -> null
        }
        Species.CAT -> if (styleSlot == StyleSlot.Cat.tail) ColorSlot.Cat.tail else null
        // Folded ears read via their lavender inner on the ghost body.
        Species.RABBIT -> if (styleSlot == StyleSlot.Rabbit.ear) ColorSlot.Rabbit.inner else null
        // Double horns tinted with the factory ivory.
        Species.DRAGON -> if (styleSlot == StyleSlot.Dragon.horn) ColorSlot.Dragon.horn else null
        Species.FOX -> null
    }

    /** `defaultColor(species, colorSlot)` — the factory hex for one colour slot. */
    fun defaultColor(species: Species, colorSlot: String): String? =
        DEFAULT_LOOKS[species]
            ?.firstOrNull { it.category == CustomizationCategory.COLOR && it.slot == colorSlot }
            ?.value

    /** The thumbnail's whole config: ghost everywhere plus the one real thing. */
    fun previewConfig(
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String,
    ): MascotConfig {
        val colors = ghostColors(species).toMutableMap()
        when (category) {
            CustomizationCategory.COLOR -> colors[slot] = value
            CustomizationCategory.STYLE -> {
                val colorSlot = styleColorSlot(species, slot)
                val tint = colorSlot?.let { defaultColor(species, it) }
                if (colorSlot != null && tint != null) colors[colorSlot] = tint
            }
            CustomizationCategory.ACCESSORY -> Unit
        }
        return MascotConfig(
            species = species,
            stage = SHOWCASE_STAGE,
            colors = colors,
            // A style preview sets only its own slot; the rig fills every other
            // slot with its plain factory default, so the shape change is the
            // sole difference.
            styles = if (category == CustomizationCategory.STYLE) mapOf(slot to value) else emptyMap(),
            accessories = if (category == CustomizationCategory.ACCESSORY) listOf(value) else emptyList(),
        )
    }

    // --- focusFor -------------------------------------------------------------

    private fun box(cx: Double, cy: Double, r: Double) =
        SvgRect(x = cx - r, y = cy - r, width = 2 * r, height = 2 * r)

    /**
     * The swim set is sold by every species EXCEPT the dragon (tradition
     * deliberately broken, user decision) — the TS guards `"swimsuit" in AA`.
     */
    fun swimsuitId(species: Species): String? = when (species) {
        Species.UNICORN -> Accessory.Unicorn.swimsuit
        Species.CAT -> Accessory.Cat.swimsuit
        Species.FOX -> Accessory.Fox.swimsuit
        Species.RABBIT -> Accessory.Rabbit.swimsuit
        Species.DRAGON -> null
    }

    fun swimRingId(species: Species): String? = when (species) {
        Species.UNICORN -> Accessory.Unicorn.swimRing
        Species.CAT -> Accessory.Cat.swimRing
        Species.FOX -> Accessory.Fox.swimRing
        Species.RABBIT -> Accessory.Rabbit.swimRing
        Species.DRAGON -> null
    }

    /**
     * `focusFor(species, category, slot, value)` — the crop, or `null` for the
     * full body (whole-coat colours, body patterns, whole-image shimmers).
     */
    fun focus(
        species: Species,
        category: CustomizationCategory,
        slot: String,
        value: String,
    ): SvgRect? {
        val l = layoutFor(SHOWCASE_STAGE.toDouble())
        val a = accessoryAnchors(species, l)
        val headCX = l.headCX
        val headCY = l.headCY
        val headR = l.headR
        val bodyCX = l.bodyCX
        val bodyCY = l.bodyCY
        val bodyRX = l.bodyRX
        val bodyRY = l.bodyRY
        val feetY = l.feetY

        val horn = { box(headCX, headCY - headR * 0.5, headR * 1.25) }
        val mane = { box(headCX, headCY, headR * 1.5) }
        val uniTail = { box(bodyCX - bodyRX * 0.5, bodyCY + bodyRY * 0.5, bodyRY * 1.3) }
        val catTail = { box(bodyCX + bodyRX * 0.9, bodyCY - bodyRY * 0.05, bodyRY * 1.25) }
        val foxTail = { box(bodyCX + bodyRX * 0.85, bodyCY + bodyRY * 0.45, bodyRY * 1.3) }
        val belly = { box(bodyCX, bodyCY + bodyRY * 0.28, bodyRX * 1.15) }
        val rabbitEars = { box(headCX, headCY - headR * 1.1, headR * 1.6) }
        val rabbitTail = { box(bodyCX - bodyRX * 0.95, bodyCY + bodyRY * 0.45, bodyRY * 1.2) }
        val dragonHorns = { box(headCX, headCY - headR * 0.75, headR * 1.35) }

        return when (category) {
            CustomizationCategory.ACCESSORY -> when {
                // Whole-image shimmer / star dust — no crop.
                value == Accessory.Unicorn.starClip -> null
                value == Accessory.Rabbit.stardust -> null
                value == Accessory.Unicorn.ribbon ||
                    value == Accessory.Cat.bellCollar ||
                    value == Accessory.Fox.scarf ||
                    value == Accessory.Rabbit.bow ||
                    value == Accessory.Dragon.fang -> box(a.neck.x, a.neck.y, headR * 1.1)
                value == Accessory.Cat.bow ||
                    value == Accessory.Cat.partyHat ||
                    value == Accessory.Fox.beanie ||
                    value == Accessory.Rabbit.nightcap ->
                    box(a.headTop.x, headCY - headR * 0.55, headR * 1.4)
                value == Accessory.Unicorn.flowerCrown ->
                    box(headCX, headCY - headR * 0.2, headR * 1.4)
                value == Accessory.Fox.boots -> box(bodyCX, feetY - 4, bodyRX * 1.25)
                // Dragon: goggles rest on the forehead; the blue breath curls at
                // the mouth; the treasure heap sits on the ground to his left.
                value == Accessory.Dragon.goggles -> box(headCX, headCY - headR * 0.6, headR * 1.3)
                value == Accessory.Dragon.blueFlame ->
                    box(headCX + headR * 0.35, headCY + headR * 0.7, headR * 1.15)
                value == Accessory.Dragon.treasure -> box(18.0, feetY - 3.5, 11.0)
                value == swimsuitId(species) -> box(bodyCX, bodyCY + bodyRY * 0.25, bodyRX * 1.2)
                value == swimRingId(species) -> box(bodyCX, bodyCY + bodyRY * 0.3, bodyRX * 1.55)
                else -> null
            }

            // Every species names its whole coat "bodyColor" — full body.
            CustomizationCategory.COLOR -> when (species) {
                Species.UNICORN -> when (slot) {
                    ColorSlot.Unicorn.horn -> horn()
                    ColorSlot.Unicorn.mane -> mane()
                    ColorSlot.Unicorn.tail -> uniTail()
                    else -> null
                }
                Species.CAT -> when (slot) {
                    ColorSlot.Cat.belly -> belly()
                    ColorSlot.Cat.tail -> catTail()
                    else -> null
                }
                Species.FOX -> when (slot) {
                    ColorSlot.Fox.belly -> belly()
                    ColorSlot.Fox.tailTip -> foxTail()
                    else -> null
                }
                Species.DRAGON -> when (slot) {
                    ColorSlot.Dragon.belly -> belly()
                    ColorSlot.Dragon.wing -> box(bodyCX, bodyCY - bodyRY * 0.5, bodyRX * 1.5)
                    ColorSlot.Dragon.horn -> dragonHorns()
                    else -> null
                }
                Species.RABBIT -> when (slot) {
                    ColorSlot.Rabbit.belly -> belly()
                    ColorSlot.Rabbit.inner -> rabbitEars()
                    else -> null
                }
            }

            // Zoom part-local shapes; body-wide (fluffy, fur pattern) stay full.
            CustomizationCategory.STYLE -> when (species) {
                Species.UNICORN -> when (slot) {
                    StyleSlot.Unicorn.horn -> horn()
                    StyleSlot.Unicorn.tail -> uniTail()
                    else -> null
                }
                Species.CAT -> if (slot == StyleSlot.Cat.tail) catTail() else null
                Species.FOX -> when (slot) {
                    StyleSlot.Fox.tail -> foxTail()
                    // Spots/stripes sit on the torso — crop to the trunk.
                    StyleSlot.Fox.fur -> box(bodyCX, bodyCY, bodyRX * 1.15)
                    else -> null
                }
                Species.DRAGON -> when (slot) {
                    StyleSlot.Dragon.horn -> dragonHorns()
                    StyleSlot.Dragon.crest -> box(headCX, headCY - headR * 0.85, headR * 1.35)
                    // Both tail styles change the TIP — crop to the raised end.
                    StyleSlot.Dragon.tail -> box(bodyCX + bodyRX * 1.42, bodyCY - bodyRY * 0.2, 11.0)
                    else -> null
                }
                Species.RABBIT -> when (slot) {
                    StyleSlot.Rabbit.ear -> rabbitEars()
                    StyleSlot.Rabbit.tail -> rabbitTail()
                    // Star-flecks sit on the torso — crop like the fox pattern.
                    StyleSlot.Rabbit.fur -> box(bodyCX, bodyCY, bodyRX * 1.15)
                    else -> null
                }
            }
        }
    }
}

// --- The view -----------------------------------------------------------------

/** The metrics the TSX authors around the thumbnail. */
object ItemPreviewMetrics {
    /** `width: size + 14` — the `p-[7px]`-equivalent plate. */
    val PLATE_INSET: Dp = 14.dp

    /** `rounded-2xl` on the backing plate. */
    val PLATE_RADIUS: Dp = 16.dp

    /** `size = 60` — the TSX default. */
    val DEFAULT_SIZE: Dp = 60.dp
}

/**
 * `<ItemPreview species category slot value size />`.
 *
 * `aria-hidden` on the web — the tile's own label names the item, so a second
 * announcement here would make every shop row read twice.
 */
@Composable
fun ItemPreview(
    species: Species,
    category: CustomizationCategory,
    slot: String,
    value: String,
    reduceMotion: ReduceMotionSource,
    modifier: Modifier = Modifier,
    size: Dp = ItemPreviewMetrics.DEFAULT_SIZE,
) {
    Box(
        modifier = modifier
            .size(size + ItemPreviewMetrics.PLATE_INSET)
            .background(
                Palette.previewPlate.color,
                RoundedCornerShape(ItemPreviewMetrics.PLATE_RADIUS),
            )
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        // `preview` clips, pins the growth scale and never bobs — the rig's own
        // thumbnail mode.
        Ollie(
            config = ItemPreviewModel.previewConfig(species, category, slot, value),
            mood = Mood.IDLE,
            reduceMotion = reduceMotion,
            size = size,
            preview = true,
            focus = ItemPreviewModel.focus(species, category, slot, value),
        )
    }
}
