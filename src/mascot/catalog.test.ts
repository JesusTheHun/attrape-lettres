import { describe, expect, it } from "vitest";
import { CATALOG, DEFAULT_LOOKS } from "./catalog";
import type { Species } from "../types";

const SPECIES: Species[] = ["unicorn", "cat", "fox"];

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
  // Premium accessories gated by maturity.
  "unicorn.accessory.flower-crown": 2,
  "unicorn.accessory.star-clip": 4,
  "cat.accessory.party-hat": 4,
  "fox.accessory.boots": 4,
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
