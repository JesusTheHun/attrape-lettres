import { INK, mix, pick } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth, Sparkles } from "../../../mascot/parts";
import { P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import { BunnyNose, CloudPuff, Ear, KinkEar, Pompon, Swirl, WhiskerDots, type PRigProps } from "./rabbitParts";

/**
 * Candidate C — « Nuage », le lapin des nuages (one bent ear!).
 * Growth arc: round cloud-soft kit → a sky sprite hopping on clouds.
 *  0-2 (untouched) curled kit, one ear already bent → head up → first hops
 *  3 cloud ruff collar · 4 XXL cotton cloud-tail · 5 hop-puffs at the feet
 *  6 breeze swirls + first gold sparkle · 7 it walks on its own cloud carpet
 *  8 sky-dipped ear tips + two companion clouds
 *  9 sky festival: golden aura, gold sparkles, a grand cloud carpet, breezes.
 */

interface CSpec {
  earH: number;
  pompon: number;
  cloudTail?: boolean;
  ruff?: boolean;
  hop?: boolean;
  swirls?: number;
  carpet?: number;
  dips?: boolean;
  drift?: boolean;
  aura?: number;
  sparkle?: number;
  gold?: boolean;
}

const STAGES: CSpec[] = [
  { earH: 0.8, pompon: 0.75 }, // 0
  { earH: 0.9, pompon: 0.8 }, // 1
  { earH: 1.0, pompon: 0.85 }, // 2
  { earH: 1.08, pompon: 0.9, ruff: true }, // 3 cloud ruff
  { earH: 1.16, pompon: 1.5, ruff: true, cloudTail: true }, // 4 XXL cloud tail
  { earH: 1.24, pompon: 1.55, ruff: true, cloudTail: true, hop: true }, // 5 hop-puffs
  { earH: 1.32, pompon: 1.6, ruff: true, cloudTail: true, hop: true, swirls: 2, sparkle: 1 }, // 6 breezes
  { earH: 1.42, pompon: 1.65, ruff: true, cloudTail: true, swirls: 2, carpet: 2.4, sparkle: 1 }, // 7 cloud carpet
  { earH: 1.52, pompon: 1.7, ruff: true, cloudTail: true, swirls: 2, carpet: 2.6, dips: true, drift: true, sparkle: 2 }, // 8 sky tips
  { earH: 1.68, pompon: 1.8, ruff: true, cloudTail: true, swirls: 3, carpet: 3.2, dips: true, drift: true, aura: 1, sparkle: 6, gold: true }, // 9 sky festival
];

const CLOUD_EDGE = "#D7E7F4";
const SKY = "#9CCFF0";
const SKY_DEEP = "#7FB8E8";
const SUN = "#FFE082";

export function CandidateC({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.rabbit;
  const S = P_STYLE_SLOT.rabbit;
  const body = pick(config.colors, C.body, "#ECF3FB");
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const inner = pick(config.colors, C.inner, "#C3DDF1");
  const bothStraight = pick(config.styles, S.ear, "pliee") === "droites";
  const bigTail = pick(config.styles, S.tail, "nuage") === "nuage";

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the free sky magic; keep the bent ear + cloud tail.
  if (preview) spec = { earH: spec.earH, pompon: spec.pompon, cloudTail: spec.cloudTail };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR, feetY } = layout;
  const tailEdge = mix(body, INK, 0.16);
  const pom = spec.pompon * (bigTail ? 1 : 0.7);
  const legW = 7;
  const earW = headR * 0.4;
  const earL = headR * spec.earH;
  const hoof = mix(body, INK, 0.3);

  const pomX = layout.standing ? bodyCX - bodyRX * 0.98 : bodyCX - bodyRX * 0.9;
  const pomY = layout.standing ? bodyCY + bodyRY * 0.5 : bodyCY - bodyRY * 0.35;

  return (
    <g>
      {(spec.aura ?? 0) > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 4} r={bodyRX + 24} color="#FFF3C9" opacity={spec.aura ?? 0} />}
      {(spec.carpet ?? 0) > 0 && <GroundGlow id={`${uid}-ground`} cx={50} y={feetY + 3} rx={bodyRX + 20} color="#BFE0F7" opacity={0.7} />}

      {/* cloud carpet under the feet (behind the legs — it stands IN the cloud) */}
      {(spec.carpet ?? 0) > 0 && (
        <g>
          <CloudPuff cx={50 - bodyRX - 7} cy={feetY + 1.5} s={(spec.carpet ?? 0) * 0.6} edge={spec.gold ? SUN : CLOUD_EDGE} />
          <CloudPuff cx={50 + bodyRX + 7} cy={feetY + 1.5} s={(spec.carpet ?? 0) * 0.6} edge={spec.gold ? SUN : CLOUD_EDGE} />
          <CloudPuff cx={50} cy={feetY + 2.5} s={spec.carpet ?? 0} edge={spec.gold ? SUN : CLOUD_EDGE} />
        </g>
      )}

      {/* tail: pompon baby → cotton cloud-tail from stade 4 */}
      {spec.cloudTail ? (
        <CloudPuff cx={pomX} cy={pomY} s={pom * 0.85} edge={CLOUD_EDGE} />
      ) : (
        <Pompon cx={pomX} cy={pomY} s={pom} color="#FFFFFF" edge={tailEdge} />
      )}

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

      {/* hop-puffs beside the feet */}
      {spec.hop && (
        <g>
          <CloudPuff cx={bodyCX - bodyRX - 8} cy={feetY - 2} s={1.1} edge={CLOUD_EDGE} />
          <CloudPuff cx={bodyCX + bodyRX + 8} cy={feetY - 2.5} s={0.9} edge={CLOUD_EDGE} />
        </g>
      )}

      {/* cloud ruff collar */}
      {spec.ruff && (
        <g>
          <CloudPuff cx={headCX - headR * 0.5} cy={headCY + headR * 0.74} s={headR * 0.045} edge={CLOUD_EDGE} />
          <CloudPuff cx={headCX + headR * 0.5} cy={headCY + headR * 0.74} s={headR * 0.045} edge={CLOUD_EDGE} />
          <CloudPuff cx={headCX} cy={headCY + headR * 0.84} s={headR * 0.05} edge={CLOUD_EDGE} />
        </g>
      )}

      {/* ears (behind the head): left straight, right bent — the signature */}
      {layout.standing ? (
        <g>
          <Ear bx={headCX - headR * 0.4} by={headCY - headR * 0.64} len={earL} wid={earW} rot={-8} outer={body} inner={inner} dip={spec.dips ? SKY_DEEP : undefined} />
          {bothStraight ? (
            <Ear bx={headCX + headR * 0.4} by={headCY - headR * 0.64} len={earL} wid={earW} rot={10} outer={body} inner={inner} dip={spec.dips ? SKY_DEEP : undefined} />
          ) : (
            <KinkEar bx={headCX + headR * 0.4} by={headCY - headR * 0.64} len={earL} wid={earW} rot={14} kink={64} outer={body} inner={inner} dip={spec.dips ? SKY_DEEP : undefined} />
          )}
        </g>
      ) : (
        <g>
          <Ear bx={headCX - headR * 0.52} by={headCY - headR * 0.42} len={earL * 0.85} wid={earW} rot={-52} outer={body} inner={inner} />
          <KinkEar bx={headCX - headR * 0.2} by={headCY - headR * 0.58} len={earL * 0.9} wid={earW} rot={-26} kink={58} outer={body} inner={inner} />
        </g>
      )}

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      {/* face */}
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.38} r={eyeR} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <WhiskerDots cx={headCX} y={headCY + headR * 0.34} dx={headR * 0.8} s={headR * 0.05} />
      <BunnyNose cx={headCX} y={headCY + headR * 0.28} s={headR * 0.055} />
      <Mouth cx={headCX} y={headCY + headR * 0.48} w={headR * 0.13} mood={mood} />

      {/* companion clouds drifting by the head */}
      {spec.drift && (
        <g>
          <CloudPuff cx={headCX - headR * 1.55} cy={headCY - headR * 0.9} s={0.85} edge={CLOUD_EDGE} />
          <CloudPuff cx={headCX + headR * 1.6} cy={headCY - headR * 0.45} s={0.7} edge={CLOUD_EDGE} />
        </g>
      )}

      {/* breeze swirls */}
      {(spec.swirls ?? 0) > 0 && (
        <g>
          <Swirl x={bodyCX - bodyRX - 17} y={bodyCY - 3} s={1} color={SKY} />
          <Swirl x={headCX + headR + 5} y={headCY + 3} s={0.8} color={SKY} />
          {(spec.swirls ?? 0) > 2 && <Swirl x={bodyCX + bodyRX + 6} y={bodyCY + 11} s={1.05} color={SKY} />}
        </g>
      )}

      {(spec.sparkle ?? 0) > 0 && (
        <Sparkles
          color={spec.gold ? SUN : "#FFF6D6"}
          points={Array.from({ length: spec.sparkle ?? 0 }).map((_, i) => {
            const a = (i / (spec.sparkle ?? 1)) * Math.PI * 2 + 1.4;
            return [bodyCX + Math.cos(a) * (bodyRX + 13), bodyCY + Math.sin(a) * (bodyRY + 10) - 3, 1.5 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
