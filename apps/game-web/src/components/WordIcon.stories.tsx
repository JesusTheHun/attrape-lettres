import type { CSSProperties, ReactNode } from "react";
import type { Meta, StoryObj } from "@storybook/react";
import { WordIcon } from "./WordIcon";
import { LETTER_WORDS, SYLLABLE_WORDS } from "../content";

/**
 * Visual harness for the DEDICATED illustrations — the hand-made SVGs that stand
 * in for a wrong emoji (a cupcake for « macaron », a hut for « igloo », a person
 * in bed for « pyjama », a dress for « jupe »). A story here IS the acceptance
 * spec: each image must (1) unmistakably read as its word, (2) beat the emoji it
 * replaces, and (3) stay legible on every surface it appears on in game — the
 * cream stage, a white slot, and the five coloured pick-tiles (the white igloo
 * is the one to watch: it must not vanish on a light background).
 *
 * The list is derived from any word carrying `img`, so a new dedicated image
 * shows up here automatically — no edit to this file.
 */

const INK = "#5A3A1E";
const FONT = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

// The five pick-tile colours the exercises actually use (Assemble / ReadImage).
const TILE_COLORS = ["#FF8A65", "#FFD54F", "#4FC3F7", "#AED581", "#BA9EE8"];
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";

/** Every word that carries a dedicated illustration, both datasets. */
const DEDICATED = [...LETTER_WORDS, ...SYLLABLE_WORDS].filter((w) => w.img);

function Board({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={{ fontFamily: FONT, color: INK, padding: 12, maxWidth: 1100 }}>
      <h2 style={{ margin: "0 0 12px", fontSize: 20, fontWeight: 900 }}>{title}</h2>
      {children}
    </div>
  );
}

function Grid({ children, min = 150 }: { children: ReactNode; min?: number }) {
  return (
    <div
      style={{
        display: "grid",
        gap: 12,
        gridTemplateColumns: `repeat(auto-fill, minmax(${min}px, 1fr))`,
      }}
    >
      {children}
    </div>
  );
}

/** A soft card with a caption underneath, matching the Mascot story cells. */
function Cell({
  children,
  caption,
  sub,
  bg = "rgba(255,255,255,0.72)",
}: {
  children: ReactNode;
  caption: string;
  sub?: string;
  bg?: string;
}) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 4,
        padding: 12,
        borderRadius: 16,
        background: bg,
        boxShadow: "0 1px 4px rgba(0,0,0,0.08)",
      }}
    >
      {children}
      <span style={{ fontSize: 14, fontWeight: 800 }}>{caption}</span>
      {sub && <span style={{ fontSize: 11, fontWeight: 700, color: "#9A7A5A" }}>{sub}</span>}
    </div>
  );
}

const meta: Meta<typeof WordIcon> = {
  title: "Contenu/Images dédiées",
  component: WordIcon,
  parameters: { layout: "fullscreen" },
};
export default meta;

type Story = StoryObj<typeof meta>;

/* -- Avant / après : l'emoji faux vs. l'image dédiée ------------------------- */
export const AvantApres: Story = {
  name: "Avant / après",
  render: () => (
    <Board title="Emoji trompeur → image dédiée">
      <Grid min={220}>
        {DEDICATED.map((w) => (
          <Cell key={w.word} caption={w.word}>
            <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
              <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
                <span aria-hidden style={{ fontSize: 72, lineHeight: 1.1, opacity: 0.85 }}>
                  {w.emoji}
                </span>
                <span style={{ fontSize: 11, fontWeight: 700, color: "#C0392B" }}>emoji ✗</span>
              </div>
              <span aria-hidden style={{ fontSize: 28, color: "#9A7A5A" }}>
                →
              </span>
              <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
                <WordIcon emoji={w.emoji} img={w.img} size={80} />
                <span style={{ fontSize: 11, fontWeight: 700, color: "#2E7D32" }}>image ✓</span>
              </div>
            </div>
          </Cell>
        ))}
      </Grid>
    </Board>
  ),
};

/* -- Sur fonds : lisibilité sur toutes les surfaces du jeu ------------------- */
export const SurFonds: Story = {
  name: "Sur fonds (lisibilité)",
  render: () => (
    <Board title="Chaque image sur le stade, un slot blanc, et les 5 tuiles de jeu">
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        {DEDICATED.map((w) => (
          <div key={w.word}>
            <h3 style={{ margin: "0 0 6px", fontSize: 15, fontWeight: 800 }}>{w.word}</h3>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
              <Surface label="stade" style={{ background: STAGE }}>
                <WordIcon emoji={w.emoji} img={w.img} size={88} />
              </Surface>
              <Surface label="slot" style={{ background: "#FFFFFF" }}>
                <WordIcon emoji={w.emoji} img={w.img} size={88} />
              </Surface>
              {TILE_COLORS.map((bg) => (
                <Surface key={bg} label="tuile" style={{ background: bg }}>
                  <WordIcon emoji={w.emoji} img={w.img} size={88} />
                </Surface>
              ))}
            </div>
          </div>
        ))}
      </div>
    </Board>
  ),
};

function Surface({
  children,
  label,
  style,
}: {
  children: ReactNode;
  label: string;
  style: CSSProperties;
}) {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 3 }}>
      <div
        style={{
          width: 120,
          height: 120,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          borderRadius: 24,
          boxShadow: "0 4px 10px rgba(0,0,0,0.10)",
          ...style,
        }}
      >
        {children}
      </div>
      <span style={{ fontSize: 11, fontWeight: 700, color: "#9A7A5A" }}>{label}</span>
    </div>
  );
}

/* -- Tailles : du petit hub au grand plateau -------------------------------- */
const SIZES = [40, 56, 72, 96, 120, 150];
export const Tailles: Story = {
  name: "Tailles",
  render: () => (
    <Board title="Silhouette lisible du plus petit (40px) au plus grand (150px)">
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        {DEDICATED.map((w) => (
          <div key={w.word}>
            <h3 style={{ margin: "0 0 6px", fontSize: 15, fontWeight: 800 }}>{w.word}</h3>
            <div style={{ display: "flex", alignItems: "flex-end", gap: 16, flexWrap: "wrap" }}>
              {SIZES.map((s) => (
                <div key={s} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 3 }}>
                  <WordIcon emoji={w.emoji} img={w.img} size={s} />
                  <span style={{ fontSize: 11, fontWeight: 700, color: "#9A7A5A" }}>{s}px</span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </Board>
  ),
};

/* -- Terrain : une image dédiée, taille réglable ---------------------------- */
interface PlayArgs {
  word: string;
  size: number;
}

export const Terrain: StoryObj<PlayArgs> = {
  name: "Terrain (interactif)",
  args: { word: DEDICATED[0]?.word ?? "", size: 150 },
  argTypes: {
    word: { control: "inline-radio", options: DEDICATED.map((w) => w.word) },
    size: { control: { type: "range", min: 32, max: 260, step: 4 } },
  },
  render: ({ word, size }) => {
    const w = DEDICATED.find((d) => d.word === word) ?? DEDICATED[0];
    if (!w) return <span>—</span>;
    return <WordIcon emoji={w.emoji} img={w.img} size={size} />;
  },
};
