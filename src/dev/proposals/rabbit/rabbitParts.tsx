import type { Layout } from "../../../mascot/growth";
import { INK } from "../../../mascot/growth";
import type { Mood } from "../../../types";
import { fourStar } from "../../../mascot/parts";

/**
 * Proposal-local rabbit part library — shared by the three candidates so the
 * chosen one promotes cleanly. Pure SVG, numeric props, kawaii house style.
 * NOTHING here is imported by app code.
 */

/** Proposal stand-in for MascotConfig ("rabbit" is not in the Species union yet). */
export interface PConfig {
  colors: Record<string, string>;
  styles: Record<string, string>;
  accessories: string[];
}

export interface PRigProps {
  config: PConfig;
  layout: Layout;
  stage: number;
  mood?: Mood;
  uid: string;
  preview?: boolean;
}

/* -- Ears ---------------------------------------------------------------- */

/**
 * One rabbit ear grown from a base point. `rot` is degrees from vertical:
 * 0 = straight up, ±10 = the classic upright tilt, ±150 = a lop ear hanging
 * down beside the face, negative values sweep back for the lying baby.
 */
export function Ear({
  bx,
  by,
  len,
  wid,
  rot,
  outer,
  inner,
  dip,
  star,
}: {
  bx: number;
  by: number;
  len: number;
  wid: number;
  rot: number;
  outer: string;
  /** inner-ear colour; omit to draw a plain ear. */
  inner?: string;
  /** colour-dipped tip (moonlit gold, sky blue…). */
  dip?: string;
  /** a small four-star riding just past the tip. */
  star?: string;
}) {
  const rad = (rot * Math.PI) / 180;
  const ux = Math.sin(rad);
  const uy = -Math.cos(rad);
  const at = (t: number): [number, number] => [bx + ux * len * t, by + uy * len * t];
  const [cx, cy] = at(0.5);
  const [icx, icy] = at(0.58);
  const [dcx, dcy] = at(0.86);
  const [sx, sy] = at(1.14);
  return (
    <g>
      <ellipse cx={cx} cy={cy} rx={wid * 0.5} ry={len * 0.52} fill={outer} transform={`rotate(${rot} ${cx} ${cy})`} />
      {inner && (
        <ellipse cx={icx} cy={icy} rx={wid * 0.27} ry={len * 0.33} fill={inner} transform={`rotate(${rot} ${icx} ${icy})`} />
      )}
      {dip && (
        <ellipse cx={dcx} cy={dcy} rx={wid * 0.36} ry={len * 0.16} fill={dip} transform={`rotate(${rot} ${dcx} ${dcy})`} />
      )}
      {star && <path d={fourStar(sx, sy, 2.3)} fill={star} />}
    </g>
  );
}

/** An ear whose top third folds over — the bent-ear kawaii signature. */
export function KinkEar({
  bx,
  by,
  len,
  wid,
  rot,
  kink,
  outer,
  inner,
  dip,
}: {
  bx: number;
  by: number;
  len: number;
  wid: number;
  rot: number;
  /** extra degrees the tip folds beyond `rot` (positive folds outward). */
  kink: number;
  outer: string;
  inner?: string;
  dip?: string;
}) {
  const rad = (rot * Math.PI) / 180;
  const ux = Math.sin(rad);
  const uy = -Math.cos(rad);
  const ex = bx + ux * len * 0.6;
  const ey = by + uy * len * 0.6;
  const rot2 = rot + kink;
  const rad2 = (rot2 * Math.PI) / 180;
  const ux2 = Math.sin(rad2);
  const uy2 = -Math.cos(rad2);
  const tl = len * 0.46;
  const c1x = bx + ux * len * 0.34;
  const c1y = by + uy * len * 0.34;
  const c2x = ex + ux2 * tl * 0.45;
  const c2y = ey + uy2 * tl * 0.45;
  const icx = bx + ux * len * 0.38;
  const icy = by + uy * len * 0.38;
  const dcx = ex + ux2 * tl * 0.78;
  const dcy = ey + uy2 * tl * 0.78;
  return (
    <g>
      <ellipse cx={c1x} cy={c1y} rx={wid * 0.5} ry={len * 0.42} fill={outer} transform={`rotate(${rot} ${c1x} ${c1y})`} />
      {inner && (
        <ellipse cx={icx} cy={icy} rx={wid * 0.26} ry={len * 0.26} fill={inner} transform={`rotate(${rot} ${icx} ${icy})`} />
      )}
      <ellipse cx={c2x} cy={c2y} rx={wid * 0.44} ry={tl * 0.55} fill={outer} transform={`rotate(${rot2} ${c2x} ${c2y})`} />
      {dip && (
        <ellipse cx={dcx} cy={dcy} rx={wid * 0.34} ry={tl * 0.24} fill={dip} transform={`rotate(${rot2} ${dcx} ${dcy})`} />
      )}
    </g>
  );
}

/* -- Tail ---------------------------------------------------------------- */

/** Round fluffy pompon tail — a clump of circles with an outline underlay so
 * it separates from a same-tone body. */
export function Pompon({ cx, cy, s, color, edge }: { cx: number; cy: number; s: number; color: string; edge: string }) {
  const puffs: Array<[number, number, number]> = [
    [0, 0, 4.4],
    [-3.2, -1.5, 3.1],
    [3, -1.7, 2.9],
    [-2.4, 2.3, 2.9],
    [2.7, 2.1, 2.8],
    [0, -3.2, 3],
  ];
  return (
    <g>
      {puffs.map(([dx, dy, r], i) => (
        <circle key={`e${i}`} cx={cx + dx * s} cy={cy + dy * s} r={(r + 0.9) * s} fill={edge} />
      ))}
      {puffs.map(([dx, dy, r], i) => (
        <circle key={i} cx={cx + dx * s} cy={cy + dy * s} r={r * s} fill={color} />
      ))}
    </g>
  );
}

/* -- Face bits ----------------------------------------------------------- */

/** Tiny rounded-triangle rabbit nose. */
export function BunnyNose({ cx, y, s, color = "#F0A0AE" }: { cx: number; y: number; s: number; color?: string }) {
  return (
    <path
      d={`M${cx - 1.9 * s} ${y} Q${cx} ${y - 1.7 * s} ${cx + 1.9 * s} ${y} Q${cx} ${y + 2.4 * s} ${cx - 1.9 * s} ${y} Z`}
      fill={color}
    />
  );
}

/** Three whisker-freckle dots per cheek — the shared rabbit face signature. */
export function WhiskerDots({ cx, y, dx, s }: { cx: number; y: number; dx: number; s: number }) {
  return (
    <g fill={INK} opacity={0.3}>
      {([-1, 1] as const).flatMap((d) =>
        [0, 1, 2].map((i) => (
          <circle key={`${d}-${i}`} cx={cx + d * (dx + (i % 2) * 1.6 * s)} cy={y + (i - 1) * 1.7 * s} r={0.58 * s} />
        ))
      )}
    </g>
  );
}

/* -- Candidate A (moon) bits --------------------------------------------- */

/** Crescent moon, tips up/down, opening to the left. */
export function Crescent({
  cx,
  cy,
  r,
  fill,
  rot = 0,
  opacity = 1,
}: {
  cx: number;
  cy: number;
  r: number;
  fill: string;
  rot?: number;
  opacity?: number;
}) {
  const d = `M${cx} ${cy - r} A${r} ${r} 0 1 1 ${cx} ${cy + r} A${r * 1.15} ${r * 1.15} 0 0 0 ${cx} ${cy - r} Z`;
  return <path d={d} fill={fill} opacity={opacity} transform={rot ? `rotate(${rot} ${cx} ${cy})` : undefined} />;
}

/* -- Candidate B (garden) bits ------------------------------------------- */

/** Little lucky clover on a stem (stem drawn first, pointing down). */
export function Clover({ x, y, s, leaf, stem }: { x: number; y: number; s: number; leaf: string; stem: string }) {
  const leaves: Array<[number, number]> = [
    [0, -1.9],
    [-1.75, 1.1],
    [1.75, 1.1],
  ];
  return (
    <g>
      <path
        d={`M${x} ${y + 1.2 * s} q ${1.3 * s} ${2.4 * s} ${0.5 * s} ${4 * s}`}
        stroke={stem}
        strokeWidth={1.1 * s}
        fill="none"
        strokeLinecap="round"
      />
      {leaves.map(([dx, dy], i) => (
        <circle key={i} cx={x + dx * s} cy={y + dy * s} r={1.8 * s} fill={leaf} />
      ))}
    </g>
  );
}

/** Small side-view butterfly — two wings + a dark body. */
export function Butterfly({ x, y, s, wing }: { x: number; y: number; s: number; wing: string }) {
  const lx = x - 1.7 * s;
  const rx = x + 1.7 * s;
  const wy = y - 0.4 * s;
  return (
    <g>
      <ellipse cx={lx} cy={wy} rx={1.9 * s} ry={1.2 * s} fill={wing} transform={`rotate(-30 ${lx} ${wy})`} />
      <ellipse cx={rx} cy={wy} rx={1.9 * s} ry={1.2 * s} fill={wing} transform={`rotate(30 ${rx} ${wy})`} />
      <ellipse cx={x} cy={y} rx={0.55 * s} ry={1.5 * s} fill={INK} opacity={0.7} />
    </g>
  );
}

/** A celebratory ring of petals around a centre (B's stade-9 burst). */
export function PetalRing({ cx, cy, rx, ry, n, color }: { cx: number; cy: number; rx: number; ry: number; n: number; color: string }) {
  return (
    <g fill={color} opacity={0.92}>
      {Array.from({ length: n }).map((_, i) => {
        const a = (i / n) * Math.PI * 2;
        const px = cx + Math.cos(a) * rx;
        const py = cy + Math.sin(a) * ry;
        const deg = (a * 180) / Math.PI + 90;
        return <ellipse key={i} cx={px} cy={py} rx={1.5} ry={2.8} transform={`rotate(${deg} ${px} ${py})`} />;
      })}
    </g>
  );
}

/** A handful of drifting petals at fixed, deterministic offsets. */
export function FallingPetals({ cx, cy, color }: { cx: number; cy: number; color: string }) {
  const petals: Array<[number, number, number]> = [
    [-30, -14, 24],
    [-21, 9, -38],
    [26, -11, 42],
    [32, 7, -16],
    [7, -26, 70],
  ];
  return (
    <g fill={color} opacity={0.9}>
      {petals.map(([dx, dy, deg], i) => (
        <ellipse key={i} cx={cx + dx} cy={cy + dy} rx={1.4} ry={2.6} transform={`rotate(${deg} ${cx + dx} ${cy + dy})`} />
      ))}
    </g>
  );
}

/* -- Candidate C (sky) bits ---------------------------------------------- */

/** Puffy three-lobe cloud with an outline underlay. */
export function CloudPuff({
  cx,
  cy,
  s,
  color = "#FFFFFF",
  edge = "#D7E7F4",
}: {
  cx: number;
  cy: number;
  s: number;
  color?: string;
  edge?: string;
}) {
  const puffs: Array<[number, number, number]> = [
    [-4.2, 0.4, 3],
    [0, -1.6, 4.1],
    [4.2, 0.4, 3],
  ];
  return (
    <g>
      {puffs.map(([dx, dy, r], i) => (
        <circle key={`e${i}`} cx={cx + dx * s} cy={cy + dy * s} r={(r + 0.8) * s} fill={edge} />
      ))}
      {puffs.map(([dx, dy, r], i) => (
        <circle key={i} cx={cx + dx * s} cy={cy + dy * s} r={r * s} fill={color} />
      ))}
    </g>
  );
}

/** A curling breeze stroke. */
export function Swirl({ x, y, s, color }: { x: number; y: number; s: number; color: string }) {
  return (
    <path
      d={`M${x} ${y} q ${6 * s} ${-3.5 * s} ${11 * s} 0 q ${2.5 * s} ${2 * s} ${-0.5 * s} ${3.2 * s} q ${-2.6 * s} ${1 * s} ${-3.4 * s} ${-1.2 * s}`}
      fill="none"
      stroke={color}
      strokeWidth={1.6}
      strokeLinecap="round"
      opacity={0.85}
    />
  );
}
