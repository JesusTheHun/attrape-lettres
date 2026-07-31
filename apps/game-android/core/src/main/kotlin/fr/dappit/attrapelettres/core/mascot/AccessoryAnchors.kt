package fr.dappit.attrapelettres.core.mascot

import fr.dappit.attrapelettres.core.domain.Species

// Port of `src/mascot/anchors.ts`.

/**
 * WHERE each *worn* accessory sits — resolved from the live per-stage `layout`
 * so a ribbon / collar / scarf tracks the head as it shrinks from the huge-headed
 * newborn to the slim adult, instead of a fixed body-relative point that the
 * baby's oversized head rides straight over (the "bow in the middle of the face"
 * bug). Placement is a pure function of (species, stage) — one source of truth,
 * eyeballed in the Storybook accessory matrix.
 *
 * Owned by AGENT A.
 */
// NB: the three anchor shapes are their own data classes rather than a shared
// point type — :core may not touch `android.graphics` (A1), and two of the
// three carry a radius/half-width a point has nowhere to put.
data class NeckAnchor(val x: Double, val y: Double, val w: Double)

data class HeadPointAnchor(val x: Double, val y: Double)

data class HeadDiscAnchor(val x: Double, val y: Double, val r: Double)

data class AccessoryAnchors(
    /**
     * Throat / collar line — neck-worn items sit here (ribbon, bell collar, scarf).
     * `x`,`y` = centre of the throat; `w` = half-width for a collar arc. Always
     * BELOW the muzzle and glued to the head, so it never climbs onto the face.
     */
    val neck: NeckAnchor,
    /** Dome apex — hats / beanies seat just above this. */
    val headTop: HeadPointAnchor,
    /** Upper-left of the face — side clips (star clip). */
    val headSide: HeadPointAnchor,
    /** Head centre + radius — ring items fan around this (flower crown). */
    val head: HeadDiscAnchor,
    /** Per-stage legs — foot-worn items follow these (boots). */
    val feet: List<LegSpec>,
)

/**
 * How far below the head centre the throat sits, in ×headR. Tuned per muzzle so
 * the collar clears the mouth on EVERY stage: the fox snout sits lowest on the
 * head, so its throat drops a touch less; the cat muzzle sits highest, so it can
 * drop the most. These are the only species-specific numbers here.
 */
/* Rabbit: tiny nose high on the face like the cat — same drop. */
/* Dragon: wide low snout like the fox — same drop (tuned in the items QA). */
// NB: a `when` with no else, not the TS Record — the compiler checks totality,
// so the missed-lookup failure mode (a collar silently drawn on the chin) does
// not exist here. The tuned VALUES are still pinned by `AccessoryAnchorsTest`.
private fun neckK(species: Species): Double = when (species) {
    Species.UNICORN -> 0.9
    Species.CAT -> 0.94
    Species.FOX -> 0.86
    Species.RABBIT -> 0.94
    Species.DRAGON -> 0.86
}

fun accessoryAnchors(species: Species, layout: Layout): AccessoryAnchors {
    val headCX = layout.headCX
    val headCY = layout.headCY
    val headR = layout.headR
    val bodyCX = layout.bodyCX

    // Standing/wobbly/proud: head sits above the chest → throat is straight below
    // the head centre. Lying newborn (stades 0-1): the head rests low and FORWARD
    // over the belly, so the throat is pulled back toward the body centre and up.
    val neck = if (layout.standing) {
        NeckAnchor(x = headCX, y = headCY + headR * neckK(species), w = headR * 0.7)
    } else {
        NeckAnchor(x = (headCX + bodyCX) / 2, y = headCY + headR * 0.6, w = headR * 0.6)
    }

    return AccessoryAnchors(
        neck = neck,
        headTop = HeadPointAnchor(x = headCX, y = headCY - headR),
        headSide = HeadPointAnchor(x = headCX - headR * 0.72, y = headCY - headR * 0.52),
        head = HeadDiscAnchor(x = headCX, y = headCY, r = headR),
        feet = layout.legs,
    )
}
