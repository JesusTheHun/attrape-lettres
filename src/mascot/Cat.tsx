import type { RigProps } from "./growth";
import { mix, pick, ramp } from "./growth";
import { accessoryAnchors } from "./anchors";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";
import { Aura, Bow, Cheeks, Crown, Eyes, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Plume, Sparkles, SwimRing, Swimsuit } from "./parts";

/**
 * Cat — "Lynx Royal" timeline. Each stade 3→9 adds a clear, high-contrast beat
 * on the way to a majestic big-cat:
 *  0-2 (untouched) curled newborn → lifting head → first steps
 *  3 cheek tufts · 4 lynx ear-tufts · 5 neck ruff starts
 *  6 growing mane + sparkle · 7 big mane · 8 aura + XL mane + halo
 *  9 lion mane, gold crown, ground glow, sparkle burst.
 * The mane is a DARKER amber with an outline halo so it never melts into the
 * same-coloured head.
 */

type Tuft = "none" | "small" | "big";
interface CSpec {
  /** tail length/bushiness multiplier. */
  tail: number;
  tuft: Tuft;
  aura: number;
  sparkle: number;
  /** lion-mane plume length; undefined/0 = none. */
  mane?: number;
  /** always-on lynx cheek tufts. */
  cheek?: boolean;
  /** head halo opacity 0..1. */
  halo?: number;
  /** royal crown. */
  crown?: boolean;
  /** pool of light under the paws. */
  ground?: boolean;
}

const MANE = "#E0872F";
const MANE_EDGE = "#A85A16";

const STAGES: CSpec[] = [
  { tail: 0.5, tuft: "none", aura: 0, sparkle: 0 }, // 0
  { tail: 0.6, tuft: "none", aura: 0, sparkle: 0 }, // 1
  { tail: 0.8, tuft: "none", aura: 0, sparkle: 0 }, // 2
  { tail: 1.05, tuft: "none", aura: 0, sparkle: 0, cheek: true }, // 3 cheek tufts
  { tail: 1.2, tuft: "small", aura: 0, sparkle: 0, cheek: true }, // 4 lynx ear-tufts
  { tail: 1.4, tuft: "small", aura: 0, sparkle: 0, cheek: true, mane: 5 }, // 5 ruff
  { tail: 1.55, tuft: "small", aura: 0.15, sparkle: 1, cheek: true, mane: 7 }, // 6 mane + sparkle
  { tail: 1.7, tuft: "big", aura: 0.4, sparkle: 2, cheek: true, mane: 9 }, // 7 big mane
  { tail: 1.95, tuft: "big", aura: 0.7, sparkle: 3, cheek: true, mane: 11, halo: 0.6 }, // 8 XL mane + halo
  { tail: 2.4, tuft: "big", aura: 1, sparkle: 6, cheek: true, mane: 14, halo: 0.6, crown: true, ground: true }, // 9 lion king
];

const TUFT_LEN: Record<Tuft, number> = { none: 0, small: 4, big: 7 };

/** Lion mane — a ring of plumes with a darker outline underlay so the spikes
 * read against the same-coloured head. */
function Mane({ cx, cy, r, size }: { cx: number; cy: number; r: number; size: number }) {
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
      {ring(size + 2.2, MANE_EDGE, "e")}
      {ring(size, MANE, "f")}
    </g>
  );
}

export function Cat({ config, layout, stage, mood, uid, preview }: RigProps) {
  const C = COLOR_SLOT.cat;
  const S = STYLE_SLOT.cat;
  const A = ACCESSORY.cat;
  const body = pick(config.colors, C.body, "#F6A96B");
  const belly = pick(config.colors, C.belly, "#FFF3E4");
  const tailCol = pick(config.colors, C.tail, body);
  const fluffy = pick(config.styles, S.hair, "short") === "fluffy";
  const longTail = pick(config.styles, S.tail, "long") === "long";
  const has = (id: string) => config.accessories.includes(id);
  const furEdge = mix(body, "#FFFFFF", 0.34); // lighter fluff so scallops are visible

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: keep the tail (a sold part) but drop mane/tufts/halo/crown/
  // glow so a ghost cat shows ONLY what this tile changes.
  if (preview) spec = { ...spec, tuft: "none", aura: 0, sparkle: 0, mane: undefined, cheek: false, halo: undefined, crown: false, ground: false };
  const tailF = spec.tail * (longTail ? 1 : 0.62);
  const raise = ramp(stage, [[0, 0], [2, 0.1], [4, 0.4], [6, 0.75], [9, 1]]);
  const earScale = ramp(stage, [[0, 0.44], [2, 0.5], [4, 0.58], [6, 0.64], [9, 0.68]]);
  const earPoint = ramp(stage, [[0, 0.6], [3, 0.85], [6, 1], [9, 1]]);
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const anchor = accessoryAnchors("cat", layout);
  const legW = 7;

  // Lush fur-clump tail: BUSHINESS (rB) scales with age; reach stays bounded so
  // the tail curls beside the body instead of flying off-canvas.
  const tx = bodyCX + bodyRX * 0.72;
  const ty = bodyCY + bodyRY * 0.02;
  const cx1 = tx + 13 + 4 * raise;
  const cy1 = ty - (4 + 11 * raise);
  const tipX = tx + 8 + 6 * raise;
  const tipY = ty - (10 + 24 * raise);
  const rB = 3.2 + 4.9 * tailF;
  const bez = (t: number): [number, number] => [
    (1 - t) * (1 - t) * tx + 2 * (1 - t) * t * cx1 + t * t * tipX,
    (1 - t) * (1 - t) * ty + 2 * (1 - t) * t * cy1 + t * t * tipY,
  ];
  const clumps = Array.from({ length: 7 }).map((_, i) => {
    const t = i / 6;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.62 + 0.5 * Math.sin(Math.PI * (0.16 + 0.8 * t))) };
  });

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#FFE29A" opacity={0.85} />}
      {spec.aura > 0 && <Aura id={`${uid}-tailglow`} cx={tipX} cy={tipY} r={rB * 1.5} color="#FFE6B0" opacity={spec.aura} />}

      {/* lying baby rests ON its swim ring (drawn behind), face clear of the tube */}
      {!layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ring`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 1.15} color="#FF6B6B" />}

      {/* tail behind body — core + fur clumps + fluffy tip */}
      <path d={`M${tx} ${ty} Q${cx1} ${cy1} ${tipX} ${tipY}`} fill="none" stroke={tailCol} strokeWidth={rB * 0.7} strokeLinecap="round" />
      {clumps.map((b, i) => <circle key={`tc${i}`} cx={b.px} cy={b.py} r={b.r} fill={tailCol} />)}
      {fluffy && clumps.filter((_, i) => i % 2 === 1).map((b, i) => <circle key={`tf${i}`} cx={b.px - b.r * 0.4} cy={b.py - b.r * 0.4} r={b.r * 0.5} fill={furEdge} />)}
      <Plume x={tipX} y={tipY} color={tailCol} len={5 + 5 * tailF} wide={4 + 4 * tailF} rot={-12} n={4} />

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={body} />
      ))}

      {/* swim ring, back half — behind the body so the pet sits IN the tube */}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringb`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FF6B6B" part="back" />}

      {/* fluffy: lighter scalloped fur halo breaking the body outline */}
      {fluffy &&
        Array.from({ length: 16 }).map((_, i) => {
          const a = (i / 16) * Math.PI * 2;
          return <circle key={i} cx={bodyCX + Math.cos(a) * bodyRX * 1.02} cy={bodyCY + Math.sin(a) * bodyRY * 1.02} r={5.4} fill={furEdge} />;
        })}

      {/* body */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.28} rx={bodyRX * 0.6} ry={bodyRY * 0.62} fill={belly} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={belly} />}
      {/* lying culotte sits on the rump, UNDER the resting head/neck */}
      {!layout.standing && has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#4FC3F7" lying />}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={body} />
      ))}

      {/* halo + lion mane (behind the head) */}
      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} />}
      {spec.mane && <Mane cx={headCX} cy={headCY} r={headR * 1.02} size={spec.mane} />}

      {/* fluffy: lighter fur halo around the head */}
      {fluffy &&
        Array.from({ length: 12 }).map((_, i) => {
          const a = (i / 12) * Math.PI * 2;
          return <circle key={`hf${i}`} cx={headCX + Math.cos(a) * headR * 1.0} cy={headCY + Math.sin(a) * headR * 1.0} r={4.6} fill={furEdge} />;
        })}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR} fill={body} />

      {/* ears — grow & sharpen with age; rounded tip for babies */}
      {([-1, 1] as const).map((d) => {
        const ex = headCX + d * headR * 0.62;
        const ey = headCY - headR * 0.58;
        const th = headR * earScale;
        const tipYe = ey - th;
        const tipXe = ex + d * th * 0.35;
        return (
          <g key={d}>
            <path d={`M${ex - d * headR * 0.02} ${ey} Q${tipXe - d * th * 0.1} ${tipYe} ${tipXe} ${tipYe + th * (1 - earPoint) * 0.2} Q${tipXe + d * th * 0.05} ${tipYe + 2} ${ex - d * headR * 0.6} ${ey - headR * 0.02} Z`} fill={body} />
            <path d={`M${ex} ${ey - 1} L${tipXe - d * th * 0.05} ${tipYe + th * 0.28} L${ex - d * headR * 0.34} ${ey - headR * 0.02} Z`} fill="#FF9AA2" />
            {TUFT_LEN[spec.tuft] > 0 && <Plume x={tipXe} y={tipYe + 1} color={body} len={TUFT_LEN[spec.tuft]} wide={2} rot={d * 12} n={2} />}
          </g>
        );
      })}

      {/* fluffy cheeks + head tuft (lighter, unmistakable) */}
      {fluffy && (
        <g>
          {([-1, 1] as const).map((d) => (
            <Plume key={d} x={headCX + d * headR * 0.92} y={headCY + headR * 0.2} color={furEdge} len={11} wide={7} rot={d * 74} n={4} />
          ))}
          <Plume x={headCX} y={headCY - headR * 0.92} color={furEdge} len={8} wide={7} rot={0} n={3} />
        </g>
      )}

      {/* lynx cheek tufts (light, always-on from stade 3) */}
      {spec.cheek &&
        ([-1, 1] as const).map((d) => (
          <Plume key={d} x={headCX + d * headR * 0.9} y={headCY + headR * 0.24} color="#FFE0C4" len={7} wide={5} rot={d * 72} n={3} />
        ))}

      {/* face */}
      <Eyes cx={headCX} y={headCY} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.36} dx={headR * 0.58} r={headR * 0.14} />
      <path d={`M${headCX - 2.4} ${headCY + headR * 0.3} L${headCX + 2.4} ${headCY + headR * 0.3} L${headCX} ${headCY + headR * 0.44} Z`} fill="#FF7C93" />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.15} mood={mood} />
      {([-1, 1] as const).map((d) => (
        <g key={d} stroke="#B79A82" strokeWidth={0.8} strokeLinecap="round">
          <line x1={headCX + d * headR * 0.35} y1={headCY + headR * 0.4} x2={headCX + d * headR} y2={headCY + headR * 0.3} />
          <line x1={headCX + d * headR * 0.35} y1={headCY + headR * 0.48} x2={headCX + d * headR} y2={headCY + headR * 0.5} />
        </g>
      ))}

      {/* royal crown (majestic) */}
      {spec.crown && <Crown cx={headCX} cy={headCY - headR * 0.5} r={headR * 0.95} band="#FFD54F" gem="#E0533B" />}

      {/* accessories — placement from the per-species/per-stage anchor resolver */}
      {/* swim set — grows with its wearer: rump culotte on the newborn (drawn
          earlier, under the head) → striped suit → star badge (6+); the ring's
          front half closes the tube around the waist, duck head at 7+. */}
      {layout.standing && has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#4FC3F7" star={stage >= 6} />}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringf`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FF6B6B" part="front" duck={stage >= 7} />}
      {has(A.bellCollar) && (
        <g>
          <path d={`M${anchor.neck.x - anchor.neck.w} ${anchor.neck.y} Q${anchor.neck.x} ${anchor.neck.y + 6} ${anchor.neck.x + anchor.neck.w} ${anchor.neck.y}`} fill="none" stroke="#EF6F6C" strokeWidth={3.4} strokeLinecap="round" />
          <circle cx={anchor.neck.x} cy={anchor.neck.y + 4} r={3} fill="#FFD54F" />
          <circle cx={anchor.neck.x} cy={anchor.neck.y + 4} r={0.9} fill="#B98A22" />
        </g>
      )}
      {has(A.bow) && <Bow x={anchor.headTop.x - headR * 0.5} y={anchor.headTop.y + headR * 0.18} s={0.95} color="#FF7EA8" />}
      {has(A.partyHat) && (
        <g>
          <path d={`M${headCX} ${headCY - headR * 1.7} L${headCX - headR * 0.5} ${headCY - headR * 0.8} L${headCX + headR * 0.5} ${headCY - headR * 0.8} Z`} fill="#4FC3F7" />
          <path d={`M${headCX - headR * 0.18} ${headCY - headR * 1.2} L${headCX + headR * 0.26} ${headCY - headR * 1.1} L${headCX + headR * 0.2} ${headCY - headR * 0.87} L${headCX - headR * 0.24} ${headCY - headR * 0.95} Z`} fill="#FFD54F" />
          <circle cx={headCX} cy={headCY - headR * 1.73} r={2.6} fill="#FF8A65" />
        </g>
      )}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 0.5;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
