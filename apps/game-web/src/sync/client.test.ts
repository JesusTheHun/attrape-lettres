import { describe, it, expect, beforeEach, vi } from "vitest";
import type { ChildProfile, PersistedProfile, Roster, Species, SpeciesProgress } from "../types";
import {
  __setSyncTransport,
  createHousehold,
  fromWire,
  syncOnce,
  toWire,
  type SyncTransport,
  type WireRoster,
} from "./client";
import { balanceOf } from "./merge";

const ALL: Species[] = ["unicorn", "cat", "fox", "rabbit", "dragon"];
const ZERO = { at: 0, by: "" };

function speciesMap(): Record<Species, SpeciesProgress> {
  const out = {} as Record<Species, SpeciesProgress>;
  for (const s of ALL) {
    out[s] = {
      config: { species: s, stage: 0, colors: {}, styles: {}, accessories: [] },
      owned: [],
      rev: ZERO,
    };
  }
  return out;
}

function profile(earned: Record<string, number> = {}): PersistedProfile {
  return {
    chosen: true,
    current: "unicorn",
    currentRev: ZERO,
    species: speciesMap(),
    stars: { earned, spent: {} },
    clears: {},
  };
}

function kid(id: string, name: string, earned: Record<string, number> = {}): ChildProfile {
  return { id, name, nameRev: { at: 10, by: "dad" }, touchedAt: 10, profile: profile(earned) };
}

function roster(children: ChildProfile[], activeId: string | null = null): Roster {
  return { children, activeId, removed: {} };
}

/** An in-memory household document with ETag semantics, like the real server. */
function fakeServer(initial: WireRoster | null = null) {
  let doc = initial;
  let version = 0;
  const transport: SyncTransport = {
    async pull() {
      return doc ? { roster: doc, etag: String(version) } : null;
    },
    async push(_h, next, etag) {
      const expected = doc ? String(version) : null;
      if (etag !== expected) return "conflict";
      doc = next;
      version += 1;
      return { etag: String(version) };
    },
  };
  return {
    transport,
    get doc() {
      return doc;
    },
    /** Simulate the other phone writing while we were mid-sync. */
    interleave(next: WireRoster) {
      doc = next;
      version += 1;
    },
  };
}

beforeEach(() => {
  localStorage.clear();
  vi.stubEnv("VITE_SYNC_URL", "https://sync.test");
});

describe("the wire format — what the server is allowed to know", () => {
  it("strips names and their stamps", () => {
    const wire = toWire(roster([kid("lea", "Léa"), kid("tom", "Tom")]));
    const json = JSON.stringify(wire);
    expect(json).not.toContain("Léa");
    expect(json).not.toContain("Tom");
    expect(json).not.toContain("nameRev");
    // What DOES travel: opaque ids and integers.
    expect(wire.children.map((c) => c.id)).toEqual(["lea", "tom"]);
  });

  it("never sends who is holding this tablet", () => {
    const wire = toWire(roster([kid("lea", "Léa")], "lea")) as unknown as Record<string, unknown>;
    expect(wire.activeId).toBeUndefined();
  });

  it("re-attaches names this device already knows", () => {
    const local = roster([kid("lea", "Léa")]);
    const back = fromWire(toWire(local), local);
    expect(back.children[0].name).toBe("Léa");
    expect(back.children[0].nameRev).toEqual({ at: 10, by: "dad" });
  });

  it("gives a never-seen child a placeholder that always loses to a local name", () => {
    const fromOtherPhone = toWire(roster([kid("tom", "Tom")]));
    const joined = fromWire(fromOtherPhone, roster([]));
    expect(joined.children[0].name).toBe("Enfant");
    // Zero stamp ⇒ the moment this parent names them, that name wins forever.
    expect(joined.children[0].nameRev).toEqual(ZERO);
  });
});

describe("syncOnce", () => {
  it("does nothing until the device has joined a household", async () => {
    const server = fakeServer();
    __setSyncTransport(server.transport);
    const local = roster([kid("lea", "Léa", { dad: 10 })]);
    expect(await syncOnce(local)).toBe(local);
    expect(server.doc).toBeNull();
  });

  it("uploads the first device's roster", async () => {
    createHousehold();
    const server = fakeServer();
    __setSyncTransport(server.transport);
    await syncOnce(roster([kid("lea", "Léa", { dad: 10 })]));
    expect(server.doc?.children).toHaveLength(1);
  });

  it("converges two phones without losing either one's stars", async () => {
    createHousehold();
    const server = fakeServer();
    __setSyncTransport(server.transport);

    // Dad's phone syncs first, then Mum's phone brings its own offline earnings.
    await syncOnce(roster([kid("lea", "Léa", { dad: 10 })]));
    const onMum = await syncOnce(roster([kid("lea", "Léa", { mum: 3 })]));

    expect(onMum.children).toHaveLength(1);
    expect(balanceOf(onMum.children[0].profile.stars)).toBe(13);
    // And Mum's phone kept calling her Léa, without the server ever knowing it.
    expect(onMum.children[0].name).toBe("Léa");
    expect(JSON.stringify(server.doc)).not.toContain("Léa");
  });

  it("brings home a sibling created on the other phone", async () => {
    createHousehold();
    const server = fakeServer();
    __setSyncTransport(server.transport);
    await syncOnce(roster([kid("tom", "Tom", { mum: 4 })]));
    const here = await syncOnce(roster([kid("lea", "Léa", { dad: 10 })], "lea"));

    expect(here.children.map((c) => c.id).sort()).toEqual(["lea", "tom"]);
    expect(here.children.find((c) => c.id === "tom")!.name).toBe("Enfant");
    expect(here.activeId).toBe("lea"); // still Léa's turn on THIS tablet
  });

  it("retries a conflicting write instead of clobbering it", async () => {
    createHousehold();
    const server = fakeServer();
    __setSyncTransport(server.transport);
    await syncOnce(roster([kid("lea", "Léa", { dad: 10 })]));

    // The other phone lands a write between our pull and our push.
    let raced = false;
    const racy: SyncTransport = {
      pull: server.transport.pull,
      async push(h, next, etag) {
        if (!raced) {
          raced = true;
          server.interleave(toWire(roster([kid("lea", "Léa", { mum: 5, dad: 10 })])));
          return "conflict";
        }
        return server.transport.push(h, next, etag);
      },
    };
    __setSyncTransport(racy);

    const merged = await syncOnce(roster([kid("lea", "Léa", { dad: 10, ipad: 2 })]));
    // All three devices' earnings survive the collision.
    expect(balanceOf(merged.children[0].profile.stars)).toBe(17);
  });

  it("is idempotent — re-syncing does not double anything", async () => {
    createHousehold();
    const server = fakeServer();
    __setSyncTransport(server.transport);
    const local = roster([kid("lea", "Léa", { dad: 10 })]);
    const once = await syncOnce(local);
    const twice = await syncOnce(once);
    expect(balanceOf(twice.children[0].profile.stars)).toBe(10);
  });
});
