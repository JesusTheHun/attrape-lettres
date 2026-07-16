import type { CSSProperties } from "react";
import type { Mood } from "../types";
import type { LegSpec } from "./growth";

/**
 * Generic flat-kawaii part library. Species files compose these + their own
 * species-specific parts. Everything is pure SVG driven by numeric props.
 *
 * Owned by AGENT A.
 */

const DARK = "#4A3222";
const BLUSH = "#FF9AA2";

/** A crisp 4-point sparkle star centred at (cx,cy). */
export function fourStar(cx: number, cy: number, r: number): string {
  const i = r * 0.36;
  return `M${cx} ${cy - r} L${cx + i} ${cy - i} L${cx + r} ${cy} L${cx + i} ${cy + i} L${cx} ${cy + r} L${cx - i} ${cy + i} L${cx - r} ${cy} L${cx - i} ${cy - i} Z`;
}

/* -- Face --------------------------------------------------------------- */

export function Eyes({
  cx,
  y,
  dx,
  r,
  mood,
  sleepy = false,
}: {
  cx: number;
  y: number;
  dx: number;
  r: number;
  mood: Mood;
  /** Peaceful closed eyes for the helpless stade-0 baby (idle only). */
  sleepy?: boolean;
}) {
  const lx = cx - dx;
  const rx = cx + dx;
  if (sleepy && mood === "idle") {
    const w = r * 1.1;
    return (
      <g fill="none" stroke={DARK} strokeWidth={r * 0.5} strokeLinecap="round">
        <path d={`M${lx - w} ${y} Q${lx} ${y + r * 0.9} ${lx + w} ${y}`} />
        <path d={`M${rx - w} ${y} Q${rx} ${y + r * 0.9} ${rx + w} ${y}`} />
      </g>
    );
  }
  if (mood === "cheer") {
    return (
      <g fill={DARK}>
        <path d={fourStar(lx, y, r * 1.35)} />
        <path d={fourStar(rx, y, r * 1.35)} />
      </g>
    );
  }
  if (mood === "happy") {
    const w = r * 1.25;
    return (
      <g fill="none" stroke={DARK} strokeWidth={r * 0.78} strokeLinecap="round">
        <path d={`M${lx - w} ${y + r * 0.5} Q${lx} ${y - r} ${lx + w} ${y + r * 0.5}`} />
        <path d={`M${rx - w} ${y + r * 0.5} Q${rx} ${y - r} ${rx + w} ${y + r * 0.5}`} />
      </g>
    );
  }
  return (
    <g>
      <circle cx={lx} cy={y} r={r} fill={DARK} />
      <circle cx={rx} cy={y} r={r} fill={DARK} />
      <circle cx={lx - r * 0.32} cy={y - r * 0.36} r={r * 0.36} fill="#fff" />
      <circle cx={rx - r * 0.32} cy={y - r * 0.36} r={r * 0.36} fill="#fff" />
      <circle cx={lx + r * 0.34} cy={y + r * 0.34} r={r * 0.16} fill="#fff" opacity={0.8} />
      <circle cx={rx + r * 0.34} cy={y + r * 0.34} r={r * 0.16} fill="#fff" opacity={0.8} />
    </g>
  );
}

export function Cheeks({ cx, y, dx, r }: { cx: number; y: number; dx: number; r: number }) {
  return (
    <g fill={BLUSH} opacity={0.6}>
      <ellipse cx={cx - dx} cy={y} rx={r} ry={r * 0.68} />
      <ellipse cx={cx + dx} cy={y} rx={r} ry={r * 0.68} />
    </g>
  );
}

export function Mouth({ cx, y, w, mood }: { cx: number; y: number; w: number; mood: Mood }) {
  if (mood === "idle") {
    return (
      <path
        d={`M${cx - w} ${y} Q${cx} ${y + w * 0.95} ${cx + w} ${y}`}
        fill="none"
        stroke={DARK}
        strokeWidth={1.5}
        strokeLinecap="round"
      />
    );
  }
  return (
    <g>
      <path d={`M${cx - w} ${y - w * 0.2} Q${cx} ${y + w * 1.5} ${cx + w} ${y - w * 0.2} Z`} fill={DARK} />
      <path
        d={`M${cx - w * 0.5} ${y + w * 0.5} Q${cx} ${y + w * 1.1} ${cx + w * 0.5} ${y + w * 0.5} Z`}
        fill="#FF7C93"
      />
    </g>
  );
}

/* -- Fur / hair plume (tails, manes, tufts) ----------------------------- */

/** A fan of rotated lock-ellipses. `wave` toggles a curly zig-zag. */
export function Plume({
  x,
  y,
  color,
  len,
  wide,
  rot,
  n,
  wave = false,
}: {
  x: number;
  y: number;
  color: string;
  len: number;
  wide: number;
  rot: number;
  n: number;
  wave?: boolean;
}) {
  const locks = [];
  for (let i = 0; i < n; i++) {
    const t = n === 1 ? 0.5 : i / (n - 1);
    const ox = x + (t - 0.5) * wide;
    const oy = y + Math.abs(t - 0.5) * len * 0.18;
    const r = wave ? rot + Math.sin(i * 1.9) * 22 : rot + (t - 0.5) * 26;
    const rad = (r * Math.PI) / 180;
    const tipX = ox + Math.sin(rad) * len;
    const tipY = oy + Math.cos(rad) * len;
    locks.push(
      <ellipse
        key={i}
        cx={(ox + tipX) / 2}
        cy={(oy + tipY) / 2}
        rx={len * 0.26}
        ry={len * 0.52}
        fill={color}
        transform={`rotate(${r} ${(ox + tipX) / 2} ${(oy + tipY) / 2})`}
      />
    );
  }
  return <g>{locks}</g>;
}

/* -- Legs --------------------------------------------------------------- */

export function Leg({
  spec,
  w,
  color,
  hoof,
}: {
  spec: LegSpec;
  w: number;
  color: string;
  hoof: string;
}) {
  const mx = (spec.hipX + spec.footX) / 2 + spec.side * spec.bend;
  const my = (spec.hipY + spec.footY) / 2;
  return (
    <g>
      <path
        d={`M${spec.hipX} ${spec.hipY} Q${mx} ${my} ${spec.footX} ${spec.footY}`}
        fill="none"
        stroke={color}
        strokeWidth={w}
        strokeLinecap="round"
      />
      <ellipse cx={spec.footX} cy={spec.footY} rx={w * 0.62} ry={w * 0.4} fill={hoof} />
    </g>
  );
}

/** Tucked folded legs for the lying (can't-stand) baby pose. */
export function FoldedLegs({
  bodyCX,
  bodyCY,
  bodyRX,
  color,
  hoof,
}: {
  bodyCX: number;
  bodyCY: number;
  bodyRX: number;
  color: string;
  hoof: string;
}) {
  const y = bodyCY + 8;
  const x = bodyCX + bodyRX * 0.35;
  return (
    <g stroke={color} strokeWidth={7} strokeLinecap="round" fill="none">
      <path d={`M${x - 4} ${y - 5} Q${x + 10} ${y + 2} ${x + 16} ${y}`} />
      <path d={`M${x - 10} ${y - 2} Q${x + 4} ${y + 6} ${x + 12} ${y + 4}`} />
      <g stroke="none" fill={hoof}>
        <ellipse cx={x + 16} cy={y} rx={4} ry={2.6} />
        <ellipse cx={x + 12} cy={y + 4} rx={4} ry={2.6} />
      </g>
    </g>
  );
}

/* -- Shine / glow ------------------------------------------------------- */

/** Soft radial glow (needs a unique gradient id per instance). */
export function Aura({
  id,
  cx,
  cy,
  r,
  color,
  opacity = 1,
}: {
  id: string;
  cx: number;
  cy: number;
  r: number;
  color: string;
  opacity?: number;
}) {
  return (
    <g opacity={opacity}>
      <defs>
        <radialGradient id={id}>
          <stop offset="0%" stopColor={color} stopOpacity="0.6" />
          <stop offset="55%" stopColor={color} stopOpacity="0.22" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </radialGradient>
      </defs>
      <circle cx={cx} cy={cy} r={r} fill={`url(#${id})`} />
    </g>
  );
}

export function Sparkles({
  points,
  color = "#FFF7D6",
}: {
  points: Array<[number, number, number]>;
  color?: string;
}) {
  if (points.length === 0) return null;
  return (
    <g fill={color}>
      {points.map(([x, y, r], i) => (
        <path key={i} d={fourStar(x, y, r)} />
      ))}
    </g>
  );
}

/* -- "Arc-en-ciel magique" premium overlay ------------------------------ */

/**
 * Premium "Arc-en-ciel magique" overlay — the ultimate reward. A single rainbow
 * prism of light that sweeps diagonally across the WHOLE image (wings included)
 * on a slow loop, like tilting a holographic card. No stars (the free growth
 * timeline already owns those) and NO colour film / glow over the pet — just the
 * travelling spectrum glint. Clipped to the viewBox so it never spills onto
 * surrounding UI. Motion is pure CSS (index.css: alSheen) — off the React render
 * path; under prefers-reduced-motion the animation freezes to a static rainbow
 * band across the centre. `id` must be unique per mascot instance.
 */
export function RainbowSheen({ id }: { id: string }) {
  const sheenW = 26; // half-width of the prism band, in viewBox units
  // Sweep range comfortably exceeds the pet's on-screen extent (wings overflow
  // the 0..100 box at the top stades). The band lives off the pet at both ends —
  // where the silhouette mask hides it — so the linear wrap is never seen.
  const sheenStyle = {
    "--al-from": "-150px",
    "--al-to": "150px",
  } as CSSProperties;

  return (
    <g>
      <defs>
        {/* the sweeping glint IS a rainbow prism — a spectrum band, soft at both edges */}
        <linearGradient id={`${id}-sheen`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#FF6F91" stopOpacity="0" />
          <stop offset="26%" stopColor="#FF6F91" stopOpacity="0" />
          <stop offset="38%" stopColor="#FF6F91" stopOpacity="0.75" />
          <stop offset="47%" stopColor="#FFD24C" stopOpacity="0.8" />
          <stop offset="53%" stopColor="#7CE8B0" stopOpacity="0.8" />
          <stop offset="60%" stopColor="#5BC8FF" stopOpacity="0.8" />
          <stop offset="68%" stopColor="#B98CFF" stopOpacity="0.75" />
          <stop offset="80%" stopColor="#B98CFF" stopOpacity="0" />
          <stop offset="100%" stopColor="#B98CFF" stopOpacity="0" />
        </linearGradient>
      </defs>

      {/* rainbow prism sweeping across the pet (confined by the silhouette mask) */}
      <g transform="rotate(-20 50 50)">
        <rect
          className="al-sheen"
          x={50 - sheenW}
          y={-120}
          width={sheenW * 2}
          height={340}
          fill={`url(#${id}-sheen)`}
          style={sheenStyle}
        />
      </g>
    </g>
  );
}

/* -- Accessories shared shapes ----------------------------------------- */

export function Bow({ x, y, s, color }: { x: number; y: number; s: number; color: string }) {
  return (
    <g>
      <path d={`M${x} ${y} L${x - 7 * s} ${y - 5 * s} Q${x - 9.5 * s} ${y} ${x - 7 * s} ${y + 5 * s} Z`} fill={color} />
      <path d={`M${x} ${y} L${x + 7 * s} ${y - 5 * s} Q${x + 9.5 * s} ${y} ${x + 7 * s} ${y + 5 * s} Z`} fill={color} />
      <circle cx={x} cy={y} r={2.6 * s} fill={color} />
      <circle cx={x - 0.6 * s} cy={y - 0.6 * s} r={1.1 * s} fill="#fff" opacity={0.4} />
    </g>
  );
}

/** One-piece striped bathing suit — a colour band CLIPPED to the torso ellipse so
 * it hugs the belly. Standing pets wear a waist band (champions, stade 6+, earn a
 * white `star` badge). `lying` newborns wear a little nappy-culotte on the RUMP
 * (away from the oversized resting head) with vertical stripes — the rig draws it
 * UNDER the head/neck so the face always stays on top. */
export function Swimsuit({ id, cx, cy, rx, ry, color, stripe = "#FFF6EE", lying = false, star = false }: { id: string; cx: number; cy: number; rx: number; ry: number; color: string; stripe?: string; lying?: boolean; star?: boolean }) {
  if (lying) {
    const edge = cx - rx * 0.16; // culotte covers the left (rump) side of the loaf
    return (
      <g>
        <defs>
          <clipPath id={id}>
            <ellipse cx={cx} cy={cy} rx={rx} ry={ry} />
          </clipPath>
        </defs>
        <g clipPath={`url(#${id})`}>
          <rect x={cx - rx - 1} y={cy - ry - 1} width={edge - (cx - rx - 1)} height={ry * 2 + 2} fill={color} />
          {[0.72, 0.44].map((k) => (
            <path
              key={k}
              d={`M${cx - rx * k} ${cy - ry} q 2.6 ${ry * 0.5} 0 ${ry} q -2.6 ${ry * 0.5} 0 ${ry}`}
              fill="none"
              stroke={stripe}
              strokeWidth={2}
              opacity={0.9}
            />
          ))}
        </g>
        <path d={`M${edge} ${cy - ry * 0.9} Q${edge + 2} ${cy} ${edge} ${cy + ry * 0.9}`} fill="none" stroke={DARK} strokeWidth={1} opacity={0.28} />
      </g>
    );
  }
  const top = cy - ry * 0.12;
  return (
    <g>
      <defs>
        <clipPath id={id}>
          <ellipse cx={cx} cy={cy} rx={rx} ry={ry} />
        </clipPath>
      </defs>
      <g clipPath={`url(#${id})`}>
        <rect x={cx - rx - 1} y={top} width={rx * 2 + 2} height={ry * 2} fill={color} />
        {[0.34, 0.68].map((k) => (
          <path
            key={k}
            d={`M${cx - rx} ${top + ry * k} q ${rx * 0.5} -3 ${rx} 0 q ${rx * 0.5} 3 ${rx} 0`}
            fill="none"
            stroke={stripe}
            strokeWidth={2.2}
            opacity={0.9}
          />
        ))}
      </g>
      <path d={`M${cx - rx * 0.97} ${top + 1.5} Q${cx} ${top - 2} ${cx + rx * 0.97} ${top + 1.5}`} fill="none" stroke={DARK} strokeWidth={1} opacity={0.28} />
      {star && <path d={fourStar(cx, cy + ry * 0.38, 3.1)} fill="#fff" opacity={0.95} />}
    </g>
  );
}

/** Classic segmented swim ring — a thick ellipse torus with alternating
 * colour/cream segments and faint rim outlines so it reads as a puffy
 * inflatable. To make the pet sit INSIDE the tube, rigs draw it twice: the
 * `back` half behind the body, the `front` half over it (each half is the same
 * geometry clipped at the tube's midline). `full` is for lying babies who rest
 * ON the ring. `duck` (stade 7+) perches an inflatable duck head on the tube —
 * drawn with the front half so the body never hides it. */
export function SwimRing({ id, cx, cy, rx, color, duck = false, part = "full" }: { id: string; cx: number; cy: number; rx: number; color: string; duck?: boolean; part?: "full" | "back" | "front" }) {
  const ry = rx * 0.38;
  const w = rx * 0.32;
  // ~1/8 of the ellipse perimeter (Ramanujan) → 4 colour + 4 cream segments.
  const per = Math.PI * (3 * (rx + ry) - Math.sqrt((3 * rx + ry) * (rx + 3 * ry)));
  const dash = per / 8;
  const pad = w + 2;
  const hx = cx + rx * 0.95;
  const hy = cy - w * 0.55;
  const hr = w * 0.8;
  return (
    <g>
      {duck && part !== "back" && (
        <g>
          <circle cx={hx} cy={hy} r={hr} fill="#FFE082" />
          <circle cx={hx} cy={hy} r={hr} fill="none" stroke={DARK} strokeWidth={0.7} opacity={0.25} />
          <path d={`M${hx + hr * 0.6} ${hy - 1.4} L${hx + hr + 3.6} ${hy + 0.4} L${hx + hr * 0.6} ${hy + 1.8} Z`} fill="#FF8A50" />
          <circle cx={hx + hr * 0.25} cy={hy - hr * 0.25} r={0.9} fill={DARK} />
        </g>
      )}
      {part !== "full" && (
        <defs>
          <clipPath id={id}>
            {part === "back" ? (
              <rect x={cx - rx - pad} y={cy - ry - pad} width={(rx + pad) * 2} height={ry + pad} />
            ) : (
              <rect x={cx - rx - pad} y={cy} width={(rx + pad) * 2} height={ry + pad} />
            )}
          </clipPath>
        </defs>
      )}
      <g clipPath={part === "full" ? undefined : `url(#${id})`}>
        <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="none" stroke="#FFF6EE" strokeWidth={w} />
        <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="none" stroke={color} strokeWidth={w} strokeDasharray={`${dash} ${dash}`} />
        <ellipse cx={cx} cy={cy} rx={rx + w / 2} ry={ry + w / 2} fill="none" stroke={DARK} strokeWidth={0.8} opacity={0.22} />
        <ellipse cx={cx} cy={cy} rx={rx - w / 2} ry={ry - w / 2} fill="none" stroke={DARK} strokeWidth={0.8} opacity={0.22} />
      </g>
    </g>
  );
}

export function Flower({
  x,
  y,
  r,
  petal,
  center,
}: {
  x: number;
  y: number;
  r: number;
  petal: string;
  center: string;
}) {
  const petals = [];
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * Math.PI * 2 - Math.PI / 2;
    petals.push(
      <circle key={i} cx={x + Math.cos(a) * r} cy={y + Math.sin(a) * r} r={r * 0.72} fill={petal} />
    );
  }
  return (
    <g>
      {petals}
      <circle cx={x} cy={y} r={r * 0.6} fill={center} />
    </g>
  );
}

/* -- Majestic-stage shared parts ---------------------------------------- */
/* These carry the late-stage "wow". Every one is OUTLINED / non-body-coloured
 * on purpose: features that share the body's colour vanish into the silhouette
 * and a child never sees the reward. Keep the edges. */

/** Glowing head halo (soft aura + a crisp ring). Needs a unique `id`. */
export function Halo({ id, cx, cy, r, opacity, color = "#FFF3C4" }: { id: string; cx: number; cy: number; r: number; opacity: number; color?: string }) {
  return (
    <g>
      <Aura id={id} cx={cx} cy={cy} r={r} color={color} opacity={opacity} />
      <circle cx={cx} cy={cy} r={r * 0.62} fill="none" stroke={color} strokeWidth={1.4} opacity={opacity * 0.8} />
    </g>
  );
}

/** Flat radial glow pooled under the feet. Needs a unique `id`. */
export function GroundGlow({ id, cx, y, rx, color, opacity }: { id: string; cx: number; y: number; rx: number; color: string; opacity: number }) {
  return (
    <g opacity={opacity}>
      <defs>
        <radialGradient id={id}>
          <stop offset="0%" stopColor={color} stopOpacity="0.8" />
          <stop offset="55%" stopColor={color} stopOpacity="0.2" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </radialGradient>
      </defs>
      <ellipse cx={cx} cy={y} rx={rx} ry={rx * 0.3} fill={`url(#${id})`} />
    </g>
  );
}

/** Outlined tiara/crown arcing over the head, gem-tipped spikes. */
export function Crown({ cx, cy, r, band = "#FFD54F", gem = "#FF7EA8", edge = "#B07E1E" }: { cx: number; cy: number; r: number; band?: string; gem?: string; edge?: string }) {
  const spikes = [-0.62, -0.31, 0, 0.31, 0.62].map((f, i) => {
    const a = (-90 + f * 78) * (Math.PI / 180);
    const bx = cx + Math.cos(a) * r;
    const by = cy + Math.sin(a) * r;
    const h = i === 2 ? 8.5 : 5;
    return { bx, by, tx: cx + Math.cos(a) * (r + h), ty: cy + Math.sin(a) * (r + h), big: i === 2 };
  });
  const bandPath = `M${cx - r * 0.72} ${cy - r * 0.55} Q${cx} ${cy - r * 1.02} ${cx + r * 0.72} ${cy - r * 0.55}`;
  return (
    <g>
      <path d={bandPath} fill="none" stroke={edge} strokeWidth={4.6} strokeLinecap="round" />
      <path d={bandPath} fill="none" stroke={band} strokeWidth={3.2} strokeLinecap="round" />
      {spikes.map((s, i) => (
        <g key={i}>
          <path d={`M${s.bx - 2} ${s.by} L${s.tx} ${s.ty} L${s.bx + 2} ${s.by} Z`} fill={band} stroke={edge} strokeWidth={0.9} strokeLinejoin="round" />
          <circle cx={s.tx} cy={s.ty} r={s.big ? 2.4 : 1.7} fill={gem} stroke={edge} strokeWidth={0.6} />
        </g>
      ))}
    </g>
  );
}

/** A ring of sparkle-stars around a centre (a celebratory burst). */
export function Burst({ cx, cy, rx, ry, n, color }: { cx: number; cy: number; rx: number; ry: number; n: number; color?: string }) {
  return (
    <Sparkles
      color={color}
      points={Array.from({ length: n }).map((_, i) => {
        const a = (i / n) * Math.PI * 2;
        return [cx + Math.cos(a) * rx, cy + Math.sin(a) * ry, 1.6 + (i % 3)] as [number, number, number];
      })}
    />
  );
}
