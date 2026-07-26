import { INK, mix } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Sparkles } from "../../../mascot/parts";
import {
  BatWings,
  BellyPlates,
  Crest,
  EggCup,
  Gem,
  Horns,
  ShellCap,
  ShellShard,
  Snout,
  SpadeTail,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate C — « Pépite », le dragon aux trésors.
 * Teal dragon that gilds itself into a treasure guardian:
 *  0 egg with gold speckles (brief) · 1 shell souvenirs (brief) · 2 horn nubs
 *  3 deep-teal wings with gold edges · 4 golden belly armour
 *  5 gem-tipped crest · 6 gem set into the tail spade + sparkle
 *  7 curled golden horns · 8 halo + forehead gem
 *  9 treasure spectacle: gold aura, coin ring, ground glow.
 */

interface CSpec {
  egg?: "full" | "bits";
  horn: number;
  goldHorns?: boolean;
  wing: number;
  crest: number;
  crestGems?: boolean;
  goldBelly?: boolean;
  tailGem?: boolean;
  foreheadGem?: boolean;
  coins?: boolean;
  aura: number;
  sparkle: number;
  halo?: number;
  ground?: boolean;
}

const STAGES: CSpec[] = [
  { egg: "full", horn: 0, wing: 0, crest: 0, aura: 0, sparkle: 0 }, // 0
  { egg: "bits", horn: 0, wing: 0, crest: 0, aura: 0, sparkle: 0 }, // 1
  { horn: 2.5, wing: 0, crest: 0, aura: 0, sparkle: 0 }, // 2 nubs
  { horn: 5, wing: 0.8, crest: 0, aura: 0, sparkle: 0 }, // 3 gold-edged wings
  { horn: 6, wing: 1.05, crest: 0, goldBelly: true, aura: 0, sparkle: 0 }, // 4 golden belly
  { horn: 7, wing: 1.2, crest: 3, crestGems: true, goldBelly: true, aura: 0, sparkle: 0 }, // 5 gem crest
  { horn: 8, wing: 1.4, crest: 3, crestGems: true, goldBelly: true, tailGem: true, aura: 0.15, sparkle: 1 }, // 6 tail gem
  { horn: 10, goldHorns: true, wing: 1.65, crest: 4, crestGems: true, goldBelly: true, tailGem: true, aura: 0.35, sparkle: 2 }, // 7 gold horns
  { horn: 11, goldHorns: true, wing: 1.9, crest: 4, crestGems: true, goldBelly: true, tailGem: true, foreheadGem: true, aura: 0.6, sparkle: 3, halo: 0.55 }, // 8 halo + gem
  { horn: 13, goldHorns: true, wing: 2.2, crest: 5, crestGems: true, goldBelly: true, tailGem: true, foreheadGem: true, coins: true, aura: 1, sparkle: 6, halo: 0.6, ground: true }, // 9
];

const BODY = "#85CDBB";
const BELLY = "#FFF1D6";
const GOLD_BELLY = "#FFD98E";
const PLATE = "#E3B45C";
const WING = "#55A896";
const WING_EDGE = "#C9A24B";
const CREST = "#4FA79A";
const CREST_EDGE = "#2F7D6E";
const HORN_FILL = "#FFE8C9";
const HORN_EDGE = "#D9B98A";
const GOLD = "#F2C14E";
const GOLD_EDGE = "#B07E1E";
const GEMS = ["#FF7EA8", "#7FD1D8", "#B98CFF"];
const EGG_SPECKLE = "#F2D48F";
const TAIL_EDGE = mix(BODY, INK, 0.35);

export function CandidateC({ layout, stage, mood = "idle", uid }: PRigProps) {
  const spec = STAGES[Math.max(0, Math.min(9, stage))];
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const legW = 7;
  const belly = spec.goldBelly ? GOLD_BELLY : BELLY;

  if (spec.egg === "full") {
    return (
      <g>
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={BODY} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={BODY} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={BODY} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={BELLY} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.36} r={eyeR * 0.85} mood={mood} sleepy={stage === 0} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.42} w={headR * 0.1} mood={mood} />
        <EggCup uid={uid} speckle={EGG_SPECKLE} />
        <ShellCap x={headCX + 2} y={headCY - headR * 0.86} s={1} tilt={10} speckle={EGG_SPECKLE} />
        <SpadeTail p0={[81, 87]} p1={[89, 83]} p2={[87.5, 75]} w={4.5} color={BODY} edge={TAIL_EDGE} tipColor={BELLY} tipS={0.7} />
      </g>
    );
  }

  if (!layout.standing) {
    return (
      <g>
        <SpadeTail p0={[bodyCX + bodyRX * 0.65, bodyCY + 2]} p1={[bodyCX + bodyRX + 9, bodyCY]} p2={[bodyCX + bodyRX + 8, bodyCY - 9]} w={5.5} color={BODY} edge={TAIL_EDGE} tipColor={BELLY} tipS={0.8} />
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={BODY} />
        <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={BELLY} />
        <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={BODY} hoof={INK} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={BODY} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={BODY} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={BELLY} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />
        <ShellCap x={headCX + 3} y={headCY - headR * 0.88} s={0.82} tilt={-12} speckle={EGG_SPECKLE} />
        <ShellShard x={bodyCX - bodyRX * 0.62} y={bodyCY + bodyRY * 0.42} s={0.9} tilt={-10} speckle={EGG_SPECKLE} />
      </g>
    );
  }

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#FFDFA0" opacity={0.9} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 22} color="#FFE1A0" opacity={spec.aura} />}

      <BatWings cx={bodyCX} cy={bodyCY - bodyRY * 0.4} s={spec.wing} membrane={WING} edge={WING_EDGE} />
      <SpadeTail
        p0={[bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5]}
        p1={[bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9]}
        p2={[bodyCX + bodyRX * 1.45, bodyCY - bodyRY * 0.35]}
        w={6}
        color={BODY}
        edge={TAIL_EDGE}
        tipColor={spec.tailGem ? GOLD : BELLY}
        tipS={1}
        gem={spec.tailGem ? GEMS[0] : undefined}
      />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={BODY} hoof={INK} />
      ))}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={BODY} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {spec.goldBelly && <BellyPlates cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} line={PLATE} />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={BODY} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={BODY} hoof={INK} />
      ))}

      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} color="#FFE9B8" />}

      <Crest hx={headCX} hy={headCY} headR={headR} n={spec.crest} color={CREST} edge={CREST_EDGE} gems={spec.crestGems ? GEMS : undefined} />
      <Horns hx={headCX} hy={headCY} headR={headR} h={spec.horn} curly={spec.goldHorns} color={spec.goldHorns ? GOLD : HORN_FILL} edge={spec.goldHorns ? GOLD_EDGE : HORN_EDGE} />
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={BODY} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />
      {spec.foreheadGem && <Gem x={headCX} y={headCY - headR * 0.45} s={headR * 0.17} color={GEMS[0]} />}

      {spec.coins && (
        <g>
          {Array.from({ length: 8 }).map((_, i) => {
            const a = (i / 8) * Math.PI * 2 + 0.4;
            const x = bodyCX + Math.cos(a) * (bodyRX + 20);
            const y = bodyCY + Math.sin(a) * (bodyRY + 14);
            return (
              <g key={i}>
                <circle cx={x} cy={y} r={2.4} fill={GOLD} stroke={GOLD_EDGE} strokeWidth={0.7} />
                <circle cx={x - 0.6} cy={y - 0.6} r={0.6} fill="#FFFDF4" opacity={0.9} />
              </g>
            );
          })}
        </g>
      )}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 1;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
          color="#FFE9B8"
        />
      )}
    </g>
  );
}
