import { describe, it, expect } from "vitest";
import type {
  ChildProfile,
  PersistedProfile,
  Roster,
  Species,
  SpeciesProgress,
} from "../types";
import {
  balanceOf,
  bump,
  ledgerOf,
  mergeCounter,
  mergeOwned,
  mergeProfile,
  mergeRoster,
  mergeStars,
} from "./merge";
import { rewardFor } from "../rewards";

/* -------------------------------------------------------------------------- */
/* The scenario every test here is really about: Léa plays on Dad's phone in    */
/* the car and on Mum's phone at home. Neither device has signal. Whatever we   */
/* do when they finally meet, she must not lose a single star.                  */
/* -------------------------------------------------------------------------- */

const DAD = "dad-phone";
const MUM = "mum-phone";
const PAD = "family-ipad";

const ALL: Species[] = ["unicorn", "cat", "fox", "rabbit", "dragon"];

function progress(s: Species, over: Partial<SpeciesProgress> = {}): SpeciesProgress {
  return {
    config: { species: s, stage: 0, colors: {}, styles: {}, accessories: [] },
    owned: [],
    rev: { at: 0, by: "" },
    ...over,
  };
}

function speciesMap(
  over: Partial<Record<Species, SpeciesProgress>> = {}
): Record<Species, SpeciesProgress> {
  const out = {} as Record<Species, SpeciesProgress>;
  for (const s of ALL) out[s] = progress(s);
  return { ...out, ...over };
}

function profile(over: Partial<PersistedProfile> = {}): PersistedProfile {
  return {
    chosen: false,
    current: "unicorn",
    currentRev: { at: 0, by: "" },
    species: speciesMap(),
    stars: { earned: {}, spent: {} },
    clears: {},
    ...over,
  };
}

function kid(id: string, over: Partial<ChildProfile> = {}): ChildProfile {
  return {
    id,
    name: id,
    nameRev: { at: 0, by: "" },
    touchedAt: 0,
    profile: profile(),
    ...over,
  };
}

function roster(over: Partial<Roster> = {}): Roster {
  return { children: [], activeId: null, removed: {}, ...over };
}

/** Léa earned `n` stars on `device` and spent `spent`. */
function earned(device: string, n: number, spent = 0): PersistedProfile {
  return profile({ stars: { earned: { [device]: n }, spent: spent ? { [device]: spent } : {} } });
}

describe("counters — the lossless half", () => {
  it("takes the fresher count per device, never the sum of the same device", () => {
    // Dad's phone at 10 stars syncs, earns 3 more, syncs again. Not 23.
    expect(mergeCounter({ [DAD]: 10 }, { [DAD]: 13 })).toEqual({ [DAD]: 13 });
  });

  it("keeps BOTH devices' earnings — the whole point", () => {
    const dad = earned(DAD, 10);
    const mum = earned(MUM, 3);
    expect(balanceOf(mergeProfile(dad, mum).stars)).toBe(13);
  });

  it("is commutative — sync order cannot change the answer", () => {
    const a = earned(DAD, 10);
    const b = earned(MUM, 3);
    expect(mergeProfile(a, b)).toEqual(mergeProfile(b, a));
  });

  it("is idempotent — syncing twice is not earning twice", () => {
    const a = earned(DAD, 10);
    const b = earned(MUM, 3);
    const once = mergeProfile(a, b);
    expect(mergeProfile(once, b)).toEqual(once);
    expect(balanceOf(mergeProfile(mergeProfile(once, b), b).stars)).toBe(13);
  });

  it("is order-independent across three devices", () => {
    const a = earned(DAD, 10);
    const b = earned(MUM, 3);
    const c = earned(PAD, 7);
    const left = mergeProfile(mergeProfile(a, b), c);
    const right = mergeProfile(a, mergeProfile(b, c));
    expect(balanceOf(left.stars)).toBe(20);
    expect(left).toEqual(right);
  });

  it("bump only ever touches this device's own slot", () => {
    const after = bump({ [DAD]: 10, [MUM]: 3 }, DAD, 5);
    expect(after).toEqual({ [DAD]: 15, [MUM]: 3 });
  });
});

describe("stars — spending across devices", () => {
  it("subtracts spending from earnings wherever each happened", () => {
    const dad = earned(DAD, 10, 4);
    const mum = earned(MUM, 6);
    expect(balanceOf(mergeProfile(dad, mum).stars)).toBe(12);
  });

  it("floors at zero when two offline devices both spend the same stars", () => {
    // Both see 10, both buy an 8-star item. 10 earned, 16 spent.
    const shared = { earned: { [PAD]: 10 }, spent: {} };
    const dad = { ...shared, spent: { [DAD]: 8 } };
    const mum = { ...shared, spent: { [MUM]: 8 } };
    const merged = mergeStars(dad, mum);

    expect(merged.spent).toEqual({ [DAD]: 8, [MUM]: 8 });
    // The arithmetic says -6. The child sees 0 and keeps both items: we never
    // claw a purchase back from a six-year-old to balance a ledger.
    expect(balanceOf(merged)).toBe(0);
  });

  it("keeps both items bought during that overdraw", () => {
    const dad = profile({ species: speciesMap({ unicorn: progress("unicorn", { owned: ["horn"] }) }) });
    const mum = profile({ species: speciesMap({ unicorn: progress("unicorn", { owned: ["tail"] }) }) });
    expect(mergeProfile(dad, mum).species.unicorn.owned).toEqual(["horn", "tail"]);
  });
});

describe("clears — farming stays unprofitable", () => {
  it("sums clears across devices so the reward curve keeps decaying", () => {
    const dad = profile({ clears: { "read-image:1": { [DAD]: 1 } } });
    const mum = profile({ clears: { "read-image:1": { [MUM]: 1 } } });
    const ledger = ledgerOf(mergeProfile(dad, mum).clears);

    // Two clears really happened, so the next one pays the third rung — playing
    // the same level on the other phone must not re-open the 10-star jackpot.
    expect(ledger["read-image:1"]).toBe(2);
    expect(rewardFor(ledger["read-image:1"])).toBe(rewardFor(2));
    expect(rewardFor(ledger["read-image:1"])).toBeLessThan(rewardFor(0));
  });

  it("merges independent levels without touching each other", () => {
    const dad = profile({ clears: { "read-image:1": { [DAD]: 3 } } });
    const mum = profile({ clears: { "syllable-grid:2": { [MUM]: 1 } } });
    expect(ledgerOf(mergeProfile(dad, mum).clears)).toEqual({
      "read-image:1": 3,
      "syllable-grid:2": 1,
    });
  });
});

describe("cosmetics — last write wins, and losing one is harmless", () => {
  it("keeps the later mascot look", () => {
    const older = progress("unicorn", {
      config: { species: "unicorn", stage: 2, colors: { hornColor: "#F0A" }, styles: {}, accessories: [] },
      rev: { at: 100, by: DAD },
    });
    const newer = progress("unicorn", {
      config: { species: "unicorn", stage: 5, colors: { hornColor: "#0FA" }, styles: {}, accessories: [] },
      rev: { at: 200, by: MUM },
    });
    const a = profile({ species: speciesMap({ unicorn: older }) });
    const b = profile({ species: speciesMap({ unicorn: newer }) });

    expect(mergeProfile(a, b).species.unicorn.config.stage).toBe(5);
    expect(mergeProfile(b, a).species.unicorn.config.stage).toBe(5);
  });

  it("unions owned items even when the OTHER side's look wins", () => {
    const loser = progress("unicorn", { owned: ["horn"], rev: { at: 100, by: DAD } });
    const winner = progress("unicorn", { owned: ["tail"], rev: { at: 200, by: MUM } });
    const merged = mergeProfile(
      profile({ species: speciesMap({ unicorn: loser }) }),
      profile({ species: speciesMap({ unicorn: winner }) })
    );
    // Look came from Mum's phone; nothing bought on Dad's was dropped.
    expect(merged.species.unicorn.owned).toEqual(["horn", "tail"]);
  });

  it("breaks an exact timestamp tie the same way from both sides", () => {
    const a = profile({ current: "fox", currentRev: { at: 500, by: DAD } });
    const b = profile({ current: "cat", currentRev: { at: 500, by: MUM } });
    expect(mergeProfile(a, b).current).toBe(mergeProfile(b, a).current);
  });

  it("never un-picks a species once chosen", () => {
    const picked = profile({ chosen: true });
    const fresh = profile({ chosen: false });
    expect(mergeProfile(picked, fresh).chosen).toBe(true);
    expect(mergeProfile(fresh, picked).chosen).toBe(true);
  });

  it("mergeOwned is a set union that keeps the local order first", () => {
    expect(mergeOwned(["a", "b"], ["b", "c"])).toEqual(["a", "b", "c"]);
  });
});

describe("roster — siblings, deletes and who is holding the tablet", () => {
  it("brings in a sibling created on the other device", () => {
    const here = roster({ children: [kid("lea")] });
    const there = roster({ children: [kid("tom")] });
    expect(mergeRoster(here, there).children.map((c) => c.id).sort()).toEqual(["lea", "tom"]);
  });

  it("merges the same child's progress from both devices", () => {
    const here = roster({ children: [kid("lea", { profile: earned(DAD, 10) })] });
    const there = roster({ children: [kid("lea", { profile: earned(MUM, 3) })] });
    const merged = mergeRoster(here, there);
    expect(merged.children).toHaveLength(1);
    expect(balanceOf(merged.children[0].profile.stars)).toBe(13);
  });

  it("never adopts the other device's active player", () => {
    const here = roster({ children: [kid("lea")], activeId: "lea" });
    const there = roster({ children: [kid("lea"), kid("tom")], activeId: "tom" });
    // Tom being at the wheel on Mum's phone says nothing about this tablet.
    expect(mergeRoster(here, there).activeId).toBe("lea");
  });

  it("clears activeId if that child was deleted elsewhere", () => {
    const here = roster({ children: [kid("lea", { touchedAt: 10 })], activeId: "lea" });
    const there = roster({ removed: { lea: 50 } });
    const merged = mergeRoster(here, there);
    expect(merged.children).toHaveLength(0);
    expect(merged.activeId).toBeNull();
  });

  it("propagates a delete to the other device", () => {
    const here = roster({ children: [kid("lea", { touchedAt: 10 }), kid("tom", { touchedAt: 10 })] });
    const there = roster({ children: [kid("tom", { touchedAt: 10 })], removed: { lea: 99 } });
    expect(mergeRoster(here, there).children.map((c) => c.id)).toEqual(["tom"]);
  });

  it("refuses a delete that would erase play done afterwards", () => {
    // Parent tidies the roster on Mum's phone at t=50. Léa keeps playing on the
    // iPad until t=200. A vanished child is unrecoverable; a resurrected one is
    // two taps. The tie goes to keeping the data.
    const here = roster({ children: [kid("lea", { touchedAt: 200, profile: earned(PAD, 40) })] });
    const there = roster({ removed: { lea: 50 } });
    const merged = mergeRoster(here, there);
    expect(merged.children).toHaveLength(1);
    expect(balanceOf(merged.children[0].profile.stars)).toBe(40);
  });

  it("keeps tombstones so a third device also honours the delete", () => {
    const here = roster({ children: [kid("lea", { touchedAt: 10 })] });
    const there = roster({ removed: { lea: 99 } });
    const merged = mergeRoster(here, there);
    // The iPad still has Léa and no tombstone; merging must not resurrect her.
    const ipad = roster({ children: [kid("lea", { touchedAt: 10 })] });
    expect(mergeRoster(merged, ipad).children).toHaveLength(0);
  });

  it("is idempotent and agrees on membership in either direction", () => {
    const here = roster({ children: [kid("lea", { profile: earned(DAD, 10) })] });
    const there = roster({ children: [kid("lea", { profile: earned(MUM, 3) }), kid("tom")] });
    const once = mergeRoster(here, there);
    expect(mergeRoster(once, there)).toEqual(once);
    expect(mergeRoster(there, here).children.map((c) => c.id).sort()).toEqual(
      once.children.map((c) => c.id).sort()
    );
  });
});
