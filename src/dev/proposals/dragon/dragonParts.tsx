import type { Layout } from "../../../mascot/growth";
import { INK } from "../../../mascot/growth";
import type { Mood } from "../../../types";

/**
 * Proposal-local dragon part library — shared by the three candidates so the
 * chosen one promotes cleanly. Pure SVG, numeric props, kawaii house style.
 * NOTHING here is imported by app code.
 */

/** Proposal stand-in for MascotConfig ("dragon" is not in the Species union yet). */
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

export const EGG_FILL = "#FFF9EE";
export const EGG_EDGE = "#E3D2BA";

/* -- The stade-0 egg ----------------------------------------------------- */

/** Cracked egg cup, fixed to the stade-0 lying layout: jagged rim rises at the
 * sides and dips in FRONT of the resting face, so the newborn peeks out of the
 * broken shell. Drawn OVER the body/chin (the dragon is IN the egg). */
const CUP_PATH =
  "M24 68 L28 63 L31.5 69 L35.5 64 L39 70 L43 65 " +
  "L45.5 84 L49 88.5 L52.5 84.5 L56 88.5 L59.5 84.5 L63 88.5 L66.5 84.5 L70 88.5 L73.5 84.5 L76.5 88 " +
  "L79 70 L82.5 74.5 L86 68.5 " +
  "C90 80 89 90 78 94.5 C68 98 42 98 32 94 C23.5 90.5 21 78 24 68 Z";

export function EggCup({ uid, speckle, suitColor }: { uid: string; speckle: string; suitColor?: string }) {
  return (
    <g>
      <path d={CUP_PATH} fill={EGG_FILL} stroke={EGG_EDGE} strokeWidth={1.2} strokeLinejoin="round" />
      {suitColor && (
        <g>
          <defs>
            <clipPath id={`${uid}-eggsuit`}>
              <path d={CUP_PATH} />
            </clipPath>
          </defs>
          <g clipPath={`url(#${uid}-eggsuit)`}>
            <rect x={20} y={83} width={70} height={16} fill={suitColor} />
            {[40, 56].map((x) => (
              <path key={x} d={`M${x} 82 q 2.6 6 0 14`} fill="none" stroke="#FFF6EE" strokeWidth={2} opacity={0.9} />
            ))}
          </g>
        </g>
      )}
      {/* crack lines + speckles */}
      <path d="M39 92 L42.5 84.5 L40 78.5" fill="none" stroke={EGG_EDGE} strokeWidth={1} opacity={0.7} />
      <path d="M78 92.5 L75 86.5" fill="none" stroke={EGG_EDGE} strokeWidth={1} opacity={0.6} />
      <g fill={speckle}>
        <circle cx={32} cy={84} r={1.3} />
        <circle cx={71} cy={92} r={1.2} />
        <circle cx={47} cy={93.5} r={1.1} />
        <circle cx={82} cy={81} r={1.1} />
      </g>
    </g>
  );
}

/** Half-shell cap worn on the head (the classic hatchling hat). */
export function ShellCap({ x, y, s, tilt, speckle }: { x: number; y: number; s: number; tilt: number; speckle: string }) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${tilt}) scale(${s})`}>
      <path
        d="M-12 1 C-11 -8 -5 -12.5 0 -12.5 C5 -12.5 11 -8 12 1 L8 5.5 L4 1.5 L0 5.8 L-4 1.5 L-8 5.5 Z"
        fill={EGG_FILL}
        stroke={EGG_EDGE}
        strokeWidth={1.1}
        strokeLinejoin="round"
      />
      <circle cx={3} cy={-6} r={1.2} fill={speckle} />
      <circle cx={-4.5} cy={-3.5} r={0.9} fill={speckle} />
    </g>
  );
}

/** Small shard of shell resting on the back/rump at stade 1. */
export function ShellShard({ x, y, s, tilt, speckle }: { x: number; y: number; s: number; tilt: number; speckle: string }) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${tilt}) scale(${s})`}>
      <path
        d="M0 0 L3 -6 L6.5 -1 L10 -5 L12 1 C8 4 3 4 0 0 Z"
        fill={EGG_FILL}
        stroke={EGG_EDGE}
        strokeWidth={1}
        strokeLinejoin="round"
      />
      <circle cx={6} cy={-1} r={0.9} fill={speckle} />
    </g>
  );
}

/* -- Wings ---------------------------------------------------------------- */

/** Scalloped bat-style dragon wings, drawn BEHIND the body. Outlined + finger
 * ridges so they stay legible against any body colour. */
export function BatWings({ cx, cy, s, membrane, edge }: { cx: number; cy: number; s: number; membrane: string; edge: string }) {
  if (s <= 0) return null;
  const wing = (d: number) => {
    const sx = cx + d * 9;
    const sy = cy;
    const tx = sx + d * 21 * s;
    const ty = sy - 17 * s;
    const p1x = sx + d * 16 * s;
    const p1y = sy - 4 * s;
    const p2x = sx + d * 8.5 * s;
    const p2y = sy - 0.5 * s;
    const path =
      `M${sx} ${sy} C${sx + d * 3 * s} ${sy - 12 * s} ${sx + d * 11 * s} ${sy - 18 * s} ${tx} ${ty} ` +
      `Q${(tx + p1x) / 2 - d * 3 * s} ${(ty + p1y) / 2} ${p1x} ${p1y} ` +
      `Q${(p1x + p2x) / 2 - d * 2 * s} ${(p1y + p2y) / 2 + 1.5 * s} ${p2x} ${p2y} ` +
      `Q${(p2x + sx) / 2} ${sy + 1.5 * s} ${sx} ${sy} Z`;
    return (
      <g key={d}>
        <path d={path} fill={membrane} stroke={edge} strokeWidth={1.1} strokeLinejoin="round" />
        <g stroke={edge} strokeWidth={0.8} opacity={0.5} fill="none">
          <path d={`M${sx + d * 2 * s} ${sy - 2 * s} L${tx - d * 2 * s} ${ty + 2 * s}`} />
          <path d={`M${sx + d * 2 * s} ${sy - s} L${p1x - d * 1.5 * s} ${p1y - s}`} />
        </g>
      </g>
    );
  };
  return (
    <g>
      {wing(-1)}
      {wing(1)}
    </g>
  );
}

/** Puffy cumulus-cloud wings (candidate B). */
export function CloudWings({ cx, cy, s, edge }: { cx: number; cy: number; s: number; edge: string }) {
  if (s <= 0) return null;
  const wing = (d: number) => {
    const sx = cx + d * 10.5;
    const sy = cy;
    const puffs: Array<[number, number, number]> = [
      [sx + d * 5 * s, sy - 3 * s, 6.3 * s],
      [sx + d * 12 * s, sy - 7.5 * s, 5.2 * s],
      [sx + d * 18 * s, sy - 3.5 * s, 4.1 * s],
      [sx + d * 10.5 * s, sy + 1 * s, 4.4 * s],
    ];
    return (
      <g key={d}>
        {puffs.map(([x, y, r], i) => (
          <circle key={i} cx={x} cy={y} r={r} fill="#FFFFFF" stroke={edge} strokeWidth={1} />
        ))}
        {puffs.map(([x, y, r], i) => (
          <circle key={`i${i}`} cx={x} cy={y} r={Math.max(r - 1, 0.5)} fill="#FFFFFF" />
        ))}
      </g>
    );
  };
  return (
    <g>
      {wing(-1)}
      {wing(1)}
    </g>
  );
}

/* -- Tail ----------------------------------------------------------------- */

/** Quadratic tail stroke ending in a spade / lightning-bolt tip that follows
 * the curve's tangent. Optional gem set into the spade (candidate C). */
export function SpadeTail({
  p0,
  p1,
  p2,
  w,
  color,
  edge,
  tip = "spade",
  tipColor,
  tipS = 1,
  gem,
}: {
  p0: [number, number];
  p1: [number, number];
  p2: [number, number];
  w: number;
  color: string;
  /** Outline colour — a same-as-body tail vanishes into the silhouette without it. */
  edge?: string;
  tip?: "spade" | "bolt" | "none";
  tipColor?: string;
  tipS?: number;
  gem?: string;
}) {
  const ang = (Math.atan2(p2[1] - p1[1], p2[0] - p1[0]) * 180) / Math.PI;
  const curve = `M${p0[0]} ${p0[1]} Q${p1[0]} ${p1[1]} ${p2[0]} ${p2[1]}`;
  return (
    <g>
      {edge && <path d={curve} fill="none" stroke={edge} strokeWidth={w + 2.4} strokeLinecap="round" />}
      <path d={curve} fill="none" stroke={color} strokeWidth={w} strokeLinecap="round" />
      {tip === "spade" && (
        <g transform={`translate(${p2[0]} ${p2[1]}) rotate(${ang}) scale(${tipS})`}>
          <path d="M7 0 L-2.5 -5 Q-0.5 0 -2.5 5 Z" fill={tipColor ?? color} stroke={edge} strokeWidth={edge ? 1 : 0} strokeLinejoin="round" />
          {gem && <Gem x={1.4} y={0} s={2.4} color={gem} />}
        </g>
      )}
      {tip === "bolt" && (
        <g transform={`translate(${p2[0]} ${p2[1]}) rotate(${ang + 90}) scale(${tipS})`}>
          <path d="M1 -7 L4.5 -1.5 L2 -1.5 L4.5 4 L-2.5 -1 L0 -1 L-2.5 -7 Z" fill={tipColor ?? "#FFD54F"} stroke="#DFA92E" strokeWidth={0.7} strokeLinejoin="round" />
        </g>
      )}
    </g>
  );
}

/* -- Head gear ------------------------------------------------------------ */

/** Two little horns on the dome. Straight cones, or curled ram horns. */
export function Horns({
  hx,
  hy,
  headR,
  h,
  curly = false,
  color,
  edge,
  tipDot,
}: {
  hx: number;
  hy: number;
  headR: number;
  h: number;
  curly?: boolean;
  color: string;
  edge: string;
  tipDot?: string;
}) {
  if (h <= 0) return null;
  const horn = (d: number) => {
    const bx = hx + d * headR * 0.52;
    const by = hy - headR * 0.72;
    if (curly) {
      const r = Math.max(h * 0.55, headR * 0.17);
      const path =
        `M${bx - d * 1.5} ${by + 2} ` +
        `C${bx + d * r * 0.3} ${by - r * 1.9} ${bx + d * r * 2.3} ${by - r * 1.7} ${bx + d * r * 2.35} ${by - r * 0.3} ` +
        `C${bx + d * r * 2.35} ${by + r * 0.55} ${bx + d * r * 1.55} ${by + r * 0.75} ${bx + d * r * 1.2} ${by + r * 0.3}`;
      return (
        <g key={d} fill="none" strokeLinecap="round">
          <path d={path} stroke={edge} strokeWidth={headR * 0.19 + 1.6} />
          <path d={path} stroke={color} strokeWidth={headR * 0.19} />
        </g>
      );
    }
    const w = headR * 0.13 + h * 0.06;
    const tx = bx + d * h * 0.45;
    const ty = by - h;
    return (
      <g key={d}>
        <path
          d={`M${bx - d * w} ${by + 1.5} Q${bx + d * h * 0.02} ${by - h * 0.6} ${tx} ${ty} Q${bx + d * (w + h * 0.16)} ${by - h * 0.45} ${bx + d * w} ${by + 1.5} Z`}
          fill={color}
          stroke={edge}
          strokeWidth={0.8}
          strokeLinejoin="round"
        />
        {tipDot && <circle cx={tx} cy={ty} r={1.7} fill={tipDot} stroke={edge} strokeWidth={0.5} />}
      </g>
    );
  };
  return (
    <g>
      {horn(-1)}
      {horn(1)}
    </g>
  );
}

/** Row of crest bumps fanned along the head dome, between the horns.
 * `round` swaps pointy triangles for soft bubbles; `gems` tips each spike;
 * `scale` grows the bumps (bone back-plates are big rounded ones). */
export function Crest({
  hx,
  hy,
  headR,
  n,
  round = false,
  color,
  edge,
  gems,
  scale = 1,
}: {
  hx: number;
  hy: number;
  headR: number;
  n: number;
  round?: boolean;
  color: string;
  edge: string;
  gems?: string[];
  scale?: number;
}) {
  if (n <= 0) return null;
  const items = Array.from({ length: n }).map((_, i) => {
    const t = n === 1 ? 0.5 : i / (n - 1);
    const a = ((t - 0.5) * 76 * Math.PI) / 180;
    const rx = Math.sin(a);
    const ry = -Math.cos(a);
    const bx = hx + rx * headR * 0.9;
    const by = hy + ry * headR * 0.88;
    const hgt = headR * (0.36 - 0.08 * Math.abs(t - 0.5) * 2) * scale;
    const px = -ry;
    const py = rx;
    const wHalf = headR * 0.12 * scale;
    if (round) {
      return (
        <g key={i}>
          <circle cx={bx + rx * hgt * 0.42} cy={by + ry * hgt * 0.42} r={headR * 0.14 * scale} fill={color} stroke={edge} strokeWidth={0.8} />
        </g>
      );
    }
    const tx = bx + rx * hgt;
    const ty = by + ry * hgt;
    return (
      <g key={i}>
        <path
          d={`M${bx - px * wHalf} ${by - py * wHalf} L${tx} ${ty} L${bx + px * wHalf} ${by + py * wHalf} Z`}
          fill={color}
          stroke={edge}
          strokeWidth={0.8}
          strokeLinejoin="round"
        />
        {gems && <Gem x={tx} y={ty} s={headR * 0.09 + 0.8} color={gems[i % gems.length]} />}
      </g>
    );
  });
  return <g>{items}</g>;
}

/** Tall wavy dorsal fin at the dome top (candidate B's silhouette hook). */
export function DomeFin({ hx, hy, headR, f, color, edge }: { hx: number; hy: number; headR: number; f: number; color: string; edge: string }) {
  if (f <= 0) return null;
  const y0 = hy - headR * 0.92;
  const h = headR * 0.62 * f;
  const w = headR * 0.3;
  return (
    <path
      d={`M${hx - w * 0.5} ${y0 + 2} C${hx - w * 1.3} ${y0 - h * 0.45} ${hx - w * 0.5} ${y0 - h * 0.85} ${hx} ${y0 - h} C${hx + w * 0.5} ${y0 - h * 0.8} ${hx + w * 1.3} ${y0 - h * 0.35} ${hx + w * 0.5} ${y0 + 2} Z`}
      fill={color}
      stroke={edge}
      strokeWidth={0.9}
      strokeLinejoin="round"
    />
  );
}

/* -- Belly / scales -------------------------------------------------------- */

/** Reptile plate lines across the belly ellipse (chord-width, no clip needed). */
export function BellyPlates({ cx, cy, rx, ry, line }: { cx: number; cy: number; rx: number; ry: number; line: string }) {
  return (
    <g fill="none" stroke={line} strokeWidth={1.3} opacity={0.6} strokeLinecap="round">
      {[-0.25, 0.12, 0.48].map((k) => {
        const halfW = rx * Math.sqrt(1 - k * k) * 0.9;
        return <path key={k} d={`M${cx - halfW} ${cy + ry * k} Q${cx} ${cy + ry * k + 2.6} ${cx + halfW} ${cy + ry * k}`} />;
      })}
    </g>
  );
}

/* -- Fire / storm / treasure bits ----------------------------------------- */

/** Small two-tone kawaii flame (points up at rot=0). */
export function FlamePuff({ x, y, s, rot = 0 }: { x: number; y: number; s: number; rot?: number }) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <path d="M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z" fill="#FF7043" />
      <path d="M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z" fill="#FFE082" />
    </g>
  );
}

/** Little lightning bolt (candidate B's breath + horns). Points up at rot=0. */
export function Bolt({ x, y, s, rot = 0 }: { x: number; y: number; s: number; rot?: number }) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <path d="M1 -8 L5 -2 L2.2 -2 L5 4 L-3 -1 L-0.2 -1 L-3 -8 Z" fill="#FFD54F" stroke="#DFA92E" strokeWidth={0.7} strokeLinejoin="round" />
    </g>
  );
}

/** Floating companion rain-cloud (candidate B, stade 6+). */
export function RainCloud({ x, y, s, drops = false }: { x: number; y: number; s: number; drops?: boolean }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${s})`}>
      <g fill="#FFFFFF" stroke="#C2D4F0" strokeWidth={1}>
        <circle cx={-3.5} cy={0} r={4} />
        <circle cx={1.5} cy={-2.2} r={4.6} />
        <circle cx={5.5} cy={0.6} r={3.4} />
      </g>
      <rect x={-6} y={0} width={13} height={3.4} rx={1.7} fill="#FFFFFF" />
      {drops && (
        <g fill="#7FB8F0">
          <ellipse cx={-2} cy={7} rx={1.1} ry={1.7} />
          <ellipse cx={3.5} cy={8.5} rx={1.1} ry={1.7} />
        </g>
      )}
    </g>
  );
}

/** Faceted kawaii gem (diamond). */
export function Gem({ x, y, s, color }: { x: number; y: number; s: number; color: string }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d={`M0 ${-s} L${s * 0.85} 0 L0 ${s} L${-s * 0.85} 0 Z`} fill={color} stroke="#FFFFFF" strokeWidth={s * 0.22} strokeLinejoin="round" />
      <circle cx={-s * 0.2} cy={-s * 0.25} r={s * 0.18} fill="#FFFFFF" opacity={0.85} />
    </g>
  );
}

/* -- Boy-coded parts (reboot) ---------------------------------------------- */

/** Jagged straight-edged storm wings — angular where BatWings is scalloped. */
export function AngularWings({ cx, cy, s, membrane, edge }: { cx: number; cy: number; s: number; membrane: string; edge: string }) {
  if (s <= 0) return null;
  const wing = (d: number) => {
    const sx = cx + d * 9;
    const sy = cy;
    const pts: Array<[number, number]> = [
      [0, 0],
      [6, -12],
      [22, -18],
      [17.5, -8.5],
      [15.5, -3.5],
      [10.5, -6.5],
      [8, -1],
    ];
    const path = pts.map(([x, y], i) => `${i === 0 ? "M" : "L"}${sx + d * x * s} ${sy + y * s}`).join(" ") + " Z";
    return (
      <g key={d}>
        <path d={path} fill={membrane} stroke={edge} strokeWidth={1.1} strokeLinejoin="round" />
        <g stroke={edge} strokeWidth={0.8} opacity={0.55} fill="none">
          <path d={`M${sx + d * 2 * s} ${sy - 2 * s} L${sx + d * 20 * s} ${sy - 16.5 * s}`} />
          <path d={`M${sx + d * 2 * s} ${sy - s} L${sx + d * 14.5 * s} ${sy - 4.5 * s}`} />
        </g>
      </g>
    );
  };
  return (
    <g>
      {wing(-1)}
      {wing(1)}
    </g>
  );
}

/** Spiky mace-ball tail tip (drawn at p2 of a SpadeTail with tip="none"). */
export function TailClub({ x, y, s, color, edge }: { x: number; y: number; s: number; color: string; edge: string }) {
  const spikes = Array.from({ length: 7 }).map((_, i) => {
    const a = (i / 7) * Math.PI * 2 - Math.PI / 2;
    const bx = x + Math.cos(a) * 3.1 * s;
    const by = y + Math.sin(a) * 3.1 * s;
    const tx = x + Math.cos(a) * 5.4 * s;
    const ty = y + Math.sin(a) * 5.4 * s;
    const px = -Math.sin(a) * 1.15 * s;
    const py = Math.cos(a) * 1.15 * s;
    return <path key={i} d={`M${bx - px} ${by - py} L${tx} ${ty} L${bx + px} ${by + py} Z`} fill={color} stroke={edge} strokeWidth={0.7} strokeLinejoin="round" />;
  });
  return (
    <g>
      {spikes}
      <circle cx={x} cy={y} r={3.4 * s} fill={color} stroke={edge} strokeWidth={0.9} />
      <circle cx={x - s} cy={y - s} r={0.9 * s} fill="#FFFFFF" opacity={0.45} />
    </g>
  );
}

/** Bone casque — a solid frill band hugging the upper dome (triceratops vibe). */
export function FrillBand({ hx, hy, headR, f, color, edge }: { hx: number; hy: number; headR: number; f: number; color: string; edge: string }) {
  if (f <= 0) return null;
  const r = headR * (1.02 + 0.16 * f);
  const a0 = (-118 * Math.PI) / 180;
  const a1 = (-62 * Math.PI) / 180;
  const arc = (rr: number, from: number, to: number) =>
    `M${hx + Math.cos(from) * rr} ${hy + Math.sin(from) * rr} A${rr} ${rr} 0 0 1 ${hx + Math.cos(to) * rr} ${hy + Math.sin(to) * rr}`;
  return (
    <g fill="none" strokeLinecap="round">
      <path d={arc(r, a0 - 0.5, a1 + 0.5)} stroke={edge} strokeWidth={headR * 0.34 * f + 1.6} />
      <path d={arc(r, a0 - 0.5, a1 + 0.5)} stroke={color} strokeWidth={headR * 0.34 * f} />
    </g>
  );
}

/** Glowing lava/charge cracks — short zigzags on the body flanks. */
export function Cracks({ cx, cy, rx, ry, color }: { cx: number; cy: number; rx: number; ry: number; color: string }) {
  const zig = (x: number, y: number, d: number) =>
    `M${x} ${y} l${2.4 * d} -2.6 l${2.4 * d} 2.6 l${2.4 * d} -2.6`;
  return (
    <g fill="none" stroke={color} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
      <path d={zig(cx - rx * 0.88, cy - ry * 0.25, 1)} />
      <path d={zig(cx + rx * 0.35, cy - ry * 0.55, 1)} />
      <path d={zig(cx - rx * 0.5, cy + ry * 0.42, 1)} opacity={0.85} />
    </g>
  );
}

/** Two little smoke curls drifting OUTWARD from the nostrils — mostly sideways
 * so they clear the eyes (rising straight up read as tears next to them). */
export function SmokePuffs({ hx, hy, headR }: { hx: number; hy: number; headR: number }) {
  const puff = (d: number) => {
    const x = hx + d * headR * 0.24;
    const y = hy + headR * 0.3;
    return (
      <g key={d} fill="#C3C7CE" opacity={0.85}>
        <circle cx={x + d * headR * 0.28} cy={y - 1} r={1.4} />
        <circle cx={x + d * headR * 0.52} cy={y - 3} r={1.9} />
        <circle cx={x + d * headR * 0.82} cy={y - 5.6} r={2.5} opacity={0.8} />
      </g>
    );
  };
  return (
    <g>
      {puff(-1)}
      {puff(1)}
    </g>
  );
}

/** White claw nicks on a foot (drawn over the dark hoof). */
export function Claws({ x, y, w }: { x: number; y: number; w: number }) {
  return (
    <g fill="#FFFFFF" opacity={0.95}>
      {[-0.3, 0.12, 0.54].map((f) => (
        <path key={f} d={`M${x + f * w} ${y - 1} l1.1 2.6 l1.1 -2.6 Z`} />
      ))}
    </g>
  );
}

/** Two tiny fangs peeking from the mouth corners. */
export function Fangs({ cx, y, w }: { cx: number; y: number; w: number }) {
  return (
    <g fill="#FFFFFF" stroke={INK} strokeWidth={0.35}>
      <path d={`M${cx - w} ${y - 0.6} l0.9 3 l1.5 -2.4 Z`} />
      <path d={`M${cx + w} ${y - 0.6} l-0.9 3 l-1.5 -2.4 Z`} />
    </g>
  );
}

/** Little dust clouds kicked up at the feet (the tank's stomp). */
export function DustPuffs({ cx, y, spread }: { cx: number; y: number; spread: number }) {
  const side = (d: number) => (
    <g key={d} fill="#D6CDBB" opacity={0.75}>
      <circle cx={cx + d * spread} cy={y - 1.5} r={2.6} />
      <circle cx={cx + d * (spread + 4)} cy={y - 0.5} r={1.8} />
      <circle cx={cx + d * (spread - 3)} cy={y + 0.5} r={1.5} />
    </g>
  );
  return (
    <g>
      {side(-1)}
      {side(1)}
    </g>
  );
}

/* -- Accessories ----------------------------------------------------------- */

/** Aviator goggles resting on the upper dome (never over the eyes). */
export function Goggles({ x, y, headR }: { x: number; y: number; headR: number }) {
  const ly = y + headR * 0.22;
  const r = headR * 0.21;
  const dx = headR * 0.3;
  return (
    <g>
      <path d={`M${x - headR * 0.72} ${ly + r * 0.3} Q${x} ${y - headR * 0.14} ${x + headR * 0.72} ${ly + r * 0.3}`} fill="none" stroke="#8D5A3B" strokeWidth={2.2} />
      <circle cx={x - dx} cy={ly} r={r} fill="#CDEBF7" stroke="#C98A5B" strokeWidth={1.8} />
      <circle cx={x + dx} cy={ly} r={r} fill="#CDEBF7" stroke="#C98A5B" strokeWidth={1.8} />
      <path d={`M${x - dx + r} ${ly} Q${x} ${ly - r * 0.5} ${x + dx - r} ${ly}`} fill="none" stroke="#C98A5B" strokeWidth={1.5} />
      <circle cx={x - dx - r * 0.3} cy={ly - r * 0.35} r={r * 0.28} fill="#FFFFFF" opacity={0.8} />
      <circle cx={x + dx - r * 0.3} cy={ly - r * 0.35} r={r * 0.28} fill="#FFFFFF" opacity={0.8} />
    </g>
  );
}

/** Golden chest armour (premium): breastplate + rivets; shoulder pads from
 * stade 6, a red gem from stade 7 — its own little growth story. */
export function ChestArmor({
  cx,
  bodyCY,
  bodyRX,
  bodyRY,
  pads,
  gem,
}: {
  cx: number;
  bodyCY: number;
  bodyRX: number;
  bodyRY: number;
  pads: boolean;
  gem: boolean;
}) {
  const y0 = bodyCY - bodyRY * 0.32;
  const h = bodyRY * 0.82;
  const w1 = bodyRX * 0.52;
  const w2 = bodyRX * 0.36;
  return (
    <g>
      <path
        d={`M${cx - w1} ${y0} Q${cx} ${y0 - 3} ${cx + w1} ${y0} L${cx + w2} ${y0 + h} Q${cx} ${y0 + h + 3.2} ${cx - w2} ${y0 + h} Z`}
        fill="#FFD54F"
        stroke="#B07E1E"
        strokeWidth={1.2}
        strokeLinejoin="round"
      />
      <path d={`M${cx - w1 * 0.62} ${y0 + h * 0.32} Q${cx} ${y0 + h * 0.16} ${cx + w1 * 0.62} ${y0 + h * 0.32}`} fill="none" stroke="#B07E1E" strokeWidth={0.9} opacity={0.65} />
      {[-0.62, 0, 0.62].map((f) => (
        <circle key={f} cx={cx + f * w1 * 0.8} cy={y0 + 2.2} r={1} fill="#B07E1E" opacity={0.8} />
      ))}
      {pads && (
        <g>
          <circle cx={cx - w1 - 1} cy={y0 + 1.5} r={3.2} fill="#FFD54F" stroke="#B07E1E" strokeWidth={1} />
          <circle cx={cx + w1 + 1} cy={y0 + 1.5} r={3.2} fill="#FFD54F" stroke="#B07E1E" strokeWidth={1} />
        </g>
      )}
      {gem && <Gem x={cx} y={y0 + h * 0.55} s={3} color="#E0533B" />}
    </g>
  );
}

/** Tiny golden shield sticker — the armour's stade-0 form, worn BY the egg. */
export function EggShield({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path
        d="M0 -4.4 C3 -4.4 4.4 -3.2 4.4 -1 C4.4 2 2.2 4.4 0 5.6 C-2.2 4.4 -4.4 2 -4.4 -1 C-4.4 -3.2 -3 -4.4 0 -4.4 Z"
        fill="#FFD54F"
        stroke="#B07E1E"
        strokeWidth={1}
        strokeLinejoin="round"
      />
      <path d="M0 -2.4 L0.75 -0.9 L2.4 -0.7 L1.2 0.45 L1.5 2.1 L0 1.3 L-1.5 2.1 L-1.2 0.45 L-2.4 -0.7 L-0.75 -0.9 Z" fill="#FFFDF4" />
    </g>
  );
}

/** Dragon snout: wide muzzle + nostrils (the nose dots make it a dragon, not a cat). */
export function Snout({ hx, hy, headR, color }: { hx: number; hy: number; headR: number; color: string }) {
  return (
    <g>
      <ellipse cx={hx} cy={hy + headR * 0.45} rx={headR * 0.46} ry={headR * 0.32} fill={color} />
      <g fill={INK} opacity={0.75}>
        <ellipse cx={hx - headR * 0.13} cy={hy + headR * 0.32} rx={1.15} ry={1.5} />
        <ellipse cx={hx + headR * 0.13} cy={hy + headR * 0.32} rx={1.15} ry={1.5} />
      </g>
    </g>
  );
}
