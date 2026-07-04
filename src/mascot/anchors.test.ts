import { describe, expect, it } from "vitest";
import { accessoryAnchors } from "./anchors";
import { layoutFor } from "./growth";
import { GROWTH_STAGES, type Species } from "../types";

/**
 * The regression these lock: a neck-worn accessory (ribbon / bell collar / scarf)
 * must sit BELOW the muzzle and stay glued to the head at every stage — never on
 * the face, which is what happened when it was anchored to a body-relative point
 * the baby's oversized head rode over.
 */

const SPECIES: Species[] = ["unicorn", "cat", "fox"];
const STAGES = Array.from({ length: GROWTH_STAGES }, (_, i) => i);

// Upright head (standing): the drawn muzzle reaches ~0.74·headR below the head
// centre, so the throat must clear that. Lying newborn: the head rests forward &
// down, so the meaningful floor is just the mouth line (~0.5·headR).
const MUZZLE_K_STANDING = 0.75;
const MOUTH_K = 0.5;

describe("accessoryAnchors — throat placement clears the face", () => {
  for (const species of SPECIES) {
    for (const stage of STAGES) {
      it(`${species} stade ${stage}: collar below the muzzle, on-canvas`, () => {
        const layout = layoutFor(stage);
        const { neck } = accessoryAnchors(species, layout);
        // Below the muzzle/mouth → not on the face.
        const floor = layout.headCY + layout.headR * (layout.standing ? MUZZLE_K_STANDING : MOUTH_K);
        expect(neck.y).toBeGreaterThan(floor);
        // Still on the creature (never off the ground line).
        expect(neck.y).toBeLessThanOrEqual(layout.feetY);
        // Centred on the head’s vertical axis (within the head’s width).
        expect(Math.abs(neck.x - layout.headCX)).toBeLessThanOrEqual(layout.headR);
        expect(neck.w).toBeGreaterThan(0);
      });
    }
  }
});

describe("accessoryAnchors — head & feet anchors", () => {
  it("hat seat sits above the head; side clip to the upper-left; feet track the legs", () => {
    for (const species of SPECIES) {
      for (const stage of STAGES) {
        const layout = layoutFor(stage);
        const a = accessoryAnchors(species, layout);
        expect(a.headTop.y).toBeLessThan(layout.headCY); // above the dome
        expect(a.headSide.x).toBeLessThan(layout.headCX); // upper-left of the face
        expect(a.headSide.y).toBeLessThan(layout.headCY);
        expect(a.feet).toBe(layout.legs); // boots follow the per-stage legs
      }
    }
  });
});
