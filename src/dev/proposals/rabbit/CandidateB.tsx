import { INK, mix, pick } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, Flower, FoldedLegs, GroundGlow, Leg, Mouth, Plume, Sparkles } from "../../../mascot/parts";
import { P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import { BunnyNose, Butterfly, Clover, Ear, FallingPetals, PetalRing, Pompon, WhiskerDots, type PRigProps } from "./rabbitParts";

/**
 * Candidate B — « Trèfle », le lapin porte-bonheur (lop ears!).
 * Growth arc: sleepy lop kit → a spring luck-spirit that makes gardens bloom.
 *  0-2 (untouched) curled kit, long ears on the ground → head up → first hops
 *  3 a lucky clover sprouts on its head · 4 a peach blossom on one ear tip
 *  5 petal ruff collar · 6 butterfly companions · 7 a grown flower crown
 *  (the clover at its centre) · 8 falling petals + soft aura
 *  9 the meadow blooms under its feet, ring of petals, full aura.
 */

interface BSpec {
  earL: number;
  pompon: number;
  sprout?: boolean;
  blossom?: boolean;
  ruff?: boolean;
  butterflies?: number;
  crown?: boolean;
  petals?: boolean;
  aura?: number;
  meadow?: boolean;
  burst?: boolean;
  sparkle?: number;
}

const STAGES: BSpec[] = [
  { earL: 0.95, pompon: 0.75 }, // 0
  { earL: 1.05, pompon: 0.8 }, // 1
  { earL: 1.15, pompon: 0.85 }, // 2
  { earL: 1.24, pompon: 0.9, sprout: true }, // 3 clover sprout
  { earL: 1.32, pompon: 0.95, sprout: true, blossom: true }, // 4 ear blossom
  { earL: 1.4, pompon: 1.0, sprout: true, blossom: true, ruff: true }, // 5 petal ruff
  { earL: 1.48, pompon: 1.05, sprout: true, blossom: true, ruff: true, butterflies: 2 }, // 6 butterflies
  { earL: 1.56, pompon: 1.1, blossom: true, ruff: true, butterflies: 2, crown: true }, // 7 flower crown
  { earL: 1.64, pompon: 1.15, blossom: true, ruff: true, butterflies: 2, crown: true, petals: true, aura: 0.35, sparkle: 2 }, // 8 petal fall
  { earL: 1.78, pompon: 1.25, blossom: true, ruff: true, butterflies: 3, crown: true, petals: true, aura: 0.8, meadow: true, burst: true, sparkle: 3 }, // 9 meadow
];

const CLOVER = "#7BB661";
const CLOVER_DARK = "#5E9948";
const BLOSSOM = "#FFB4A2";
const BLOSSOM_SOFT = "#FFD9C9";
const HONEY = "#FFE082";

export function CandidateB({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.rabbit;
  const S = P_STYLE_SLOT.rabbit;
  const body = pick(config.colors, C.body, "#E7F1DB");
  const belly = pick(config.colors, C.belly, "#FFF9EE");
  const inner = pick(config.colors, C.inner, "#F6CDBB");
  const oneUp = pick(config.styles, S.ear, "tombantes") === "une-levee";
  const bigTail = pick(config.styles, S.tail, "grand") === "grand";

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the free garden magic; keep the lop ears + pompon.
  if (preview) spec = { earL: spec.earL, pompon: spec.pompon };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR, feetY } = layout;
  const tailEdge = mix(body, INK, 0.18);
  const pom = spec.pompon * (bigTail ? 1 : 0.7);
  const legW = 7;
  const earW = headR * 0.46;
  const earL = headR * spec.earL;
  const hoof = mix(body, INK, 0.3);

  // Lop ears hanging beside the face when standing (drawn OVER the head so the
  // inner ear faces the viewer — the lop signature); flopped on the ground when
  // lying. Style variant lifts the right ear upright ("une oreille levée").
  const ears = layout.standing
    ? [
        { bx: headCX - headR * 0.68, by: headCY - headR * 0.42, rot: -160, k: 1 },
        { bx: headCX + headR * 0.68, by: headCY - headR * 0.42, rot: oneUp ? 14 : 160, k: oneUp ? 0.8 : 1 },
      ]
    : [
        { bx: headCX - headR * 0.62, by: headCY - headR * 0.32, rot: -128, k: 0.95 },
        { bx: headCX - headR * 0.22, by: headCY - headR * 0.5, rot: -100, k: 1 },
      ];

  const earsEl = (
    <g>
      {ears.map((e, i) => (
        <Ear key={i} bx={e.bx} by={e.by} len={earL * e.k} wid={earW} rot={e.rot} outer={body} inner={inner} />
      ))}
    </g>
  );

  const pomX = layout.standing ? bodyCX - bodyRX * 0.98 : bodyCX - bodyRX * 0.9;
  const pomY = layout.standing ? bodyCY + bodyRY * 0.5 : bodyCY - bodyRY * 0.35;

  // The right-ear blossom rides that ear's tip, whichever pose/variant.
  const blossomEar = ears[1];
  const bRad = (blossomEar.rot * Math.PI) / 180;
  const bTipX = blossomEar.bx + Math.sin(bRad) * earL * blossomEar.k * 0.95;
  const bTipY = blossomEar.by - Math.cos(bRad) * earL * blossomEar.k * 0.95;

  return (
    <g>
      {spec.meadow && <GroundGlow id={`${uid}-ground`} cx={50} y={feetY + 2} rx={bodyRX + 18} color="#D9EFC9" opacity={0.8} />}
      {(spec.aura ?? 0) > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 4} r={bodyRX + 24} color="#FFE9DC" opacity={spec.aura ?? 0} />}

      {/* pompon tail */}
      <Pompon cx={pomX} cy={pomY} s={pom} color={belly} edge={tailEdge} />

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={hoof} />
      ))}

      {/* body + belly */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={hoof} />}

      {/* neck */}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={hoof} />
      ))}

      {/* petal ruff collar */}
      {spec.ruff && <Plume x={headCX} y={headCY + headR * 0.74} color={BLOSSOM_SOFT} len={7} wide={headR * 1.05} rot={0} n={5} />}

      {/* lying baby: ears flopped on the ground, behind the head */}
      {!layout.standing && earsEl}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      {/* standing: lop ears OVER the head sides, inner ear facing the viewer */}
      {layout.standing && earsEl}

      {/* clover sprout on the dome (3-6), grown into the crown's centre at 7+ */}
      {spec.sprout && <Clover x={headCX + headR * 0.12} y={headCY - headR * 1.16} s={1.15} leaf={CLOVER} stem={CLOVER_DARK} />}
      {spec.crown && (
        <g>
          <Flower x={headCX - headR * 0.55} y={headCY - headR * 0.85} r={2.4} petal={BLOSSOM} center={HONEY} />
          <Flower x={headCX + headR * 0.55} y={headCY - headR * 0.85} r={2.4} petal={HONEY} center={BLOSSOM} />
          <Clover x={headCX} y={headCY - headR * 1.12} s={1.3} leaf={CLOVER} stem={CLOVER_DARK} />
        </g>
      )}

      {/* face */}
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.38} r={eyeR} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <WhiskerDots cx={headCX} y={headCY + headR * 0.34} dx={headR * 0.8} s={headR * 0.05} />
      <BunnyNose cx={headCX} y={headCY + headR * 0.28} s={headR * 0.055} />
      <Mouth cx={headCX} y={headCY + headR * 0.48} w={headR * 0.13} mood={mood} />

      {/* ear-tip blossom (over the head so it reads on the hanging ear) */}
      {spec.blossom && <Flower x={bTipX} y={bTipY} r={2.6} petal={BLOSSOM} center={HONEY} />}

      {/* garden life around the pet */}
      {(spec.butterflies ?? 0) > 0 && (
        <g>
          <Butterfly x={bodyCX - bodyRX - 9} y={bodyCY - bodyRY - 6} s={1.1} wing={HONEY} />
          <Butterfly x={headCX + headR * 1.25} y={headCY - headR * 0.25} s={0.95} wing="#A8D8F0" />
          {(spec.butterflies ?? 0) > 2 && <Butterfly x={bodyCX + bodyRX + 10} y={bodyCY + 2} s={1} wing={BLOSSOM} />}
        </g>
      )}
      {spec.petals && <FallingPetals cx={bodyCX} cy={bodyCY - 4} color="#FFC9B4" />}
      {spec.burst && <PetalRing cx={bodyCX} cy={bodyCY - 6} rx={bodyRX + 16} ry={bodyRY + 14} n={10} color={BLOSSOM} />}

      {/* blooming meadow underfoot (in front of the legs) */}
      {spec.meadow && (
        <g>
          {[
            [-26, 1, 2.4],
            [-2, 3, 2.0],
            [21, 1.5, 2.6],
          ].map(([dx, dy, r], i) => (
            <Flower key={i} x={bodyCX + dx} y={feetY + dy} r={r} petal={i === 1 ? HONEY : BLOSSOM} center={i === 1 ? BLOSSOM : HONEY} />
          ))}
          {[
            [-15, 2],
            [10, 3],
            [30, 2],
          ].map(([dx, dy], i) => (
            <path key={`g${i}`} d={`M${bodyCX + dx} ${feetY + dy} q -1.5 -4 0 -6 M${bodyCX + dx + 2} ${feetY + dy} q 1.5 -3.5 2.5 -5`} stroke={CLOVER} strokeWidth={1.1} fill="none" strokeLinecap="round" />
          ))}
        </g>
      )}

      {(spec.sparkle ?? 0) > 0 && (
        <Sparkles
          color="#FFF6D6"
          points={Array.from({ length: spec.sparkle ?? 0 }).map((_, i) => {
            const a = (i / (spec.sparkle ?? 1)) * Math.PI * 2 + 2;
            return [bodyCX + Math.cos(a) * (bodyRX + 14), bodyCY + Math.sin(a) * (bodyRY + 11) - 2, 1.5 + (i % 2)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
