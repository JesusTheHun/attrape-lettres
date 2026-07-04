import type { Species } from "../types";
import type { Layout } from "./growth";

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

export interface AccessoryAnchors {
  /** Throat / collar line — neck-worn items sit here (ribbon, bell collar, scarf).
   *  `x`,`y` = centre of the throat; `w` = half-width for a collar arc. Always
   *  BELOW the muzzle and glued to the head, so it never climbs onto the face. */
  neck: { x: number; y: number; w: number };
  /** Dome apex — hats / beanies seat just above this. */
  headTop: { x: number; y: number };
  /** Upper-left of the face — side clips (star clip). */
  headSide: { x: number; y: number };
  /** Head centre + radius — ring items fan around this (flower crown). */
  head: { x: number; y: number; r: number };
  /** Per-stage legs — foot-worn items follow these (boots). */
  feet: Layout["legs"];
}

/**
 * How far below the head centre the throat sits, in ×headR. Tuned per muzzle so
 * the collar clears the mouth on EVERY stage: the fox snout sits lowest on the
 * head, so its throat drops a touch less; the cat muzzle sits highest, so it can
 * drop the most. These are the only species-specific numbers here.
 */
const NECK_K: Record<Species, number> = { unicorn: 0.9, cat: 0.94, fox: 0.86 };

export function accessoryAnchors(species: Species, layout: Layout): AccessoryAnchors {
  const { headCX, headCY, headR, bodyCX } = layout;

  // Standing/wobbly/proud: head sits above the chest → throat is straight below
  // the head centre. Lying newborn (stades 0-1): the head rests low and FORWARD
  // over the belly, so the throat is pulled back toward the body centre and up.
  const neck = layout.standing
    ? { x: headCX, y: headCY + headR * NECK_K[species], w: headR * 0.7 }
    : { x: (headCX + bodyCX) / 2, y: headCY + headR * 0.6, w: headR * 0.6 };

  return {
    neck,
    headTop: { x: headCX, y: headCY - headR },
    headSide: { x: headCX - headR * 0.72, y: headCY - headR * 0.52 },
    head: { x: headCX, y: headCY, r: headR },
    feet: layout.legs,
  };
}
