import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type {
  ChildProfile,
  CustomizationOption,
  ExerciseId,
  MascotConfig,
  PersistedProfile,
  Profile,
  Roster,
  Species,
  SpeciesProgress,
} from "../types";
import { ledgerKey, rewardFor } from "../rewards";
import {
  loadRoster,
  loadV1Profile,
  loadV2Profile,
  saveRoster,
} from "../storage";

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

const ALL_SPECIES: Species[] = ["unicorn", "cat", "fox"];

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

function blankProgress(species: Species): SpeciesProgress {
  return { config: blankConfig(species), owned: [] };
}

function blankSpeciesMap(): Record<Species, SpeciesProgress> {
  return {
    unicorn: blankProgress("unicorn"),
    cat: blankProgress("cat"),
    fox: blankProgress("fox"),
  };
}

const DEFAULT_PROFILE: PersistedProfile = {
  chosen: false,
  current: "unicorn",
  species: blankSpeciesMap(),
  balance: 0,
  ledger: {},
};

/** Old v1 shape, kept only so we can migrate it forward. */
interface LegacyProfile {
  chosen?: boolean;
  config?: Partial<MascotConfig> & { species?: Species };
  balance?: number;
  ledger?: Record<string, number>;
  owned?: string[];
}

function normalizeSpecies(
  partial: Partial<Record<Species, SpeciesProgress>> | undefined
): Record<Species, SpeciesProgress> {
  const out = blankSpeciesMap();
  for (const s of ALL_SPECIES) {
    const src = partial?.[s];
    if (src) {
      out[s] = {
        config: { ...blankConfig(s), ...src.config, species: s },
        owned: src.owned ?? [],
      };
    }
  }
  return out;
}

function normalizeProfile(p: PersistedProfile): PersistedProfile {
  return {
    chosen: p.chosen ?? false,
    current: p.current ?? "unicorn",
    species: normalizeSpecies(p.species),
    balance: p.balance ?? 0,
    ledger: p.ledger ?? {},
  };
}

/** v1 (single mascot) → v2 profile shape: that mascot fills its species slot. */
function migrateLegacy(l: LegacyProfile): PersistedProfile {
  const current = l.config?.species ?? "unicorn";
  const species = blankSpeciesMap();
  species[current] = {
    config: { ...blankConfig(current), ...l.config, species: current },
    owned: l.owned ?? [],
  };
  return {
    chosen: l.chosen ?? true,
    current,
    species,
    balance: l.balance ?? 0,
    ledger: l.ledger ?? {},
  };
}

function child(name: string, profile: PersistedProfile): ChildProfile {
  return { id: newId(), name: name.trim() || "Joueur", profile };
}

function normalizeRoster(r: Roster): Roster {
  const children = (r.children ?? []).map((c) => ({
    id: c.id || newId(),
    name: c.name ?? "Joueur",
    profile: normalizeProfile(c.profile),
  }));
  const activeId = children.some((c) => c.id === r.activeId) ? r.activeId : null;
  return { children, activeId };
}

/** Migrate the best available saved data into a roster (v3 → v2 → v1 → empty). */
function initialRoster(): Roster {
  const v3 = loadRoster();
  if (v3) return normalizeRoster(v3);

  const v2 = loadV2Profile();
  if (v2) {
    const c = child("Joueur 1", normalizeProfile(v2));
    return { children: [c], activeId: c.id };
  }

  const v1 = loadV1Profile();
  if (v1) {
    const c = child("Joueur 1", migrateLegacy(v1 as LegacyProfile));
    return { children: [c], activeId: c.id };
  }

  return { children: [], activeId: null };
}

function activeProfileOf(r: Roster): PersistedProfile {
  return r.children.find((c) => c.id === r.activeId)?.profile ?? DEFAULT_PROFILE;
}

/** Persisted shape → runtime view: flatten the current species' config/owned. */
function expose(p: PersistedProfile): Profile {
  const cur = p.species[p.current];
  return { ...p, config: cur.config, owned: cur.owned };
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

  /** Award for clearing (exercise, level). Returns points granted; decays per repeat. */
  award: (exercise: ExerciseId, level: number) => number;
  /** Points the NEXT clear of (exercise, level) grants — for "seen in advance" cues. */
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
  const updateActive = useCallback(
    (fn: (p: PersistedProfile) => PersistedProfile) => {
      const r = ref.current;
      if (!r.activeId) return;
      commit({
        ...r,
        children: r.children.map((c) =>
          c.id === r.activeId ? { ...c, profile: fn(c.profile) } : c
        ),
      });
    },
    [commit]
  );

  const award = useCallback(
    (exercise: ExerciseId, level: number) => {
      const key = ledgerKey(exercise, level);
      const prior = activeProfileOf(ref.current).ledger[key] ?? 0;
      const points = rewardFor(prior);
      updateActive((p) => ({
        ...p,
        balance: p.balance + points,
        ledger: { ...p.ledger, [key]: (p.ledger[key] ?? 0) + 1 },
      }));
      return points;
    },
    [updateActive]
  );

  const preview = useCallback(
    (exercise: ExerciseId, level: number) =>
      rewardFor(activeProfileOf(ref.current).ledger[ledgerKey(exercise, level)] ?? 0),
    []
  );

  const spend = useCallback(
    (cost: number) => {
      if (activeProfileOf(ref.current).balance < cost) return false;
      updateActive((p) => ({ ...p, balance: p.balance - cost }));
      return true;
    },
    [updateActive]
  );

  const setConfig = useCallback(
    (next: MascotConfig | ((c: MascotConfig) => MascotConfig)) => {
      updateActive((p) => {
        const cur = p.species[p.current];
        const config = typeof next === "function" ? next(cur.config) : next;
        return withCurrent(p, { ...cur, config });
      });
    },
    [updateActive]
  );

  const buy = useCallback(
    (option: CustomizationOption) => {
      const p = activeProfileOf(ref.current);
      const cur = p.species[p.current];
      const owned = cur.owned.includes(option.id);
      if (!owned && p.balance < option.cost) return false;
      updateActive((pp) => {
        const c = pp.species[pp.current];
        const already = c.owned.includes(option.id);
        return {
          ...pp,
          balance: already ? pp.balance : pp.balance - option.cost,
          species: {
            ...pp.species,
            [pp.current]: {
              config: applyOption(c.config, option),
              owned: already ? c.owned : [...c.owned, option.id],
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
      updateActive((p) => ({ ...p, chosen: true, current: species }));
    },
    [updateActive]
  );

  const createChild = useCallback(
    (name: string) => {
      const c = child(name, DEFAULT_PROFILE);
      commit({ children: [...ref.current.children, c], activeId: c.id });
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
      commit({
        ...r,
        children: r.children.map((c) =>
          c.id === id ? { ...c, name: trimmed.slice(0, 14) } : c
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
