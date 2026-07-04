import { useId, type ReactNode } from "react";
import type { MascotConfig, Species } from "../types";
import { layoutFor, type Layout } from "../mascot/growth";
import { Aura, Plume, Sparkles, fourStar } from "../mascot/parts";
import { Unicorn, U_STAGES, type USpec } from "../mascot/Unicorn";
import { Cat, C_STAGES, type CSpec } from "../mascot/Cat";
import { Fox, F_STAGES, type FSpec } from "../mascot/Fox";

/* ==========================================================================
 * GROWTH-STAGE REDESIGN — DEV / REVIEW ONLY (not wired into the kid flow).
 *
 * Two competing redesigns per species for stades 3..9. Stades 0..2 are kept
 * IDENTICAL to production (the first three stages the client already likes).
 *
 * A proposal is: a RETUNED base spec (drives the existing rig's built-in
 * features harder / differently) + BEHIND/FRONT SVG overlays adding brand-new
 * parts (crowns, halos, extra tails, flames, wings…) + a per-stage `scale` so
 * the growth is actually FELT. Nothing here touches the shipped rigs beyond the
 * tiny optional `spec` seam, so picking a winner later is a clean fold-in.
 * ======================================================================== */

export type Proposal = 1 | 2;

export interface Design {
  scale: number;
  behind: ReactNode;
  front: ReactNode;
  note: string;
}

/* Default palettes — ProposalMascot renders with an empty config, so overlays
 * must match each rig's built-in defaults. */
const UNI = { body: "#F5ECFF", mane: "#BA9EE8", horn: "#FFD54F", crystal: "#8FD8E8", gem: "#7FD1D8" };
const CATP = { body: "#F6A96B", light: "#FFE0C4", mane: "#E0872F", maneEdge: "#A85A16", wing: "#EAF2FF" };
const FOXP = { body: "#FF8A65", tailDeep: "#F26B3C", edge: "#B8431C", tip: "#FFF6EE", ember: "#FFB74D", spark: "#FFE082", flame: "#FF7043" };

const RAINBOW = ["#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8", "#BA9EE8"];
const GROW = [1, 1, 1, 1.0, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3];
const scaleAt = (stage: number) => GROW[Math.max(0, Math.min(9, stage))];

/* -- shared overlay art ---------------------------------------------------- */

function Halo({ cx, cy, r, opacity, uid, color = "#FFF3C4" }: { cx: number; cy: number; r: number; opacity: number; uid: string; color?: string }) {
  return (
    <g>
      <Aura id={`${uid}-halo`} cx={cx} cy={cy} r={r} color={color} opacity={opacity} />
      <circle cx={cx} cy={cy} r={r * 0.62} fill="none" stroke={color} strokeWidth={1.4} opacity={opacity * 0.8} />
    </g>
  );
}

function GroundGlow({ cx, y, rx, color, uid, opacity }: { cx: number; y: number; rx: number; color: string; uid: string; opacity: number }) {
  const id = `${uid}-ground`;
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

function Tiara({ cx, cy, r, band = "#FFD54F", gem = "#FF7EA8", edge = "#B07E1E" }: { cx: number; cy: number; r: number; band?: string; gem?: string; edge?: string }) {
  const spikes = [-0.62, -0.31, 0, 0.31, 0.62].map((f, i) => {
    const a = (-90 + f * 78) * (Math.PI / 180);
    const bx = cx + Math.cos(a) * r;
    const by = cy + Math.sin(a) * r;
    const h = i === 2 ? 8.5 : 5;
    return { bx, by, tx: cx + Math.cos(a) * (r + h), ty: cy + Math.sin(a) * (r + h), big: i === 2 };
  });
  return (
    <g>
      <path
        d={`M${cx - r * 0.72} ${cy - r * 0.55} Q${cx} ${cy - r * 1.02} ${cx + r * 0.72} ${cy - r * 0.55}`}
        fill="none"
        stroke={edge}
        strokeWidth={4.6}
        strokeLinecap="round"
      />
      <path
        d={`M${cx - r * 0.72} ${cy - r * 0.55} Q${cx} ${cy - r * 1.02} ${cx + r * 0.72} ${cy - r * 0.55}`}
        fill="none"
        stroke={band}
        strokeWidth={3.2}
        strokeLinecap="round"
      />
      {spikes.map((s, i) => (
        <g key={i}>
          <path d={`M${s.bx - 2} ${s.by} L${s.tx} ${s.ty} L${s.bx + 2} ${s.by} Z`} fill={band} stroke={edge} strokeWidth={0.9} strokeLinejoin="round" />
          <circle cx={s.tx} cy={s.ty} r={s.big ? 2.4 : 1.7} fill={gem} stroke={edge} strokeWidth={0.6} />
        </g>
      ))}
    </g>
  );
}

function Gem({ x, y, r, color }: { x: number; y: number; r: number; color: string }) {
  return (
    <g>
      <path d={`M${x} ${y - r} L${x + r * 0.8} ${y} L${x} ${y + r} L${x - r * 0.8} ${y} Z`} fill={color} />
      <path d={`M${x} ${y - r} L${x + r * 0.8} ${y} L${x} ${y} Z`} fill="#fff" opacity={0.4} />
    </g>
  );
}

function OWings({ cx, cy, s, color = "#BFE3FF", edge = "#5AA9E0", starry = false }: { cx: number; cy: number; s: number; color?: string; edge?: string; starry?: boolean }) {
  const wing = (dir: number, key: string) => (
    <g key={key} transform={`translate(${cx + dir * 6} ${cy}) scale(${dir * s} ${s})`}>
      <path d="M0 0 Q18 -24 34 -13 Q25 -6 30 4 Q19 -2 23 11 Q13 3 15 15 Q7 6 6 17 Q1 8 0 0 Z" fill={color} stroke={edge} strokeWidth={1.3} strokeLinejoin="round" />
      {/* feather separations for definition */}
      <path d="M4 2 Q15 -14 28 -9" fill="none" stroke={edge} strokeWidth={0.9} opacity={0.7} />
      <path d="M3 7 Q13 -1 24 2" fill="none" stroke={edge} strokeWidth={0.8} opacity={0.55} />
      {starry &&
        ([[12, -7], [18, -1], [22, 6], [14, 9]] as const).map(([x, y], i) => (
          <path key={i} d={fourStar(x, y, 1.5)} fill="#fff" />
        ))}
    </g>
  );
  return (
    <g>
      {wing(-1, "l")}
      {wing(1, "r")}
    </g>
  );
}

function CrystalHorn({ x, baseY, h, glow, color = UNI.crystal }: { x: number; baseY: number; h: number; glow: boolean; color?: string }) {
  const apexY = baseY - h;
  const hw = 2 + h * 0.17;
  const ridges = Math.max(1, Math.round(h / 5));
  return (
    <g>
      <path d={`M${x} ${apexY} L${x - hw} ${baseY} Q${x} ${baseY + hw * 0.5} ${x + hw} ${baseY} Z`} fill={color} stroke="#3E8FB0" strokeWidth={1} strokeLinejoin="round" />
      <path d={`M${x} ${apexY} L${x + hw} ${baseY} Q${x} ${baseY + hw * 0.5} ${x} ${baseY} Z`} fill="#fff" opacity={0.4} />
      {Array.from({ length: ridges }).map((_, i) => {
        const t = (i + 0.5) / ridges;
        const yy = baseY - h * t;
        const ww = hw * (1 - t) + 0.5;
        return <line key={i} x1={x - ww} y1={yy} x2={x + ww} y2={yy} stroke="#fff" strokeWidth={0.6} opacity={0.5} />;
      })}
      {glow && (
        <>
          <path d={fourStar(x, apexY - 1.5, 3.4)} fill="#FFFFFF" />
          <path d={fourStar(x, apexY - 1.5, 6)} fill="#FFFFFF" opacity={0.35} />
        </>
      )}
    </g>
  );
}

function Shard({ x, y, s, rot, color }: { x: number; y: number; s: number; rot: number; color: string }) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <path d="M0 -6 L2.4 0 L0 6 L-2.4 0 Z" fill={color} stroke="#3E8FB0" strokeWidth={0.8} strokeLinejoin="round" />
      <path d="M0 -6 L2.4 0 L0 0 Z" fill="#fff" opacity={0.45} />
    </g>
  );
}

function ShardRing({ cx, cy, r, n, uid, color = UNI.crystal }: { cx: number; cy: number; r: number; n: number; uid: string; color?: string }) {
  return (
    <g>
      {Array.from({ length: n }).map((_, i) => {
        const a = (i / n) * Math.PI * 2 - Math.PI / 2;
        const rr = r * (i % 2 ? 1.08 : 0.9);
        return <Shard key={`${uid}${i}`} x={cx + Math.cos(a) * rr} y={cy + Math.sin(a) * rr} s={0.9 + (i % 3) * 0.18} rot={(a * 180) / Math.PI + 90} color={color} />;
      })}
    </g>
  );
}

function Flame({ x, y, s, outer = FOXP.flame, inner = FOXP.spark }: { x: number; y: number; s: number; outer?: string; inner?: string }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${s})`}>
      <path d="M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z" fill={outer} />
      <path d="M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z" fill={inner} />
    </g>
  );
}

function Embers({ cx, cy, r, n, seed }: { cx: number; cy: number; r: number; n: number; seed: number }) {
  return (
    <g>
      {Array.from({ length: n }).map((_, i) => {
        const a = i * 2.399963 + seed;
        const rr = r * (0.35 + 0.65 * (((i * 0.61 + seed) % 1) + 0) );
        const x = cx + Math.cos(a) * rr;
        const y = cy + Math.sin(a) * rr - i * 0.35;
        return <circle key={i} cx={x} cy={y} r={0.8 + (i % 3) * 0.45} fill={i % 2 ? FOXP.ember : FOXP.spark} opacity={0.9} />;
      })}
    </g>
  );
}

function Mane({ cx, cy, r, size, color, edge }: { cx: number; cy: number; r: number; size: number; color: string; edge: string }) {
  const n = 15;
  const ring = (len: number, col: string, prefix: string) =>
    Array.from({ length: n }).map((_, i) => {
      const a = (i / n) * Math.PI * 2;
      const x = cx + Math.cos(a) * r;
      const y = cy + Math.sin(a) * r + r * 0.28;
      return <Plume key={`${prefix}${i}`} x={x} y={y} color={col} len={len} wide={size * 0.6} rot={90 - (a * 180) / Math.PI} n={2} />;
    });
  return (
    <g>
      {ring(size + 2.2, edge, "e")}
      {ring(size, color, "f")}
    </g>
  );
}

function Burst({ cx, cy, rx, ry, n, color }: { cx: number; cy: number; rx: number; ry: number; n: number; color?: string }) {
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

/** Extra fanned tail (kitsune) — a bushy fur-clump curve at a given angle.
 * Drawn with an outline underlay + a deeper fill so each tail separates from
 * the same-coloured body instead of melting into it. */
function FanTail({ rootX, rootY, angleDeg, length, rB, color, edge, tip, flame, ki }: { rootX: number; rootY: number; angleDeg: number; length: number; rB: number; color: string; edge: string; tip: string; flame: boolean; ki: number }) {
  const a = (angleDeg * Math.PI) / 180;
  const perp = a + Math.PI / 2;
  const ex = rootX + Math.cos(a) * length;
  const ey = rootY + Math.sin(a) * length;
  const cx = rootX + Math.cos(a) * length * 0.5 + Math.cos(perp) * length * 0.16;
  const cy = rootY + Math.sin(a) * length * 0.5 + Math.sin(perp) * length * 0.16;
  const bez = (t: number): [number, number] => [
    (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * cx + t * t * ex,
    (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * cy + t * t * ey,
  ];
  const clumps = Array.from({ length: 6 }).map((_, i) => {
    const t = i / 5;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.55 + 0.5 * Math.sin(Math.PI * (0.12 + 0.8 * t))), tip: t > 0.72 };
  });
  const [tx, ty] = bez(1);
  return (
    <g key={ki}>
      {/* outline underlay */}
      {clumps.map((b, i) => (
        <circle key={`o${i}`} cx={b.px} cy={b.py} r={b.r + 1.1} fill={edge} />
      ))}
      {clumps.map((b, i) => (
        <circle key={i} cx={b.px} cy={b.py} r={b.r} fill={b.tip ? tip : color} />
      ))}
      {flame && <Flame x={tx} y={ty} s={0.6} />}
    </g>
  );
}

/* -- per-species proposal builders ---------------------------------------- */

function pick<T>(map: Record<number, T>, stage: number): T {
  return map[stage];
}

function unicornDesign(p: Proposal, stage: number, l: Layout, uid: string): { spec: USpec; d: Design } {
  if (stage < 3) return { spec: U_STAGES[stage], d: { scale: 1, behind: null, front: null, note: "= actuel" } };
  const scale = scaleAt(stage);
  const hornBaseY = l.headCY - l.headR * 0.72;

  if (p === 1) {
    // Wings are drawn by the overlay (below) with a real outline, so the base
    // rig's pale wings stay off — contrast is fully controlled here.
    const spec = pick<USpec>(
      {
        3: { horn: 9, shine: false, wing: 0, aura: 0, sparkle: 0 },
        4: { horn: 12, shine: false, wing: 0, aura: 0.1, sparkle: 0 },
        5: { horn: 15, shine: false, wing: 0, aura: 0.25, sparkle: 1 },
        6: { horn: 17, shine: false, wing: 0, aura: 0.4, sparkle: 2 },
        7: { horn: 19, shine: false, wing: 0, aura: 0.6, sparkle: 3 },
        8: { horn: 21, shine: true, wing: 0, aura: 0.85, sparkle: 4 },
        9: { horn: 26, shine: true, wing: 0, aura: 1, sparkle: 7 },
      },
      stage
    );
    const wingS = pick<number>({ 3: 0.8, 4: 1.1, 5: 1.3, 6: 1.5, 7: 1.7, 8: 1.9, 9: 2.2 }, stage);
    const note = pick<string>(
      {
        3: "Corne + petites ailes ouvertes",
        4: "Ailes grandes ouvertes",
        5: "Crinière longue + 1ʳᵉ étincelle",
        6: "Halo lumineux",
        7: "Couronne d'étoiles",
        8: "Crinière arc-en-ciel + corne brillante",
        9: "✨ Alicorne céleste : halo, couronne, faisceau",
      },
      stage
    );
    const behind = (
      <g>
        {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 20} color="#FFE29A" uid={uid} opacity={0.9} />}
        <OWings cx={l.bodyCX} cy={l.bodyCY - l.bodyRY * 0.28} s={wingS} color="#BFE3FF" edge="#5AA9E0" starry={stage >= 9} />
        {stage >= 6 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={stage >= 8 ? 0.9 : 0.5} uid={uid} />}
        {stage >= 5 && stage < 8 && (
          <Plume x={l.headCX + l.headR * 0.24} y={l.headCY - l.headR * 0.08} color={UNI.mane} len={l.headR * (1.15 + 0.28 * (stage - 5))} wide={l.headR * 0.62} rot={24} n={4} />
        )}
        {stage >= 8 &&
          RAINBOW.map((c, i) => (
            <Plume key={i} x={l.headCX + l.headR * 0.16} y={l.headCY - l.headR * 0.2 + (i / 4) * l.headR} color={c} len={l.headR * 1.5} wide={l.headR * 0.5} rot={26 + i * 5} n={3} />
          ))}
      </g>
    );
    const apexY = hornBaseY - spec.horn;
    const front = (
      <g>
        {stage >= 7 && <Tiara cx={l.headCX} cy={l.headCY - l.headR * 0.5} r={l.headR * 0.95} band={UNI.horn} gem="#FF7EA8" />}
        {stage >= 9 && (
          <g>
            <path d={`M${l.headCX - 5} ${apexY} L${l.headCX} ${apexY - 26} L${l.headCX + 5} ${apexY} Z`} fill="#FFF6C4" opacity={0.5} />
            <path d={fourStar(l.headCX, apexY - 2, 4.2)} fill="#FFF3C4" />
            <Burst cx={50} cy={l.bodyCY - 4} rx={l.bodyRX + 17} ry={l.bodyRY + 14} n={9} color="#FFF3C4" />
          </g>
        )}
      </g>
    );
    return { spec, d: { scale, behind, front, note } };
  }

  // P2 — Cristal Royal (no feathered wings; faceted crystal horn + jewels)
  const spec = pick<USpec>(
    {
      3: { horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0 },
      4: { horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0 },
      5: { horn: 0, shine: false, wing: 0, aura: 0.1, sparkle: 0 },
      6: { horn: 0, shine: false, wing: 0, aura: 0.3, sparkle: 0 },
      7: { horn: 0, shine: false, wing: 0, aura: 0.5, sparkle: 2 },
      8: { horn: 0, shine: false, wing: 0, aura: 0.75, sparkle: 3 },
      9: { horn: 0, shine: false, wing: 0, aura: 1, sparkle: 7 },
    },
    stage
  );
  const hornH = pick<number>({ 3: 8, 4: 11, 5: 14, 6: 16, 7: 18, 8: 21, 9: 27 }, stage);
  const shards = pick<number>({ 3: 0, 4: 0, 5: 0, 6: 3, 7: 4, 8: 5, 9: 6 }, stage);
  const note = pick<string>(
    {
      3: "Corne de cristal facettée",
      4: "Joyau au front",
      5: "Diadème de joyaux",
      6: "Cristaux flottants + aura",
      7: "Corne rayonnante",
      8: "Halo royal + joyaux",
      9: "👑 Cristal : couronne, corne prismatique, orbite",
    },
    stage
  );
  const behind = (
    <g>
      {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 20} color={UNI.crystal} uid={uid} opacity={0.85} />}
      {stage >= 8 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={0.7} uid={uid} color={UNI.crystal} />}
      {shards > 0 && <ShardRing cx={50} cy={l.bodyCY - 2} r={l.bodyRX + 12} n={shards} uid={uid} />}
    </g>
  );
  const front = (
    <g>
      <CrystalHorn x={l.headCX} baseY={hornBaseY} h={hornH} glow={stage >= 7} />
      {stage >= 4 && <Gem x={l.headCX} y={l.headCY - l.headR * 0.12} r={2.6} color={UNI.gem} />}
      {stage >= 5 && <Tiara cx={l.headCX} cy={l.headCY - l.headR * 0.42} r={l.headR * 0.98} band="#EAF6FF" gem={UNI.gem} edge="#5AA9E0" />}
      {stage >= 9 && <Burst cx={50} cy={l.bodyCY - 4} rx={l.bodyRX + 17} ry={l.bodyRY + 14} n={9} color="#EAF6FF" />}
    </g>
  );
  return { spec, d: { scale, behind, front, note } };
}

function catDesign(p: Proposal, stage: number, l: Layout, uid: string): { spec: CSpec; d: Design } {
  if (stage < 3) return { spec: C_STAGES[stage], d: { scale: 1, behind: null, front: null, note: "= actuel" } };
  const scale = scaleAt(stage);

  if (p === 1) {
    // Lynx Royal — big-cat majesty, lion-like mane
    const spec = pick<CSpec>(
      {
        3: { tail: 1.05, tuft: "none", aura: 0, sparkle: 0 },
        4: { tail: 1.2, tuft: "small", aura: 0, sparkle: 0 },
        5: { tail: 1.4, tuft: "small", aura: 0, sparkle: 0 },
        6: { tail: 1.55, tuft: "small", aura: 0.15, sparkle: 1 },
        7: { tail: 1.7, tuft: "big", aura: 0.4, sparkle: 2 },
        8: { tail: 1.95, tuft: "big", aura: 0.7, sparkle: 3 },
        9: { tail: 2.4, tuft: "big", aura: 1, sparkle: 6 },
      },
      stage
    );
    const mane = pick<number>({ 3: 0, 4: 0, 5: 5, 6: 7, 7: 9, 8: 11, 9: 14 }, stage);
    const note = pick<string>(
      {
        3: "Touffes de joues",
        4: "Aigrettes de lynx",
        5: "Collerette naissante",
        6: "Crinière + étincelle",
        7: "Grande crinière",
        8: "Aura + crinière XL",
        9: "🦁 Lynx royal : crinière, couronne, aura",
      },
      stage
    );
    const behind = (
      <g>
        {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 18} color="#FFE29A" uid={uid} opacity={0.85} />}
        {stage >= 8 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={0.6} uid={uid} />}
        {mane > 0 && <Mane cx={l.headCX} cy={l.headCY} r={l.headR * 1.02} size={mane} color={CATP.mane} edge={CATP.maneEdge} />}
      </g>
    );
    const front = (
      <g>
        {([-1, 1] as const).map((d) => (
          <Plume key={d} x={l.headCX + d * l.headR * 0.9} y={l.headCY + l.headR * 0.24} color={CATP.light} len={7} wide={5} rot={d * 72} n={3} />
        ))}
        {stage >= 9 && <Tiara cx={l.headCX} cy={l.headCY - l.headR * 0.5} r={l.headR * 0.95} band="#FFD54F" gem="#E0533B" />}
        {stage >= 9 && <Burst cx={50} cy={l.bodyCY - 2} rx={l.bodyRX + 15} ry={l.bodyRY + 12} n={8} color="#FFF3C4" />}
      </g>
    );
    return { spec, d: { scale, behind, front, note } };
  }

  // P2 — Chat Céleste (winged magic cat, star tail)
  const spec = pick<CSpec>(
    {
      3: { tail: 0.95, tuft: "none", aura: 0, sparkle: 0 },
      4: { tail: 1.05, tuft: "none", aura: 0, sparkle: 0 },
      5: { tail: 1.15, tuft: "none", aura: 0, sparkle: 0 },
      6: { tail: 1.25, tuft: "small", aura: 0.2, sparkle: 1 },
      7: { tail: 1.4, tuft: "small", aura: 0.4, sparkle: 2 },
      8: { tail: 1.6, tuft: "big", aura: 0.7, sparkle: 3 },
      9: { tail: 1.9, tuft: "big", aura: 1, sparkle: 6 },
    },
    stage
  );
  const wing = pick<number>({ 3: 0, 4: 0.35, 5: 0.6, 6: 0.85, 7: 1.05, 8: 1.3, 9: 1.65 }, stage);
  const note = pick<string>(
    {
      3: "Étoile au front",
      4: "Ailes naissantes",
      5: "Petites ailes",
      6: "Collier d'étoiles",
      7: "Ailes + queue étoilée",
      8: "Halo céleste",
      9: "🌟 Chat céleste ailé, queue-étoile",
    },
    stage
  );
  const tailTip: [number, number] = [l.bodyCX + l.bodyRX * 0.72 + 12, l.bodyCY - l.bodyRY * 1.0];
  const behind = (
    <g>
      {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 18} color="#CDE7FF" uid={uid} opacity={0.85} />}
      {stage >= 8 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={0.7} uid={uid} color="#CDE7FF" />}
      {wing > 0 && <OWings cx={l.bodyCX} cy={l.bodyCY - l.bodyRY * 0.32} s={wing * 1.15} color="#CBE4FF" edge="#6FB0E8" starry={stage >= 9} />}
    </g>
  );
  const front = (
    <g>
      <path d={fourStar(l.headCX, l.headCY - l.headR * 0.16, 3)} fill="#FFF3C4" />
      {stage >= 6 && (
        <g>
          <path d={`M${l.headCX - l.headR * 0.7} ${l.headCY + l.headR * 0.86} Q${l.headCX} ${l.headCY + l.headR * 1.25} ${l.headCX + l.headR * 0.7} ${l.headCY + l.headR * 0.86}`} fill="none" stroke="#8FD8E8" strokeWidth={2.2} />
          {[-0.5, 0, 0.5].map((f, i) => (
            <path key={i} d={fourStar(l.headCX + f * l.headR * 0.7, l.headCY + l.headR * (1.05 + Math.abs(f) * -0.16), 1.8)} fill="#FFF3C4" />
          ))}
        </g>
      )}
      {stage >= 7 && (
        <g>
          <Aura id={`${uid}-cattail`} cx={tailTip[0]} cy={tailTip[1]} r={7} color="#FFF3C4" opacity={0.9} />
          <path d={fourStar(tailTip[0], tailTip[1], 3.2)} fill="#FFF3C4" />
        </g>
      )}
      {stage >= 9 && (
        <g>
          <Tiara cx={l.headCX} cy={l.headCY - l.headR * 0.52} r={l.headR * 0.92} band="#EAF6FF" gem="#7FD1D8" edge="#6FB0E8" />
          <Burst cx={50} cy={l.bodyCY - 2} rx={l.bodyRX + 15} ry={l.bodyRY + 12} n={8} color="#FFF3C4" />
        </g>
      )}
    </g>
  );
  return { spec, d: { scale, behind, front, note } };
}

function foxDesign(p: Proposal, stage: number, l: Layout, uid: string): { spec: FSpec; d: Design } {
  if (stage < 3) return { spec: F_STAGES[stage], d: { scale: 1, behind: null, front: null, note: "= actuel" } };
  const scale = scaleAt(stage);
  const rumpX = l.bodyCX;
  const rumpY = l.bodyCY + l.bodyRY * 0.02;

  if (p === 1) {
    // Kitsune — extra tails sprout as it grows
    const spec = pick<FSpec>(
      {
        3: { tail: 0.95, ruff: 0.7, tuft: true, aura: 0, sparkle: 0 },
        4: { tail: 1.05, ruff: 0.8, tuft: true, aura: 0, sparkle: 0 },
        5: { tail: 1.15, ruff: 0.9, tuft: true, aura: 0, sparkle: 0 },
        6: { tail: 1.25, ruff: 1.0, tuft: true, aura: 0.2, sparkle: 1 },
        7: { tail: 1.4, ruff: 1.1, tuft: true, aura: 0.4, sparkle: 2 },
        8: { tail: 1.55, ruff: 1.2, tuft: true, aura: 0.7, sparkle: 3 },
        9: { tail: 1.8, ruff: 1.3, tuft: true, aura: 1, sparkle: 6 },
      },
      stage
    );
    const tails = pick<number>({ 3: 0, 4: 1, 5: 2, 6: 2, 7: 4, 8: 4, 9: 6 }, stage);
    const flame = stage >= 6;
    const tailF = spec.tail;
    const note = pick<string>(
      {
        3: "Aigrettes d'oreilles + collerette",
        4: "2ᵉ queue !",
        5: "3 queues",
        6: "Étincelles + bouts enflammés",
        7: "5 queues, feux-follets",
        8: "Halo + aura de feu",
        9: "🦊 Kitsune : 7 queues, feux, halo",
      },
      stage
    );
    const behind = (
      <g>
        {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 18} color="#FFD59A" uid={uid} opacity={0.85} />}
        {stage >= 8 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={0.55} uid={uid} color="#FFD59A" />}
        {Array.from({ length: tails }).map((_, i) => {
          const spread = tails === 1 ? 0 : i / (tails - 1) - 0.5;
          const angle = 270 + spread * 132; // symmetric fan rising behind the body
          return (
            <FanTail
              key={i}
              ki={i}
              rootX={rumpX}
              rootY={rumpY}
              angleDeg={angle}
              length={l.bodyRX * (1.12 + 0.42 * tailF)}
              rB={4 + 3 * tailF}
              color={FOXP.tailDeep}
              edge={FOXP.edge}
              tip={FOXP.tip}
              flame={flame}
            />
          );
        })}
      </g>
    );
    const front = (
      <g>
        {stage >= 8 && <path d={fourStar(l.headCX, l.headCY - l.headR * 0.55, 2.6)} fill="#FFF3C4" />}
        {stage >= 9 && <Burst cx={50} cy={l.bodyCY - 2} rx={l.bodyRX + 15} ry={l.bodyRY + 12} n={8} color="#FFE082" />}
      </g>
    );
    return { spec, d: { scale, behind, front, note } };
  }

  // P2 — Renard de Braise (ember / fire fox)
  const spec = pick<FSpec>(
    {
      3: { tail: 0.95, ruff: 0.7, tuft: true, aura: 0, sparkle: 0 },
      4: { tail: 1.05, ruff: 0.85, tuft: true, aura: 0, sparkle: 0 },
      5: { tail: 1.15, ruff: 0.95, tuft: true, aura: 0, sparkle: 0 },
      6: { tail: 1.25, ruff: 1.05, tuft: true, aura: 0.3, sparkle: 1 },
      7: { tail: 1.4, ruff: 1.15, tuft: true, aura: 0.5, sparkle: 2 },
      8: { tail: 1.55, ruff: 1.25, tuft: true, aura: 0.8, sparkle: 3 },
      9: { tail: 1.75, ruff: 1.35, tuft: true, aura: 1, sparkle: 6 },
    },
    stage
  );
  const note = pick<string>(
    {
      3: "Bout de queue incandescent",
      4: "Braises volantes",
      5: "Queue enflammée",
      6: "Aura de braises",
      7: "Crinière de feu",
      8: "Halo ardent",
      9: "🔥 Renard de braise : queue ardente, crinière",
    },
    stage
  );
  const tipX = l.bodyCX + l.bodyRX * 0.92;
  const tipY = l.bodyCY + l.bodyRY * 0.9;
  const behind = (
    <g>
      {stage >= 9 && <GroundGlow cx={50} y={l.feetY + 2} rx={l.bodyRX + 18} color="#FF8A3D" uid={uid} opacity={0.85} />}
      {stage >= 8 && <Halo cx={l.headCX} cy={l.headCY} r={l.headR * 1.7} opacity={0.6} uid={uid} color="#FF9A4D" />}
    </g>
  );
  const flames = stage >= 9 ? 3 : 1;
  const front = (
    <g>
      {stage >= 3 && <Aura id={`${uid}-tiptail`} cx={tipX} cy={tipY} r={6 + (stage - 3)} color="#FFB74D" opacity={0.9} />}
      {stage >= 5 &&
        Array.from({ length: flames }).map((_, i) => (
          <Flame key={i} x={tipX + (i - (flames - 1) / 2) * 5} y={tipY} s={0.6 + 0.12 * (stage - 5)} />
        ))}
      {stage >= 4 && <Embers cx={tipX} cy={tipY - 6} r={10 + (stage - 4) * 1.5} n={4 + (stage - 4)} seed={0.7} />}
      {stage >= 7 &&
        ([-0.5, -0.15, 0.2] as const).map((f, i) => (
          <Flame key={`m${i}`} x={l.headCX + f * l.headR * 1.1} y={l.headCY - l.headR * 0.55} s={0.5 + 0.06 * (stage - 7)} />
        ))}
      {stage >= 9 && <Burst cx={50} cy={l.bodyCY - 2} rx={l.bodyRX + 15} ry={l.bodyRY + 12} n={8} color="#FFE082" />}
    </g>
  );
  return { spec, d: { scale, behind, front, note } };
}

/* -- public renderer + notes --------------------------------------------- */

const DEFAULT_CONFIG: Record<Species, MascotConfig> = {
  unicorn: { species: "unicorn", stage: 0, colors: {}, styles: {}, accessories: [] },
  cat: { species: "cat", stage: 0, colors: {}, styles: {}, accessories: [] },
  fox: { species: "fox", stage: 0, colors: {}, styles: {}, accessories: [] },
};

export const PROPOSAL_NAMES: Record<Species, [string, string]> = {
  unicorn: ["Céleste ✨", "Cristal Royal 👑"],
  cat: ["Lynx Royal 🦁", "Chat Céleste 🌟"],
  fox: ["Kitsune 🦊", "Braise 🔥"],
};

/** Returns just the one-line "what's new this level" caption for a proposal. */
export function proposalNote(species: Species, stage: number, proposal: Proposal): string {
  const l = layoutFor(stage);
  if (species === "unicorn") return unicornDesign(proposal, stage, l, "n").d.note;
  if (species === "cat") return catDesign(proposal, stage, l, "n").d.note;
  return foxDesign(proposal, stage, l, "n").d.note;
}

export function ProposalMascot({ species, stage, proposal, size = 108 }: { species: Species; stage: number; proposal: Proposal; size?: number }) {
  const uid = useId().replace(/:/g, "");
  const layout = layoutFor(stage);
  const config = { ...DEFAULT_CONFIG[species], stage };

  let rig: ReactNode;
  let design: Design;
  if (species === "unicorn") {
    const { spec, d } = unicornDesign(proposal, stage, layout, uid);
    design = d;
    rig = <Unicorn config={config} layout={layout} stage={stage} mood="idle" uid={uid} spec={spec} />;
  } else if (species === "cat") {
    const { spec, d } = catDesign(proposal, stage, layout, uid);
    design = d;
    rig = <Cat config={config} layout={layout} stage={stage} mood="idle" uid={uid} spec={spec} />;
  } else {
    const { spec, d } = foxDesign(proposal, stage, layout, uid);
    design = d;
    rig = <Fox config={config} layout={layout} stage={stage} mood="idle" uid={uid} spec={spec} />;
  }

  const k = design.scale;
  const pivot = layout.feetY;
  const transform = `translate(50 ${pivot}) scale(${k}) translate(-50 ${-pivot})`;

  return (
    <svg viewBox="0 0 100 100" width={size} height={size} className="select-none" style={{ overflow: "visible", display: "block" }}>
      <ellipse cx={50} cy={layout.feetY + 3.5} rx={layout.bodyRX * 0.92} ry={3.6} fill="#000" opacity={0.1} />
      <g transform={transform}>
        {design.behind}
        {rig}
        {design.front}
      </g>
    </svg>
  );
}
