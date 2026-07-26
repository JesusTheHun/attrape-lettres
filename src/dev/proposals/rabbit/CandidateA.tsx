import { INK, mix, pick } from "../../../mascot/growth";
import { Aura, Burst, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth, Plume, Sparkles, fourStar } from "../../../mascot/parts";
import { P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import { BunnyNose, Crescent, Ear, KinkEar, Pompon, WhiskerDots, type PRigProps } from "./rabbitParts";

/**
 * Candidate A — « Lune », le lapin de lune (tsuki no usagi).
 * Growth arc: helpless kit → the legendary moon rabbit.
 *  0-2 (untouched) curled kit, ears laid back → head up → first hops
 *  3 downy chest + inner ears bloom lavender · 4 moonlit gold ear tips
 *  5 grand moon pompon + gold star · 6 gold crescent forehead mark + sparkles
 *  7 star-tipped ears · 8 a floating crescent moon glows beside the head
 *  9 full moon: lavender aura, moonlight pooling on the ground, ring of stars.
 */

interface ASpec {
  earH: number;
  pompon: number;
  chest?: boolean;
  bloom?: boolean;
  dip?: boolean;
  pomStar?: boolean;
  mark?: boolean;
  sparkle?: number;
  starTips?: boolean;
  halo?: number;
  aura?: number;
  ground?: boolean;
  burst?: boolean;
}

const STAGES: ASpec[] = [
  { earH: 0.78, pompon: 0.75 }, // 0
  { earH: 0.88, pompon: 0.8 }, // 1
  { earH: 0.98, pompon: 0.85 }, // 2
  { earH: 1.06, pompon: 0.9, chest: true, bloom: true }, // 3 chest down + lavender ears
  { earH: 1.14, pompon: 0.95, chest: true, bloom: true, dip: true }, // 4 gold tips
  { earH: 1.22, pompon: 1.42, chest: true, bloom: true, dip: true, pomStar: true }, // 5 moon pompon
  { earH: 1.3, pompon: 1.46, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 2 }, // 6 crescent mark
  { earH: 1.4, pompon: 1.5, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 3, starTips: true }, // 7 star tips
  { earH: 1.5, pompon: 1.54, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 4, starTips: true, halo: 0.6 }, // 8 crescent halo
  { earH: 1.66, pompon: 1.62, chest: true, bloom: true, dip: true, pomStar: true, mark: true, sparkle: 6, starTips: true, halo: 0.7, aura: 1, ground: true, burst: true }, // 9 full moon
];

const GOLD = "#FFD54F";
const MOONLIGHT = "#EFE7FF";
const STAR_SOFT = "#FFE082";

export function CandidateA({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.rabbit;
  const S = P_STYLE_SLOT.rabbit;
  const body = pick(config.colors, C.body, "#F6EFE3");
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const innerBase = pick(config.colors, C.inner, "#D9CCEE");
  const foldEars = pick(config.styles, S.ear, "hautes") === "pliees";
  const bigTail = pick(config.styles, S.tail, "grand") === "grand";

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the free per-stage magic; keep ears + pompon (sold parts).
  if (preview) spec = { earH: spec.earH, pompon: spec.pompon, bloom: spec.bloom };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR, feetY } = layout;
  const inner = mix(body, innerBase, spec.bloom ? 1 : 0.2);
  const tailEdge = mix(body, INK, 0.18);
  const pom = spec.pompon * (bigTail ? 1 : 0.7);
  const legW = 7;
  const earW = headR * 0.4;
  const earL = headR * spec.earH;

  // Upright tilted ears when standing; swept back over the shoulder when lying.
  const ears = layout.standing
    ? [
        { bx: headCX - headR * 0.42, by: headCY - headR * 0.62, rot: -12, k: 1 },
        { bx: headCX + headR * 0.42, by: headCY - headR * 0.62, rot: 12, k: 1 },
      ]
    : [
        { bx: headCX - headR * 0.55, by: headCY - headR * 0.48, rot: -66, k: 0.95 },
        { bx: headCX - headR * 0.2, by: headCY - headR * 0.6, rot: -30, k: 1 },
      ];

  const pomX = layout.standing ? bodyCX - bodyRX * 0.98 : bodyCX - bodyRX * 0.9;
  const pomY = layout.standing ? bodyCY + bodyRY * 0.5 : bodyCY - bodyRY * 0.35;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={feetY + 2} rx={bodyRX + 18} color="#D9CCF2" opacity={0.85} />}
      {(spec.aura ?? 0) > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 4} r={bodyRX + 24} color={MOONLIGHT} opacity={spec.aura ?? 0} />}

      {/* pompon tail peeking out on the rump */}
      <Pompon cx={pomX} cy={pomY} s={pom} color="#FFFFFF" edge={tailEdge} />
      {spec.pomStar && <path d={fourStar(pomX, pomY - 2 * pom, 2.4)} fill={GOLD} />}

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={mix(body, INK, 0.3)} />
      ))}

      {/* body + belly */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={mix(body, INK, 0.3)} />}

      {/* neck */}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={mix(body, INK, 0.3)} />
      ))}

      {/* downy chest */}
      {spec.chest && <Plume x={headCX} y={headCY + headR * 0.8} color={belly} len={5.5} wide={headR * 0.65} rot={0} n={3} />}

      {/* floating crescent moon beside the head (behind the ears) */}
      {(spec.halo ?? 0) > 0 && (
        <g>
          <Aura id={`${uid}-halo`} cx={headCX + headR * 1.05} cy={headCY - headR * 0.9} r={headR * 0.95} color="#FFF3C4" opacity={(spec.halo ?? 0) + 0.15} />
          <Crescent cx={headCX + headR * 1.05} cy={headCY - headR * 0.9} r={headR * 0.4} fill={STAR_SOFT} rot={24} />
        </g>
      )}

      {/* ears (behind the head) */}
      {ears.map((e, i) =>
        foldEars ? (
          <KinkEar key={i} bx={e.bx} by={e.by} len={earL * e.k} wid={earW} rot={e.rot} kink={(i === 0 ? -1 : 1) * 58} outer={body} inner={inner} dip={spec.dip ? GOLD : undefined} />
        ) : (
          <Ear key={i} bx={e.bx} by={e.by} len={earL * e.k} wid={earW} rot={e.rot} outer={body} inner={inner} dip={spec.dip ? GOLD : undefined} star={spec.starTips ? STAR_SOFT : undefined} />
        )
      )}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      {/* face */}
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.38} r={eyeR} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <WhiskerDots cx={headCX} y={headCY + headR * 0.34} dx={headR * 0.8} s={headR * 0.05} />
      <BunnyNose cx={headCX} y={headCY + headR * 0.28} s={headR * 0.055} />
      <Mouth cx={headCX} y={headCY + headR * 0.48} w={headR * 0.13} mood={mood} />
      {spec.mark && <Crescent cx={headCX} cy={headCY - headR * 0.55} r={2.7} fill={GOLD} rot={18} />}

      {/* stade-9 ring of stars + sparkles */}
      {spec.burst && <Burst cx={bodyCX} cy={bodyCY - 6} rx={bodyRX + 16} ry={bodyRY + 14} n={8} color={STAR_SOFT} />}
      {(spec.sparkle ?? 0) > 0 && (
        <Sparkles
          color="#FFF1B8"
          points={Array.from({ length: spec.sparkle ?? 0 }).map((_, i) => {
            const a = (i / (spec.sparkle ?? 1)) * Math.PI * 2 + 0.9;
            return [bodyCX + Math.cos(a) * (bodyRX + 13), bodyCY + Math.sin(a) * (bodyRY + 10) - 3, 1.5 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
