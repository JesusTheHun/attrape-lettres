import type { RigProps } from "./growth";
import { INK, pick, ramp } from "./growth";
import { accessoryAnchors } from "./anchors";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Plume, Sparkles, SwimRing, Swimsuit, fourStar } from "./parts";

/**
 * Fox — "Kitsune" timeline. Each stade 3→9 sprouts a clearly visible beat on
 * the way to a many-tailed fire-spirit:
 *  0-2 (untouched) curled kit → lifting head → first steps
 *  3 ear-tufts + ruff · 4 a 2nd tail · 5 a 3rd tail
 *  6 sparkle + flame-tipped tails · 7 five tails · 8 forehead mark + halo
 *  9 seven glowing tails, flame tips, halo, ground glow, sparkle burst.
 * The extra tails are a DEEPER orange with a dark outline + cream tips, so they
 * separate from the same-coloured body instead of merging into one mass.
 */

interface FSpec {
  tail: number;
  ruff: number;
  tuft: boolean;
  aura: number;
  sparkle: number;
  /** number of extra fanned kitsune tails (on top of the base tail). */
  extraTails?: number;
  /** flame tips on the fanned tails. */
  flames?: boolean;
  /** forehead spirit-mark. */
  mark?: boolean;
  /** head halo opacity 0..1. */
  halo?: number;
  /** pool of light under the paws. */
  ground?: boolean;
}

const TAIL_DEEP = "#F26B3C";
const TAIL_EDGE = "#B8431C";
const TAIL_TIP = "#FFF6EE";

const STAGES: FSpec[] = [
  { tail: 0.55, ruff: 0.4, tuft: false, aura: 0, sparkle: 0 }, // 0
  { tail: 0.65, ruff: 0.45, tuft: false, aura: 0, sparkle: 0 }, // 1
  { tail: 0.8, ruff: 0.55, tuft: false, aura: 0, sparkle: 0 }, // 2
  { tail: 0.95, ruff: 0.7, tuft: true, aura: 0, sparkle: 0 }, // 3 ear tufts + ruff
  { tail: 1.05, ruff: 0.8, tuft: true, aura: 0, sparkle: 0, extraTails: 1 }, // 4 2nd tail
  { tail: 1.15, ruff: 0.9, tuft: true, aura: 0, sparkle: 0, extraTails: 2 }, // 5 3 tails
  { tail: 1.25, ruff: 1.0, tuft: true, aura: 0.2, sparkle: 1, extraTails: 2, flames: true }, // 6 flame tips
  { tail: 1.4, ruff: 1.1, tuft: true, aura: 0.4, sparkle: 2, extraTails: 4, flames: true }, // 7 5 tails
  { tail: 1.55, ruff: 1.2, tuft: true, aura: 0.7, sparkle: 3, extraTails: 4, flames: true, mark: true, halo: 0.55 }, // 8
  { tail: 1.8, ruff: 1.3, tuft: true, aura: 1, sparkle: 6, extraTails: 6, flames: true, mark: true, halo: 0.55, ground: true }, // 9 kitsune
];

/** Small two-tone flame. */
function Flame({ x, y, s }: { x: number; y: number; s: number }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${s})`}>
      <path d="M0 0 C-6 -6 -5 -15 0 -22 C5 -15 6 -6 0 0 Z" fill="#FF7043" />
      <path d="M0 -3 C-3 -7 -3 -13 0 -17 C3 -13 3 -7 0 -3 Z" fill="#FFE082" />
    </g>
  );
}

/** One fanned kitsune tail — a bushy fur-clump curve with an outline underlay
 * + a deeper fill + cream tip, so it reads apart from the body and its siblings. */
function FanTail({ rootX, rootY, angleDeg, length, rB, flame, ki }: { rootX: number; rootY: number; angleDeg: number; length: number; rB: number; flame: boolean; ki: number }) {
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
      {clumps.map((b, i) => (
        <circle key={`o${i}`} cx={b.px} cy={b.py} r={b.r + 1.1} fill={TAIL_EDGE} />
      ))}
      {clumps.map((b, i) => (
        <circle key={i} cx={b.px} cy={b.py} r={b.r} fill={b.tip ? TAIL_TIP : TAIL_DEEP} />
      ))}
      {flame && <Flame x={tx} y={ty} s={0.6} />}
    </g>
  );
}

export function Fox({ config, layout, stage, mood, uid, preview }: RigProps) {
  const C = COLOR_SLOT.fox;
  const S = STYLE_SLOT.fox;
  const A = ACCESSORY.fox;
  const body = pick(config.colors, C.body, "#FF8A65");
  // Default belly is pure white so the warm "Ventre crème" option reads clearly.
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const tailTip = pick(config.colors, C.tailTip, "#FFFFFF");
  const pattern = pick(config.styles, S.fur, "plain");
  const longTail = pick(config.styles, S.tail, "long") === "long";
  const has = (id: string) => config.accessories.includes(id);

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: keep the base tail + ruff (sold parts) but drop the extra
  // kitsune tails, flames, spirit-mark, halo and glow so a ghost fox shows ONLY
  // what this tile changes.
  if (preview) spec = { ...spec, tuft: false, aura: 0, sparkle: 0, extraTails: 0, flames: false, mark: false, halo: undefined, ground: false };
  const tailF = spec.tail * (longTail ? 1 : 0.66);
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const anchor = accessoryAnchors("fox", layout);
  const legW = 7;

  // Bushy tail = fur clumps along a curve; it tapers to a pointed TIP whose fur
  // clumps carry the recolour, so the tint follows the tail silhouette to a point.
  const rootX = bodyCX + bodyRX * 0.55;
  const rootY = bodyCY - bodyRY * 0.22;
  const ctrlX = bodyCX + bodyRX * 1.28;
  const ctrlY = bodyCY + bodyRY * 0.18;
  const endX = bodyCX + bodyRX * 0.92;
  const endY = bodyCY + bodyRY * 0.98;
  const rB = 5 + 6 * tailF;
  const bez = (t: number): [number, number] => [
    (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * ctrlX + t * t * endX,
    (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * ctrlY + t * t * endY,
  ];
  const bodyClumps = Array.from({ length: 6 }).map((_, i) => {
    const t = (i / 5) * 0.72;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.75 + 0.35 * Math.sin(Math.PI * (t / 0.72))) };
  });
  const tipClumps = Array.from({ length: 4 }).map((_, i) => {
    const t = 0.76 + (i / 3) * 0.24;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.62 - 0.36 * (i / 3)) };
  });

  const extra = spec.extraTails ?? 0;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#FFD59A" opacity={0.85} />}

      {/* lying baby rests ON its swim ring (drawn behind), face clear of the tube */}
      {!layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ring`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 1.15} color="#FFD54F" />}

      {/* extra kitsune tails, fanned symmetrically behind the body */}
      {Array.from({ length: extra }).map((_, i) => {
        const spread = extra === 1 ? 0 : i / (extra - 1) - 0.5;
        return (
          <FanTail
            key={i}
            ki={i}
            rootX={bodyCX}
            rootY={bodyCY + bodyRY * 0.02}
            angleDeg={270 + spread * 132}
            length={bodyRX * (1.12 + 0.42 * tailF)}
            rB={4 + 3 * tailF}
            flame={spec.flames === true}
          />
        );
      })}

      {/* bushy tail behind body; recolourable tapering tip */}
      {spec.aura > 0 && <Aura id={`${uid}-tail`} cx={endX} cy={endY} r={rB * 1.3} color="#FFF1C4" opacity={spec.aura} />}
      {bodyClumps.map((b, i) => <circle key={`bu${i}`} cx={b.px} cy={b.py} r={b.r} fill={body} />)}
      {tipClumps.map((b, i) => <circle key={`tp${i}`} cx={b.px} cy={b.py} r={b.r} fill={tailTip} />)}

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      {/* swim ring, back half — behind the body so the pet sits IN the tube */}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringb`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="back" />}

      {/* body */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />

      {/* fur pattern (clearly visible) */}
      {pattern === "spots" &&
        [
          [-0.42, -0.28],
          [0.28, -0.4],
          [0.02, -0.05],
          [-0.15, 0.28],
          [0.36, 0.05],
        ].map(([dx, dy], i) => (
          <ellipse key={i} cx={bodyCX + dx * bodyRX} cy={bodyCY + dy * bodyRY} rx={3.2} ry={3.2} fill="#A24A2C" opacity={0.85} />
        ))}
      {pattern === "stripes" &&
        [-0.4, -0.05, 0.3].map((dy, i) => (
          <path key={i} d={`M${bodyCX - bodyRX * 0.72} ${bodyCY + dy * bodyRY} Q${bodyCX} ${bodyCY + dy * bodyRY - 5} ${bodyCX + bodyRX * 0.72} ${bodyCY + dy * bodyRY}`} fill="none" stroke="#8A4326" strokeWidth={2.6} opacity={0.4} strokeLinecap="round" />
        ))}

      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={INK} />}
      {/* lying culotte sits on the rump, UNDER the resting head/neck */}
      {!layout.standing && has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#5AA9E0" lying />}

      {/* neck */}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      {/* chest ruff */}
      <path d={`M${headCX - headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Q${headCX} ${headCY + headR * 1.5 * spec.ruff} ${headCX + headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Z`} fill={belly} />

      {/* halo (behind the head) */}
      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} color="#FFD59A" />}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      {/* ears — tall but ROUNDED tips, soft dark tip */}
      {([-1, 1] as const).map((d) => {
        const ex = headCX + d * headR * 0.58;
        const ey = headCY - headR * 0.5;
        const th = headR * ramp(stage, [[0, 0.55], [4, 0.72], [9, 0.78]]);
        const tipX = ex + d * th * 0.32;
        const tipY = ey - th;
        return (
          <g key={d}>
            <path d={`M${ex - d * headR * 0.5} ${ey} Q${tipX - d * th * 0.15} ${tipY - 2} ${tipX + d * 1.5} ${tipY + th * 0.14} Q${tipX + d * th * 0.12} ${tipY + th * 0.1} ${ex + d * headR * 0.05} ${ey - headR * 0.05} Z`} fill={body} />
            <path d={`M${tipX} ${tipY + th * 0.05} Q${tipX + d * th * 0.14} ${tipY + th * 0.12} ${tipX + d * 1} ${tipY + th * 0.3} Q${tipX - d * th * 0.06} ${tipY + th * 0.24} ${tipX} ${tipY + th * 0.05} Z`} fill={INK} />
            {spec.tuft && <Plume x={ex - d * headR * 0.02} y={ey - headR * 0.02} color={belly} len={5} wide={3} rot={d * 10} n={2} />}
          </g>
        );
      })}

      {/* white cheek ruff (grows) */}
      {([-1, 1] as const).map((d) => (
        <Plume key={d} x={headCX + d * headR * 0.72} y={headCY + headR * 0.3} color={belly} len={6 + 5 * spec.ruff} wide={5} rot={d * 60} n={3} />
      ))}

      {/* snout */}
      <ellipse cx={headCX} cy={headCY + headR * 0.42} rx={headR * 0.42} ry={headR * 0.32} fill={belly} />
      <ellipse cx={headCX} cy={headCY + headR * 0.3} rx={2.4} ry={2} fill={INK} />

      {/* face — softened, rounder eyes */}
      <Eyes cx={headCX} y={headCY - headR * 0.02} dx={headR * 0.4} r={eyeR * 1.05} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.5} w={headR * 0.14} mood={mood} />

      {/* forehead spirit-mark (kitsune) */}
      {spec.mark && <path d={fourStar(headCX, headCY - headR * 0.55, 2.6)} fill="#FFF3C4" />}

      {/* accessories — placement from the per-species/per-stage anchor resolver */}
      {/* swim set — grows with its wearer: rump culotte on the newborn (drawn
          earlier, under the head) → striped suit → star badge (6+); the ring's
          front half closes the tube around the waist, duck head at 7+. */}
      {layout.standing && has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#5AA9E0" star={stage >= 6} />}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringf`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="front" duck={stage >= 7} />}
      {has(A.scarf) && (
        <g>
          <path d={`M${anchor.neck.x - anchor.neck.w} ${anchor.neck.y} Q${anchor.neck.x} ${anchor.neck.y + 7} ${anchor.neck.x + anchor.neck.w} ${anchor.neck.y}`} fill="none" stroke="#66BB6A" strokeWidth={5} strokeLinecap="round" />
          <path d={`M${anchor.neck.x + anchor.neck.w * 0.5} ${anchor.neck.y + 2} l3 10 l-6 1 Z`} fill="#4FA84E" />
        </g>
      )}
      {has(A.beanie) && (
        <g>
          <path d={`M${headCX - headR} ${headCY - headR * 0.5} Q${headCX} ${headCY - headR * 1.7} ${headCX + headR} ${headCY - headR * 0.5} Z`} fill="#4FC3F7" />
          <rect x={headCX - headR} y={headCY - headR * 0.62} width={headR * 2} height={headR * 0.3} rx={headR * 0.15} fill="#2E9BD6" />
          <circle cx={headCX} cy={headCY - headR * 1.55} r={3.4} fill="#FFF3E6" />
        </g>
      )}
      {has(A.boots) &&
        anchor.feet.map((l, i) => (
          <g key={i}>
            <rect x={l.footX - 4} y={l.footY - 6} width={8} height={8} rx={3} fill="#8D5A3B" />
            <rect x={l.footX - 5} y={l.footY - 7} width={10} height={3} rx={1.5} fill="#C98A5B" />
          </g>
        ))}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 1;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
