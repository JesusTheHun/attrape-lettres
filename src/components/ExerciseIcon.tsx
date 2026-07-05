import type { ReactNode } from "react";
import type { ExerciseId } from "../types";

/**
 * The hub icon for each exercise: a tinted rounded badge + an in-house white
 * pictogram (no emoji). Every glyph is drawn to say what the game IS — the
 * letter games earn real letterforms, the rest earn simple silhouettes.
 *
 * Keyed by `ExerciseId` on purpose: a NEW exercise won't type-check until it has
 * an icon here, so the catalog and the icon set can never drift. See CLAUDE.md
 * "Add an exercise". Decorative — the hub already labels every game by name, so
 * the <svg> is aria-hidden.
 */

const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

/** Shared white-stroke style for pictogram lines. */
const line = {
  fill: "none",
  stroke: "#fff",
  strokeWidth: 2.4,
  strokeLinecap: "round",
  strokeLinejoin: "round",
} as const;

/** A crisp white letter in the rounded UI font — for the letter-shape games. */
function Glyph({
  x,
  y,
  size,
  children,
}: {
  x: number;
  y: number;
  size: number;
  children: string;
}) {
  return (
    <text
      x={x}
      y={y}
      textAnchor="middle"
      dominantBaseline="central"
      fontFamily={ROUNDED}
      fontWeight={900}
      fontSize={size}
      fill="#fff"
    >
      {children}
    </text>
  );
}

/**
 * The "écritures mêlées" corner chip — a white sub-badge with a shuffle mark.
 * Both mixed twins wear it, so a child reads them as the same game "mixed up".
 */
function ShuffleChip({ tint }: { tint: string }) {
  return (
    <g>
      <circle cx={23.5} cy={23.5} r={6.6} fill="#fff" />
      <g stroke={tint} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" fill="none">
        <path d="M20.5 26 L26 21" />
        <path d="M20.5 21 L26 26" />
        <path d="M23.8 21 L26 21 L26 23.2" />
        <path d="M23.8 26 L26 26 L26 23.8" />
      </g>
    </g>
  );
}

const GLYPHS: Record<ExerciseId, { tint: string; glyph: ReactNode }> = {
  // First letter — a big A leading the word, with a little sparkle on it.
  "first-letter": {
    tint: "#FF8A5B",
    glyph: (
      <>
        <Glyph x={15} y={17.5} size={17}>
          A
        </Glyph>
        <path d="M24 5.5 L24.98 7.02 L27.5 9 L24.98 10.98 L24 12.5 L23.02 10.98 L20.5 9 L23.02 7.02 Z" fill="#fff" />
      </>
    ),
  },
  // Complete the word — three slots, the middle piece missing (dashed).
  "fill-blank": {
    tint: "#7C6FF0",
    glyph: (
      <>
        <rect x={6} y={12} width={6} height={8} rx={1.7} fill="#fff" />
        <rect x={13} y={12} width={6} height={8} rx={1.7} fill="none" stroke="#fff" strokeWidth={1.8} strokeDasharray="2.4 2.2" />
        <rect x={20} y={12} width={6} height={8} rx={1.7} fill="#fff" />
      </>
    ),
  },
  // Order the syllables — bars beside up/down reorder arrows.
  "order-syllables": {
    tint: "#2EC4B6",
    glyph: (
      <>
        <rect x={13} y={10} width={13} height={5} rx={2.5} fill="#fff" />
        <rect x={13} y={17} width={13} height={5} rx={2.5} fill="#fff" opacity={0.72} />
        <path d="M8 10.5 V21.5" {...line} />
        <path d="M5.8 12.4 L8 10 L10.2 12.4" {...line} />
        <path d="M5.8 19.6 L8 22 L10.2 19.6" {...line} />
      </>
    ),
  },
  // Find the intruder — a magnifier catching the odd one (a small x).
  "find-intruder": {
    tint: "#4D9DE0",
    glyph: (
      <>
        <circle cx={14} cy={14} r={6} {...line} />
        <path d="M18.4 18.4 L23.5 23.5" {...line} strokeWidth={2.8} />
        <path d="M12 12 L16 16 M16 12 L12 16" stroke="#fff" strokeWidth={1.8} strokeLinecap="round" fill="none" />
      </>
    ),
  },
  // Make the sound — a speaker with two sound waves.
  "spell-sound": {
    tint: "#F4A62A",
    glyph: (
      <>
        <path d="M8 13 H11 L15 9 V23 L11 19 H8 Z" fill="#fff" />
        <path d="M18 12 a6 6 0 0 1 0 8" {...line} strokeWidth={2.2} />
        <path d="M20.6 9 a10 10 0 0 1 0 14" {...line} strokeWidth={2.2} opacity={0.7} />
      </>
    ),
  },
  // Write the syllable — a pencil over a writing line.
  "spell-syllable": {
    tint: "#EF5D8F",
    glyph: (
      <>
        <path d="M21 9 l2 2 -9 9 -3 1 1 -3 z" fill="#fff" />
        <path d="M9 24 H23" {...line} strokeWidth={2} />
      </>
    ),
  },
  // The syllable + intruders — aim for the right one (a bullseye).
  "spell-syllable-plus": {
    tint: "#E4572E",
    glyph: (
      <>
        <circle cx={16} cy={16} r={8} {...line} />
        <circle cx={16} cy={16} r={4} {...line} />
        <circle cx={16} cy={16} r={1.7} fill="#fff" />
      </>
    ),
  },
  // Write two syllables — two writing lines + a pencil.
  "spell-two-syllables": {
    tint: "#3BA55D",
    glyph: (
      <>
        <path d="M7 15 H17" {...line} strokeWidth={2.6} />
        <path d="M7 21 H16" {...line} strokeWidth={2.6} opacity={0.8} />
        <path d="M21 8 l3 3 -8 8 -4 1 1 -4 z" fill="#fff" />
      </>
    ),
  },
  // Read the word — a framed picture (sun + hills).
  "read-image": {
    tint: "#6C8BE0",
    glyph: (
      <>
        <rect x={6} y={8} width={20} height={16} rx={3} {...line} />
        <circle cx={11.5} cy={13} r={1.9} fill="#fff" />
        <path d="M7 22 L12 16 L15 19 L18 15 L25 22" {...line} strokeWidth={2.2} />
      </>
    ),
  },
  // Upper- and lowercase — a big A and a little a.
  "match-case": {
    tint: "#F2994A",
    glyph: (
      <>
        <Glyph x={12} y={18} size={16}>
          A
        </Glyph>
        <Glyph x={22} y={19} size={11}>
          a
        </Glyph>
      </>
    ),
  },
  // Cursive letters — one flowing looped stroke.
  "match-script": {
    tint: "#B06AB3",
    glyph: <path d="M8 20 C 8 12, 13 11, 12.5 16 C 12 20, 15 21, 19 18 C 21 16.5, 22 15, 22.5 13" {...line} />,
  },
  // The syllable + intruders, mixed writings — the bullseye, shuffled.
  "spell-syllable-plus-mixed": {
    tint: "#B23A2A",
    glyph: (
      <>
        <circle cx={14} cy={14} r={6.5} {...line} />
        <circle cx={14} cy={14} r={3} {...line} />
        <circle cx={14} cy={14} r={1.5} fill="#fff" />
        <ShuffleChip tint="#B23A2A" />
      </>
    ),
  },
  // Two syllables, mixed writings — the two lines + pencil, shuffled.
  "spell-two-syllables-mixed": {
    tint: "#2E7D5B",
    glyph: (
      <>
        <path d="M6 13 H15" {...line} strokeWidth={2.4} />
        <path d="M6 19 H14" {...line} strokeWidth={2.4} opacity={0.8} />
        <path d="M18 8 l3 3 -7 7 -4 1 1 -4 z" fill="#fff" />
        <ShuffleChip tint="#2E7D5B" />
      </>
    ),
  },
};

export function ExerciseIcon({ id, size = 30 }: { id: ExerciseId; size?: number }) {
  const { tint, glyph } = GLYPHS[id];
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden style={{ display: "block" }}>
      <rect x={2} y={2} width={28} height={28} rx={8.5} fill={tint} />
      {glyph}
    </svg>
  );
}
