import type { ReactNode } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import { CATALOG } from "./catalog";
import { Mascot } from "./Mascot";
import { GROWTH_STAGES, type MascotConfig, type Mood, type Species } from "../types";

/**
 * Visual harness for the mascot rig. Three families of story:
 *  - Croissance*  — one pet across all 10 growth stades (baby → majestic).
 *  - Accessoires* — every accessory × every stade for one pet, so you can eyeball
 *    that each placement is glued to the right spot (the ribbon on the THROAT,
 *    not the middle of the baby's face) as the head shrinks with age.
 *  - Terrain      — a fully interactive single mascot (species/stade/accessory).
 *
 * The rig is deterministic given (species, stage, accessories), so a story IS a
 * placement spec: if a cell looks wrong here, the anchor in anchors.ts is wrong.
 */

const STAGES = Array.from({ length: GROWTH_STAGES }, (_, i) => i);
const INK = "#5A3A1E";
const FONT = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const cfg = (species: Species, stage: number, accessories: string[] = []): MascotConfig => ({
  species,
  stage,
  colors: {},
  styles: {},
  accessories,
});

const stageLabel = (s: number): string =>
  s === 0 ? "bébé" : s === GROWTH_STAGES - 1 ? "majestueux" : `stade ${s}`;

const accessoriesFor = (species: Species) =>
  CATALOG.filter((o) => o.species === species && o.category === "accessory");

/* -- Non-exported render helpers (exported consts become stories) ------------ */

function Cell({
  children,
  caption,
  sub,
  dim = false,
  badge,
}: {
  children: ReactNode;
  caption: string;
  sub?: string;
  dim?: boolean;
  badge?: string;
}) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 2,
        padding: 10,
        borderRadius: 16,
        background: "rgba(255,255,255,0.72)",
        boxShadow: "0 1px 4px rgba(0,0,0,0.08)",
        position: "relative",
        opacity: dim ? 0.55 : 1,
      }}
    >
      {badge && (
        <span style={{ position: "absolute", top: 6, right: 8, fontSize: 12 }}>{badge}</span>
      )}
      {children}
      <span style={{ fontSize: 13, fontWeight: 800 }}>{caption}</span>
      {sub && <span style={{ fontSize: 11, fontWeight: 700, color: "#9A7A5A" }}>{sub}</span>}
    </div>
  );
}

function Grid({ children, min = 120 }: { children: ReactNode; min?: number }) {
  return (
    <div
      style={{
        display: "grid",
        gap: 10,
        gridTemplateColumns: `repeat(auto-fill, minmax(${min}px, 1fr))`,
      }}
    >
      {children}
    </div>
  );
}

function Board({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={{ fontFamily: FONT, color: INK, padding: 8, maxWidth: 1100 }}>
      <h2 style={{ margin: "0 0 12px", fontSize: 20, fontWeight: 900 }}>{title}</h2>
      {children}
    </div>
  );
}

/** Every growth stade of one pet, side by side. */
function GrowthBoard({ species, label }: { species: Species; label: string }) {
  return (
    <Board title={`${label} — croissance (stade 0 → 9)`}>
      <Grid>
        {STAGES.map((stage) => (
          <Cell key={stage} caption={`Niveau ${stage + 1}`} sub={stageLabel(stage)}>
            <Mascot config={cfg(species, stage)} mood="idle" size={104} />
          </Cell>
        ))}
      </Grid>
    </Board>
  );
}

/** Every accessory of one pet, worn at every stade. Rows = accessory. */
function AccessoryBoard({ species, label }: { species: Species; label: string }) {
  const items = accessoriesFor(species);
  return (
    <Board title={`${label} — placement des accessoires × stade`}>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {items.map((o) => {
          const gate = o.minStage ?? 0;
          return (
            <div key={o.id}>
              <h3 style={{ margin: "0 0 6px", fontSize: 15, fontWeight: 800 }}>
                {o.emoji} {o.name}
                {gate > 0 && (
                  <span style={{ marginLeft: 8, fontSize: 12, fontWeight: 700, color: "#9A7A5A" }}>
                    · débloqué au stade {gate}
                  </span>
                )}
              </h3>
              <Grid min={104}>
                {STAGES.map((stage) => (
                  <Cell
                    key={stage}
                    caption={`Niveau ${stage + 1}`}
                    dim={stage < gate}
                    badge={stage < gate ? "🔒" : undefined}
                  >
                    <Mascot config={cfg(species, stage, [o.id])} mood="idle" size={96} />
                  </Cell>
                ))}
              </Grid>
            </div>
          );
        })}
      </div>
    </Board>
  );
}

const meta: Meta<typeof Mascot> = {
  title: "Mascotte/Mascot",
  component: Mascot,
  parameters: { layout: "fullscreen" },
};
export default meta;

type Story = StoryObj<typeof meta>;

/* -- Croissance : un pet, tous les stades ----------------------------------- */
export const CroissanceLicorne: Story = { render: () => <GrowthBoard species="unicorn" label="🦄 Licorne" /> };
export const CroissanceChat: Story = { render: () => <GrowthBoard species="cat" label="🐱 Chat" /> };
export const CroissanceRenard: Story = { render: () => <GrowthBoard species="fox" label="🦊 Renard" /> };

/* -- Accessoires : chaque accessoire × chaque stade, par pet ---------------- */
export const AccessoiresLicorne: Story = { render: () => <AccessoryBoard species="unicorn" label="🦄 Licorne" /> };
export const AccessoiresChat: Story = { render: () => <AccessoryBoard species="cat" label="🐱 Chat" /> };
export const AccessoiresRenard: Story = { render: () => <AccessoryBoard species="fox" label="🦊 Renard" /> };

/* -- Terrain de jeu : mascotte interactive ---------------------------------- */
const NONE = "(aucun)";
const ALL_ACCESSORIES = [NONE, ...CATALOG.filter((o) => o.category === "accessory").map((o) => o.id)];

interface PlayArgs {
  species: Species;
  stage: number;
  accessory: string;
  mood: Mood;
  size: number;
}

export const Terrain: StoryObj<PlayArgs> = {
  name: "Terrain (interactif)",
  args: { species: "unicorn", stage: 2, accessory: "unicorn.accessory.ribbon", mood: "idle", size: 220 },
  argTypes: {
    species: { control: "inline-radio", options: ["unicorn", "cat", "fox"] },
    stage: { control: { type: "range", min: 0, max: GROWTH_STAGES - 1, step: 1 } },
    mood: { control: "inline-radio", options: ["idle", "happy", "cheer"] },
    accessory: { control: "select", options: ALL_ACCESSORIES },
    size: { control: { type: "range", min: 64, max: 300, step: 4 } },
  },
  render: ({ species, stage, accessory, mood, size }) => (
    <Mascot
      config={cfg(species, stage, accessory && accessory !== NONE ? [accessory] : [])}
      mood={mood}
      size={size}
    />
  ),
};
