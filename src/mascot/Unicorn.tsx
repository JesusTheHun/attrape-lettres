import type { RigProps } from "./growth";
import { INK, pick } from "./growth";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";
import { Aura, Bow, Cheeks, Crown, Eyes, Flower, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Plume, Sparkles, fourStar } from "./parts";

/**
 * Unicorn — "Céleste" timeline. Every stade 3→9 adds ONE clearly visible,
 * high-contrast beat so a child sees the reward at each level:
 *  0-2 (untouched) folded newborn → lifting head → horn nub
 *  3 real horn + outlined sky-blue wings open · 4 wings spread wide
 *  5 flowing mane crest + first sparkle · 6 glowing halo
 *  7 star crown · 8 rainbow mane + shining horn
 *  9 majestic alicorn: huge starry wings, halo, crown, rainbow mane, horn beam,
 *    ground glow, sparkle burst.
 * Wings are OUTLINED (see Wings) so they never vanish into the pale body.
 */

interface USpec {
  /** horn height in viewBox units; 0 = hornless. */
  horn: number;
  shine: boolean;
  /** wing scale; 0 = none. */
  wing: number;
  /** 0..1 magical glow. */
  aura: number;
  sparkle: number;
  /** flowing mane crest length (× headR); undefined = base mane only. */
  maneFlow?: number;
  /** multi-colour flowing mane (supersedes maneFlow). */
  rainbow?: boolean;
  /** head halo opacity 0..1. */
  halo?: number;
  /** star crown on the head. */
  crown?: boolean;
  /** light beam + sparkle burst from the horn tip. */
  beam?: boolean;
  /** magical pool of light under the hooves. */
  ground?: boolean;
}

const RAINBOW = ["#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8", "#BA9EE8"];

const STAGES: USpec[] = [
  { horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0 }, // 0 newborn, hornless
  { horn: 0, shine: false, wing: 0, aura: 0, sparkle: 0 }, // 1 lifting head, hornless
  { horn: 3, shine: false, wing: 0, aura: 0, sparkle: 0 }, // 2 nub
  { horn: 9, shine: false, wing: 0.8, aura: 0, sparkle: 0 }, // 3 horn + wings open
  { horn: 12, shine: false, wing: 1.1, aura: 0.1, sparkle: 0 }, // 4 wings spread
  { horn: 15, shine: false, wing: 1.3, aura: 0.25, sparkle: 1, maneFlow: 1.15 }, // 5 mane crest
  { horn: 17, shine: false, wing: 1.5, aura: 0.4, sparkle: 2, maneFlow: 1.43, halo: 0.5 }, // 6 halo
  { horn: 19, shine: false, wing: 1.7, aura: 0.6, sparkle: 3, maneFlow: 1.7, halo: 0.5, crown: true }, // 7 crown
  { horn: 21, shine: true, wing: 1.9, aura: 0.85, sparkle: 4, halo: 0.9, crown: true, rainbow: true }, // 8 rainbow
  { horn: 26, shine: true, wing: 2.2, aura: 1, sparkle: 7, halo: 0.9, crown: true, rainbow: true, beam: true, ground: true }, // 9 majestic
];

function Horn({ h, x, baseY, color, spiral, shineId }: { h: number; x: number; baseY: number; color: string; spiral: boolean; shineId?: string }) {
  if (h <= 0) return null;
  const apexY = baseY - h;
  const hw = 2 + h * 0.15;
  const fill = shineId ? `url(#${shineId})` : color;
  const rings = Math.max(1, Math.round(h / 4));
  return (
    <g>
      {shineId && (
        <defs>
          <linearGradient id={shineId} x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stopColor={color} />
            <stop offset="50%" stopColor="#FFF6C4" />
            <stop offset="100%" stopColor={color} />
          </linearGradient>
        </defs>
      )}
      <path d={`M${x} ${apexY} L${x - hw} ${baseY} Q${x} ${baseY + hw * 0.6} ${x + hw} ${baseY} Z`} fill={fill} />
      {spiral &&
        Array.from({ length: rings }).map((_, i) => {
          const t = (i + 0.5) / rings;
          const yy = baseY - h * t;
          const ww = hw * (1 - t) + 0.4;
          return <line key={i} x1={x - ww} y1={yy + 1.3} x2={x + ww} y2={yy - 1.3} stroke={INK} strokeWidth={0.8} opacity={0.45} strokeLinecap="round" />;
        })}
    </g>
  );
}

/** Celestial wings — saturated sky-blue with a real outline + feather lines, so
 * they stay legible against the pale body (a small pale wing is invisible). */
function Wings({ s, cx, cy, starry }: { s: number; cx: number; cy: number; starry: boolean }) {
  if (s <= 0) return null;
  const wing = (dir: number, key: string) => (
    <g key={key} transform={`translate(${cx + dir * 6} ${cy}) scale(${dir * s} ${s})`}>
      <path d="M0 0 Q18 -24 34 -13 Q25 -6 30 4 Q19 -2 23 11 Q13 3 15 15 Q7 6 6 17 Q1 8 0 0 Z" fill="#BFE3FF" stroke="#5AA9E0" strokeWidth={1.3} strokeLinejoin="round" />
      <path d="M4 2 Q15 -14 28 -9" fill="none" stroke="#5AA9E0" strokeWidth={0.9} opacity={0.7} />
      <path d="M3 7 Q13 -1 24 2" fill="none" stroke="#5AA9E0" strokeWidth={0.8} opacity={0.55} />
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

/** Flowing tail attached to the rump. Straight = smooth swish, curly = coiled. */
function UnicornTail({ x, y, len, color, curly }: { x: number; y: number; len: number; color: string; curly: boolean }) {
  return (
    <g>
      {/* wedge blending into the rump so the tail reads attached */}
      <path d={`M${x + 8} ${y - 7} Q${x - 3} ${y + 2} ${x - 2} ${y + 11} L${x + 9} ${y + 5} Z`} fill={color} />
      <Plume x={x} y={y} color={color} len={len} wide={9} rot={-24} n={3} wave={curly} />
      {curly ? (
        <g fill="none" stroke={color} strokeWidth={4.2} strokeLinecap="round">
          <path d={`M${x - len * 0.28} ${y + len * 0.62} q -8 3 -6 10 q 2 6 -4 8 q -6 2 -2 8`} />
        </g>
      ) : (
        <g fill="none" stroke="#ffffff" strokeWidth={1} opacity={0.45} strokeLinecap="round">
          <path d={`M${x - 2} ${y + 3} q -5 ${len * 0.4} -8 ${len * 0.7}`} />
        </g>
      )}
    </g>
  );
}

export function Unicorn({ config, layout, stage, mood, uid }: RigProps) {
  const C = COLOR_SLOT.unicorn;
  const S = STYLE_SLOT.unicorn;
  const A = ACCESSORY.unicorn;
  const body = pick(config.colors, C.body, "#F5ECFF");
  const hornCol = pick(config.colors, C.horn, "#FFD54F");
  const maneCol = pick(config.colors, C.mane, "#BA9EE8");
  const tailCol = pick(config.colors, C.tail, "#F49AC2");
  const curlyTail = pick(config.styles, S.tail, "straight") === "curly";
  const spiralHorn = pick(config.styles, S.horn, "smooth") === "spiral";
  const has = (id: string) => config.accessories.includes(id);
  const earIn = "#FBE4F1";

  const spec = STAGES[Math.max(0, Math.min(9, stage))];
  const hoof = "#EADAC6";
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, neckX, neckY, eyeR } = layout;
  const hornBaseY = headCY - headR * 0.72;
  const apexY = hornBaseY - spec.horn;
  const legW = layout.pose === "proud" ? 6.5 : 7;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 20} color="#FFE29A" opacity={0.9} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 24} color="#FFE29A" opacity={spec.aura} />}

      <Wings s={spec.wing} cx={bodyCX} cy={bodyCY - bodyRY * 0.28} starry={spec.wing >= 2} />

      {/* tail attached at rump, behind body */}
      <UnicornTail x={bodyCX - bodyRX * 0.5} y={bodyCY - bodyRY * 0.12} len={bodyRY * 1.6} color={tailCol} curly={curlyTail} />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={hoof} />
      ))}

      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={hoof} />}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.9} strokeLinecap="round" />

      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={hoof} />
      ))}

      {/* halo (glowing ring behind the head) */}
      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} />}

      {/* mane behind head + top crest */}
      <Plume x={headCX + headR * 0.22} y={headCY - headR * 0.05} color={maneCol} len={headR * 1.2} wide={headR * 0.65} rot={22} n={4} />
      {spec.horn > 0 && <Plume x={headCX - headR * 0.15} y={headCY - headR * 0.85} color={maneCol} len={headR * 0.55} wide={headR * 0.35} rot={-6} n={2} />}
      {/* flowing mane crest → rainbow at the top stages */}
      {spec.rainbow
        ? RAINBOW.map((c, i) => (
            <Plume key={i} x={headCX + headR * 0.24} y={headCY - headR * 0.08 + (i / 4) * headR} color={c} len={headR * 1.5} wide={headR * 0.5} rot={26 + i * 5} n={3} />
          ))
        : spec.maneFlow && (
            <Plume x={headCX + headR * 0.24} y={headCY - headR * 0.08} color={maneCol} len={headR * spec.maneFlow} wide={headR * 0.62} rot={24} n={4} />
          )}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.98} fill={body} />

      {/* rounded ears (never horn-like) */}
      {([-1, 1] as const).map((d) => {
        const ex = headCX + d * headR * 0.6;
        const ey = headCY - headR * 0.58;
        return (
          <g key={d} transform={`rotate(${d * 22} ${ex} ${ey})`}>
            <ellipse cx={ex} cy={ey} rx={headR * 0.16} ry={headR * 0.3} fill={body} />
            <ellipse cx={ex} cy={ey + headR * 0.04} rx={headR * 0.08} ry={headR * 0.18} fill={earIn} />
          </g>
        );
      })}

      {/* forelock — two symmetric locks framing the face, clear of the eyes */}
      <Plume x={headCX - headR * 0.24} y={headCY - headR * 0.6} color={maneCol} len={headR * 0.42} wide={4} rot={-20} n={2} />
      <Plume x={headCX + headR * 0.24} y={headCY - headR * 0.6} color={maneCol} len={headR * 0.42} wide={4} rot={20} n={2} />

      {/* horn (on top, centred between the forelock tufts) */}
      <Horn h={spec.horn} x={headCX} baseY={hornBaseY} color={hornCol} spiral={spiralHorn} shineId={spec.shine ? `${uid}-horn` : undefined} />
      {spec.shine && <Sparkles points={[[headCX, apexY - 1.5, 3]]} />}
      {/* horn light beam (majestic) */}
      {spec.beam && (
        <g>
          <path d={`M${headCX - 5} ${apexY} L${headCX} ${apexY - 26} L${headCX + 5} ${apexY} Z`} fill="#FFF6C4" opacity={0.5} />
          <path d={fourStar(headCX, apexY - 2, 4.2)} fill="#FFF3C4" />
        </g>
      )}

      {/* star crown */}
      {spec.crown && <Crown cx={headCX} cy={headCY - headR * 0.5} r={headR * 0.95} band={hornCol} gem="#FF7EA8" />}

      {/* face */}
      <Eyes cx={headCX} y={headCY + headR * 0.06} dx={headR * 0.42} r={eyeR} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.36} dx={headR * 0.62} r={headR * 0.15} />
      <Mouth cx={headCX} y={headCY + headR * 0.5} w={headR * 0.16} mood={mood} />

      {/* hoof aura (majestic) */}
      {spec.aura > 0.4 && layout.legs.map((l, i) => <Aura key={`ha${i}`} id={`${uid}-hoof-${i}`} cx={l.footX} cy={l.footY} r={8} color="#FFF0B8" opacity={spec.aura} />)}

      {/* accessories */}
      {has(A.ribbon) && <Bow x={neckX} y={neckY} s={1.15} color="#FF7EA8" />}
      {has(A.flowerCrown) &&
        [-62, -31, 0, 31, 62].map((deg, i) => {
          const rad = ((deg - 90) * Math.PI) / 180;
          const fx = headCX + Math.cos(rad) * headR * 0.98;
          const fy = headCY + Math.sin(rad) * headR * 0.98;
          const palette = ["#FF8FB1", "#FFD54F", "#AED581", "#7FD1D8", "#BA9EE8"];
          return <Flower key={i} x={fx} y={fy} r={3.6} petal={palette[i]} center="#FFF3C4" />;
        })}
      {has(A.starClip) && (
        <g>
          <Sparkles points={[[headCX - headR * 0.72, headCY - headR * 0.52, 6.5]]} color="#FFD54F" />
          <Sparkles points={[[headCX - headR * 0.42, headCY - headR * 0.78, 3]]} color="#FFF3C4" />
        </g>
      )}

      {/* sparkles (proud/majestic) */}
      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2;
            return [bodyCX + Math.cos(a) * (bodyRX + 15), bodyCY - 4 + Math.sin(a) * (bodyRY + 12), 1.8 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
