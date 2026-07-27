import { describe, expect, it } from "vitest";
import { CATALOG, DEFAULT_LOOKS } from "./catalog";
import type { Species } from "../types";

const SPECIES: Species[] = ["unicorn", "cat", "fox", "rabbit", "dragon"];

/* Growth gates derived from a per-stage visual render of every rig: an option is
 * gated only when the body part it dresses isn't visible yet at that stade
 * (e.g. the unicorn is hornless until stade 2, the kitten's belly/tail are
 * tucked in its stade-0 curl). Anything absent from this map must be ungated —
 * this pins the analysis so a catalog edit can't silently regress it. */
const EXPECTED_GATES: Record<string, number> = {
  // Unicorn horn: nub at stade 2, real (twistable) horn at stade 3.
  "unicorn.color.hornColor.rose": 2,
  "unicorn.color.hornColor.turquoise": 2,
  "unicorn.style.hornStyle.spiral": 3,
  // Cat belly + tail: hidden in the stade-0 curl, shown once it sits up (1).
  "cat.color.bellyColor.rose": 1,
  "cat.color.tailColor.roux": 1,
  "cat.color.tailColor.noire": 1,
  "cat.style.tailSize.short": 1,
  // Rabbit inner ears: the tint only "blooms" at stade 3; the ear fold can't
  // show while the ears lie on the back (stades 0-1); the swimsuit is worn
  // standing only (the lying nappy read as a backpack in the blind test).
  "rabbit.color.innerEarColor.rose": 3,
  "rabbit.color.innerEarColor.menthe": 3,
  "rabbit.style.earStyle.pliees": 2,
  "rabbit.accessory.swimsuit": 2,
  // Dragon: the belly is hidden inside the stade-0 egg; horn nubs point at 2
  // (real horns at 3 for the double crown); wings sprout at 3; the crest
  // appears at 4; the tail pokes out of the egg but only reads from 1; the
  // flame tail lights when he walks (2); cape/fang need him standing (2);
  // goggles arrive with the wings (3).
  "dragon.color.bellyColor.magma": 1,
  "dragon.color.wingColor.nuit": 3,
  "dragon.color.wingColor.dorees": 3,
  "dragon.color.hornColor.or": 2,
  "dragon.color.hornColor.noires": 2,
  "dragon.style.hornStyle.double": 3,
  "dragon.style.crestStyle.lava": 4,
  "dragon.style.tailStyle.club": 1,
  "dragon.style.tailStyle.flame": 2,
  "dragon.accessory.cape": 2,
  "dragon.accessory.goggles": 3,
  "dragon.accessory.fang-necklace": 2,
  // Premium accessories gated by maturity.
  "unicorn.accessory.flower-crown": 2,
  "unicorn.accessory.star-clip": 4,
  "cat.accessory.party-hat": 4,
  "fox.accessory.boots": 4,
  "rabbit.accessory.stardust": 4,
  "dragon.accessory.blue-flame": 4,
};

describe("catalog growth gates", () => {
  it("gates exactly the options whose part isn't visible early", () => {
    for (const o of CATALOG) {
      expect(o.minStage ?? 0, `${o.id} minStage`).toBe(EXPECTED_GATES[o.id] ?? 0);
    }
  });

  it("only references gates within the growth range", () => {
    for (const o of CATALOG) {
      if (o.minStage !== undefined) {
        expect(o.minStage).toBeGreaterThanOrEqual(0);
        expect(o.minStage).toBeLessThan(10);
      }
    }
  });
});

describe("catalog default looks", () => {
  it("provides exactly one factory look per colour/style slot", () => {
    for (const species of SPECIES) {
      const variantSlots = new Set(
        CATALOG.filter(
          (o) => o.species === species && (o.category === "color" || o.category === "style")
        ).map((o) => `${o.category}:${o.slot}`)
      );
      const defaultSlots = DEFAULT_LOOKS[species].map((d) => `${d.category}:${d.slot}`);
      // No duplicates, and the two sets match exactly.
      expect(new Set(defaultSlots).size).toBe(defaultSlots.length);
      expect(new Set(defaultSlots)).toEqual(variantSlots);
    }
  });

  it("gates each default no later than its slot's variants", () => {
    for (const species of SPECIES) {
      for (const look of DEFAULT_LOOKS[species]) {
        const variantMins = CATALOG.filter(
          (o) => o.species === species && o.category === look.category && o.slot === look.slot
        ).map((o) => o.minStage ?? 0);
        const variantMin = Math.min(...variantMins);
        const defMin = look.minStage ?? 0;
        expect(defMin).toBeLessThanOrEqual(variantMin);
        // Gated variants ⇒ gated default (never advertise a look for a hidden part).
        expect(defMin > 0).toBe(variantMin > 0);
      }
    }
  });
});
