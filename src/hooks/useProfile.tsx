import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { App as CapApp } from "@capacitor/app";
import type {
  ChildProfile,
  ClearCounters,
  CustomizationOption,
  ExerciseId,
  MascotConfig,
  PersistedProfile,
  Profile,
  Rev,
  Roster,
  Species,
  SpeciesProgress,
  StarCounters,
} from "../types";
import { ledgerKey, previewReward, sessionReward } from "../rewards";
import { exerciseDifficulty } from "../levels";
import {
  loadRoster,
  loadV1Profile,
  loadV2Profile,
  loadV3Roster,
  saveRoster,
} from "../storage";
import { deviceId } from "../device";
import {
  balanceOf,
  bump,
  emptyStars,
  ledgerOf,
  newRev,
} from "../sync/merge";
import { syncEnabled, syncOnce } from "../sync/client";

/* -------------------------------------------------------------------------- */
/* useProfile — single source of truth for players, points, mascots, progress. */
/* Wrap the app in <ProfileProvider>; read/write it anywhere via useProfile().  */
/* Persistence goes through storage.ts (the swappable native seam).            */
/*                                                                            */
/* Three ownership tiers:                                                      */
/*   • device : a Roster of named children (siblings share the tablet)         */
/*   • child  : stars (balance) + cleared-levels (ledger) are GLOBAL to a child */
/*   • species: each mascot keeps its own growth/look/items (switch is safe)   */
/* All mutations below act on the ACTIVE child; roster ops switch who that is.  */
/* -------------------------------------------------------------------------- */

const ALL_SPECIES: Species[] = ["unicorn", "cat", "fox", "rabbit", "dragon"];

function newId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `c_${Date.now().toString(36)}_${Math.floor(Math.random() * 1e9).toString(36)}`;
  }
}

function blankConfig(species: Species): MascotConfig {
  return { species, stage: 0, colors: {}, styles: {}, accessories: [] };
}

/** The stamp every never-written LWW field starts at — always loses a merge. */
const ZERO_REV: Rev = { at: 0, by: "" };

function blankProgress(species: Species): SpeciesProgress {
  return { config: blankConfig(species), owned: [], rev: ZERO_REV };
}

function blankSpeciesMap(): Record<Species, SpeciesProgress> {
  return {
    unicorn: blankProgress("unicorn"),
    cat: blankProgress("cat"),
    fox: blankProgress("fox"),
    rabbit: blankProgress("rabbit"),
    dragon: blankProgress("dragon"),
  };
}

const DEFAULT_PROFILE: PersistedProfile = {
  chosen: false,
  current: "unicorn",
  currentRev: ZERO_REV,
  species: blankSpeciesMap(),
  stars: emptyStars(),
  clears: {},
};

/** Old v1 shape (single mascot), kept only so we can migrate it forward. */
interface LegacyV1Profile {
  chosen?: boolean;
  config?: Partial<MascotConfig> & { species?: Species };
  balance?: number;
  ledger?: Record<string, number>;
  owned?: string[];
}

/**
 * The v2/v3 profile shape — identical to each other, and to v4 except that
 * stars and clears were bare running totals. One migrator serves both.
 */
interface LegacyFlatProfile {
  chosen?: boolean;
  current?: Species;
  species?: Partial<Record<Species, { config?: Partial<MascotConfig>; owned?: string[] }>>;
  balance?: number;
  ledger?: Record<string, number>;
}

interface LegacyV3Roster {
  children?: { id?: string; name?: string; profile?: LegacyFlatProfile }[];
  activeId?: string | null;
}

/** Anything shaped enough to be read as progress — a v4 blob or a legacy one. */
type LooseProgress = { config?: Partial<MascotConfig>; owned?: string[]; rev?: Rev };
type LooseProfile = {
  chosen?: boolean;
  current?: Species;
  currentRev?: Rev;
  species?: Partial<Record<Species, LooseProgress>>;
  stars?: Partial<StarCounters>;
  clears?: ClearCounters;
};

function normalizeSpecies(
  partial: Partial<Record<Species, LooseProgress>> | undefined
): Record<Species, SpeciesProgress> {
  const out = blankSpeciesMap();
  for (const s of ALL_SPECIES) {
    const src = partial?.[s];
    if (src) {
      out[s] = {
        config: { ...blankConfig(s), ...src.config, species: s },
        owned: src.owned ?? [],
        rev: src.rev ?? ZERO_REV,
      };
    }
  }
  return out;
}

function normalizeProfile(p: LooseProfile): PersistedProfile {
  return {
    chosen: p.chosen ?? false,
    current: p.current ?? "unicorn",
    currentRev: p.currentRev ?? ZERO_REV,
    species: normalizeSpecies(p.species),
    stars: { earned: p.stars?.earned ?? {}, spent: p.stars?.spent ?? {} },
    clears: p.clears ?? {},
  };
}

/**
 * v2/v3 → v4. The flat totals become "everything earned on THIS device", which
 * is the only honest seeding: before v4 there was no sync, so whatever a device
 * holds is exactly what that device produced. Two phones that migrate their own
 * blobs and meet later therefore SUM — correct, they really were two separate
 * progressions. `balance` was already net of spending, so it seeds `earned`
 * with `spent` empty and the spendable total comes out unchanged.
 */
function migrateFlatProfile(l: LegacyFlatProfile, device: string): PersistedProfile {
  const clears: ClearCounters = {};
  for (const [key, n] of Object.entries(l.ledger ?? {})) {
    if (n > 0) clears[key] = { [device]: n };
  }
  const balance = l.balance ?? 0;
  return normalizeProfile({
    chosen: l.chosen,
    current: l.current,
    species: l.species,
    stars: { earned: balance > 0 ? { [device]: balance } : {}, spent: {} },
    clears,
  });
}

/** v1 (single mascot) → v4: that mascot fills its species slot, then as above. */
function migrateV1Profile(l: LegacyV1Profile, device: string): PersistedProfile {
  const current = l.config?.species ?? "unicorn";
  return migrateFlatProfile(
    {
      chosen: l.chosen ?? true,
      current,
      species: { [current]: { config: l.config, owned: l.owned ?? [] } },
      balance: l.balance,
      ledger: l.ledger,
    },
    device
  );
}

function child(
  name: string,
  profile: PersistedProfile,
  device: string,
  now: number
): ChildProfile {
  return {
    id: newId(),
    name: name.trim() || "Joueur",
    nameRev: newRev(device, now),
    touchedAt: now,
    profile,
  };
}

function normalizeRoster(r: Roster): Roster {
  const children = (r.children ?? []).map((c) => ({
    id: c.id || newId(),
    name: c.name ?? "Joueur",
    nameRev: c.nameRev ?? ZERO_REV,
    touchedAt: c.touchedAt ?? 0,
    profile: normalizeProfile(c.profile),
  }));
  const activeId = children.some((c) => c.id === r.activeId) ? r.activeId : null;
  return { children, activeId, removed: r.removed ?? {} };
}

/** Migrate the best available saved data into a roster (v4 → v3 → v2 → v1). */
function initialRoster(): Roster {
  const device = deviceId();
  const now = Date.now();

  const v4 = loadRoster();
  if (v4) return normalizeRoster(v4);

  const v3 = loadV3Roster() as LegacyV3Roster | null;
  if (v3?.children?.length) {
    const children = v3.children.map((c) => ({
      id: c.id || newId(),
      name: c.name ?? "Joueur",
      nameRev: ZERO_REV,
      // Fresh migration: nothing can have tombstoned these yet, and marking
      // them touched keeps a future stale tombstone from erasing them.
      touchedAt: now,
      profile: migrateFlatProfile(c.profile ?? {}, device),
    }));
    const activeId = children.some((c) => c.id === v3.activeId) ? v3.activeId! : null;
    return { children, activeId, removed: {} };
  }

  const v2 = loadV2Profile() as LegacyFlatProfile | null;
  if (v2) {
    const c = child("Joueur 1", migrateFlatProfile(v2, device), device, now);
    return { children: [c], activeId: c.id, removed: {} };
  }

  const v1 = loadV1Profile();
  if (v1) {
    const c = child("Joueur 1", migrateV1Profile(v1 as LegacyV1Profile, device), device, now);
    return { children: [c], activeId: c.id, removed: {} };
  }

  return { children: [], activeId: null, removed: {} };
}

function activeProfileOf(r: Roster): PersistedProfile {
  return r.children.find((c) => c.id === r.activeId)?.profile ?? DEFAULT_PROFILE;
}

/**
 * Persisted shape → runtime view: flatten the current species' config/owned and
 * fold the counters back into the plain `balance`/`ledger` the whole UI reads.
 * Everything outside this hook, storage.ts and sync/merge.ts sees numbers.
 */
function expose(p: PersistedProfile): Profile {
  const cur = p.species[p.current];
  return {
    ...p,
    config: cur.config,
    owned: cur.owned,
    balance: balanceOf(p.stars),
    ledger: ledgerOf(p.clears),
  };
}

/** Replace only the current species' progress inside a profile. */
function withCurrent(p: PersistedProfile, progress: SpeciesProgress): PersistedProfile {
  return { ...p, species: { ...p.species, [p.current]: progress } };
}

export interface ProfileAPI {
  /** The ACTIVE child's flattened profile (a chosen=false default if none active). */
  profile: Profile;
  /** Everyone on this device. */
  children: ChildProfile[];
  /** The child currently playing, or null while on the welcome screen. */
  activeId: string | null;

  /** Award for clearing (exercise, level). Returns points granted: the decaying
   *  completion curve plus the first-try accuracy bonus (see sessionReward).
   *  Training exercises (difficulty 0) grant 0 but still count in the ledger. */
  award: (exercise: ExerciseId, level: number, perfectRounds: number, totalRounds: number) => number;
  /** Points the NEXT clear of (exercise, level) guarantees — for "seen in advance" cues. */
  preview: (exercise: ExerciseId, level: number) => number;
  /** Spend points if affordable. Returns success. */
  spend: (cost: number) => boolean;
  /** Buy + own + equip/apply an option for the current species. No-op if unaffordable; re-equips if owned. */
  buy: (option: CustomizationOption) => boolean;
  /** Mutate the current species' mascot config (equip owned items, grow a stage, …). */
  setConfig: (next: MascotConfig | ((c: MascotConfig) => MascotConfig)) => void;
  /** Pick / switch the active mascot. Non-destructive: each species keeps its progress. */
  chooseSpecies: (species: Species) => void;

  /** Create a new child and make them active (their species picker follows). */
  createChild: (name: string) => void;
  /** Make an existing child the active player. */
  selectChild: (id: string) => void;
  /** Rename a child. Ignored if the trimmed name is empty. */
  renameChild: (id: string, name: string) => void;
  /** Delete a child and everything they own. */
  deleteChild: (id: string) => void;
  /** Return to the "Qui joue ?" welcome screen (no active player). */
  switchChild: () => void;
}

const Ctx = createContext<ProfileAPI | null>(null);

/** What `config` looks like with this option applied. Pure — the shop also uses
 * it to dress the live preview during a try-on, without touching the profile. */
export function applyOption(c: MascotConfig, o: CustomizationOption): MascotConfig {
  switch (o.category) {
    case "accessory":
      return c.accessories.includes(o.id)
        ? c
        : { ...c, accessories: [...c.accessories, o.id] };
    case "color":
      return { ...c, colors: { ...c.colors, [o.slot]: o.value } };
    case "style":
      return { ...c, styles: { ...c.styles, [o.slot]: o.value } };
  }
}

export function ProfileProvider({ children: kids }: { children: ReactNode }) {
  const [roster, setRoster] = useState<Roster>(initialRoster);
  // Mirror in a ref so award/spend/buy read the freshest value synchronously.
  const ref = useRef(roster);
  ref.current = roster;

  const commit = useCallback((next: Roster) => {
    ref.current = next;
    setRoster(next);
    saveRoster(next);
  }, []);

  // Rewrite the active child's profile; no-op if nobody is playing.
  // Every write stamps `touchedAt` — the delete rule in sync/merge.ts reads it
  // to refuse a tombstone that would erase play this device has since done.
  const updateActive = useCallback(
    (fn: (p: PersistedProfile) => PersistedProfile) => {
      const r = ref.current;
      if (!r.activeId) return;
      const now = Date.now();
      commit({
        ...r,
        children: r.children.map((c) =>
          c.id === r.activeId ? { ...c, touchedAt: now, profile: fn(c.profile) } : c
        ),
      });
    },
    [commit]
  );

  /**
   * Household sync: pull, merge, push — on mount and every time the app comes
   * back to the foreground. Safe to fire as often as we like; merge.ts is
   * idempotent. Deliberately NOT on every write: gameplay stays offline-first,
   * and a child mid-round must never wait on a network call.
   */
  useEffect(() => {
    if (!syncEnabled()) return;
    const run = () => {
      void syncOnce(ref.current)
        .then((merged) => {
          if (merged !== ref.current) commit(merged);
        })
        .catch(() => {
          /* no signal, or the server is down: the device keeps playing alone */
        });
    };
    run();
    let cancel: (() => void) | undefined;
    void CapApp.addListener("appStateChange", ({ isActive }) => {
      if (isActive) run();
    })
      .then((h) => {
        cancel = () => void h.remove();
      })
      .catch(() => {
        /* web build: no native app-state events */
      });
    return () => cancel?.();
  }, [commit]);

  const award = useCallback(
    (exercise: ExerciseId, level: number, perfectRounds: number, totalRounds: number) => {
      const key = ledgerKey(exercise, level);
      const prior = ledgerOf(activeProfileOf(ref.current).clears)[key] ?? 0;
      const points = sessionReward(exerciseDifficulty(exercise), prior, perfectRounds, totalRounds);
      const device = deviceId();
      updateActive((p) => ({
        ...p,
        stars: { ...p.stars, earned: bump(p.stars.earned, device, points) },
        clears: { ...p.clears, [key]: bump(p.clears[key] ?? {}, device, 1) },
      }));
      return points;
    },
    [updateActive]
  );

  const preview = useCallback(
    (exercise: ExerciseId, level: number) =>
      previewReward(
        ledgerOf(activeProfileOf(ref.current).clears),
        exercise,
        level,
        exerciseDifficulty(exercise)
      ),
    []
  );

  const spend = useCallback(
    (cost: number) => {
      if (balanceOf(activeProfileOf(ref.current).stars) < cost) return false;
      const device = deviceId();
      updateActive((p) => ({
        ...p,
        stars: { ...p.stars, spent: bump(p.stars.spent, device, cost) },
      }));
      return true;
    },
    [updateActive]
  );

  const setConfig = useCallback(
    (next: MascotConfig | ((c: MascotConfig) => MascotConfig)) => {
      const rev = newRev(deviceId(), Date.now());
      updateActive((p) => {
        const cur = p.species[p.current];
        const config = typeof next === "function" ? next(cur.config) : next;
        return withCurrent(p, { ...cur, config, rev });
      });
    },
    [updateActive]
  );

  const buy = useCallback(
    (option: CustomizationOption) => {
      const p = activeProfileOf(ref.current);
      const cur = p.species[p.current];
      const owned = cur.owned.includes(option.id);
      if (!owned && balanceOf(p.stars) < option.cost) return false;
      const device = deviceId();
      const rev = newRev(device, Date.now());
      updateActive((pp) => {
        const c = pp.species[pp.current];
        const already = c.owned.includes(option.id);
        return {
          ...pp,
          stars: already
            ? pp.stars
            : { ...pp.stars, spent: bump(pp.stars.spent, device, option.cost) },
          species: {
            ...pp.species,
            [pp.current]: {
              config: applyOption(c.config, option),
              owned: already ? c.owned : [...c.owned, option.id],
              rev,
            },
          },
        };
      });
      return true;
    },
    [updateActive]
  );

  const chooseSpecies = useCallback(
    (species: Species) => {
      const currentRev = newRev(deviceId(), Date.now());
      updateActive((p) => ({ ...p, chosen: true, current: species, currentRev }));
    },
    [updateActive]
  );

  const createChild = useCallback(
    (name: string) => {
      const r = ref.current;
      const c = child(name, DEFAULT_PROFILE, deviceId(), Date.now());
      commit({ ...r, children: [...r.children, c], activeId: c.id });
    },
    [commit]
  );

  const selectChild = useCallback(
    (id: string) => commit({ ...ref.current, activeId: id }),
    [commit]
  );

  const renameChild = useCallback(
    (id: string, name: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      const r = ref.current;
      const now = Date.now();
      const nameRev = newRev(deviceId(), now);
      commit({
        ...r,
        children: r.children.map((c) =>
          c.id === id
            ? { ...c, name: trimmed.slice(0, 14), nameRev, touchedAt: now }
            : c
        ),
      });
    },
    [commit]
  );

  const deleteChild = useCallback(
    (id: string) => {
      const r = ref.current;
      commit({
        children: r.children.filter((c) => c.id !== id),
        activeId: r.activeId === id ? null : r.activeId,
        // Tombstone, not just a removal: without it the family's other device
        // still has the child and would hand them straight back on next merge.
        removed: { ...r.removed, [id]: Date.now() },
      });
    },
    [commit]
  );

  const switchChild = useCallback(
    () => commit({ ...ref.current, activeId: null }),
    [commit]
  );

  const value = useMemo<ProfileAPI>(
    () => ({
      profile: expose(activeProfileOf(roster)),
      children: roster.children,
      activeId: roster.activeId,
      award,
      preview,
      spend,
      buy,
      setConfig,
      chooseSpecies,
      createChild,
      selectChild,
      renameChild,
      deleteChild,
      switchChild,
    }),
    [
      roster,
      award,
      preview,
      spend,
      buy,
      setConfig,
      chooseSpecies,
      createChild,
      selectChild,
      renameChild,
      deleteChild,
      switchChild,
    ]
  );

  return <Ctx.Provider value={value}>{kids}</Ctx.Provider>;
}

export function useProfile(): ProfileAPI {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useProfile must be used within <ProfileProvider>");
  return ctx;
}
