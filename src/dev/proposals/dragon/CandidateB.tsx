import { INK, mix } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Sparkles } from "../../../mascot/parts";
import {
  BellyPlates,
  Bolt,
  CloudWings,
  DomeFin,
  EggCup,
  Horns,
  RainCloud,
  ShellCap,
  ShellShard,
  Snout,
  SpadeTail,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate B — « Nimbus », le dragon des orages.
 * Periwinkle sky-dragon growing into a thunder spirit:
 *  0 egg (brief) · 1 shell souvenirs (brief) · 2 horn nubs
 *  3 cumulus cloud-wing buds · 4 lightning-bolt tail + dome fin
 *  5 belly plates + tall fin · 6 companion rain-cloud + sparkle
 *  7 golden zigzag horns · 8 halo + storm aura
 *  9 thunder spectacle: giant cloud wings, lightning breath, ground glow.
 */

interface BSpec {
  egg?: "full" | "bits";
  horn: number;
  boltHorns?: boolean;
  wing: number;
  fin: number;
  boltTail?: boolean;
  plates: boolean;
  cloud?: boolean;
  breath?: boolean;
  aura: number;
  sparkle: number;
  halo?: number;
  ground?: boolean;
}

const STAGES: BSpec[] = [
  { egg: "full", horn: 0, wing: 0, fin: 0, plates: false, aura: 0, sparkle: 0 }, // 0
  { egg: "bits", horn: 0, wing: 0, fin: 0, plates: false, aura: 0, sparkle: 0 }, // 1
  { horn: 2.5, wing: 0, fin: 0, plates: false, aura: 0, sparkle: 0 }, // 2 nubs
  { horn: 5, wing: 0.8, fin: 0, plates: false, aura: 0, sparkle: 0 }, // 3 cloud buds
  { horn: 6, wing: 1.05, fin: 0.55, boltTail: true, plates: false, aura: 0, sparkle: 0 }, // 4 bolt tail + fin
  { horn: 7, wing: 1.2, fin: 0.85, boltTail: true, plates: true, aura: 0, sparkle: 0 }, // 5 plates
  { horn: 8, wing: 1.4, fin: 1, boltTail: true, plates: true, cloud: true, aura: 0.15, sparkle: 1 }, // 6 rain buddy
  { horn: 9, boltHorns: true, wing: 1.65, fin: 1.1, boltTail: true, plates: true, cloud: true, aura: 0.35, sparkle: 2 }, // 7 bolt horns
  { horn: 10, boltHorns: true, wing: 1.9, fin: 1.2, boltTail: true, plates: true, cloud: true, aura: 0.6, sparkle: 3, halo: 0.55 }, // 8 halo
  { horn: 11, boltHorns: true, wing: 2.2, fin: 1.3, boltTail: true, plates: true, cloud: true, breath: true, aura: 1, sparkle: 6, halo: 0.6, ground: true }, // 9
];

const BODY = "#A9C6EF";
const BELLY = "#F2F7FF";
const PLATE = "#C4D6F0";
const CLOUD_EDGE = "#C2D4F0";
const FIN = "#6FA6E8";
const FIN_EDGE = "#4C86CC";
const HORN_FILL = "#FFEBC9";
const HORN_EDGE = "#D9B98A";
const EGG_SPECKLE = "#C9DCF5";
const TAIL_EDGE = mix(BODY, INK, 0.35);

export function CandidateB({ layout, stage, mood = "idle", uid }: PRigProps) {
  const spec = STAGES[Math.max(0, Math.min(9, stage))];
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const legW = 7;

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
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#BFD9FF" opacity={0.9} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 22} color="#CFE3FF" opacity={spec.aura} />}

      <CloudWings cx={bodyCX} cy={bodyCY - bodyRY * 0.4} s={spec.wing} edge={CLOUD_EDGE} />
      <SpadeTail
        p0={[bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5]}
        p1={[bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9]}
        p2={[bodyCX + bodyRX * 1.45, bodyCY - bodyRY * 0.35]}
        w={6}
        color={BODY}
        edge={TAIL_EDGE}
        tip={spec.boltTail ? "bolt" : "spade"}
        tipColor={spec.boltTail ? "#FFD54F" : BELLY}
        tipS={spec.boltTail ? 1.15 : 0.85}
      />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={BODY} hoof={INK} />
      ))}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={BODY} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={BELLY} />
      {spec.plates && <BellyPlates cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} line={PLATE} />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={BODY} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={BODY} hoof={INK} />
      ))}

      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} color="#DCEBFF" />}

      <DomeFin hx={headCX} hy={headCY} headR={headR} f={spec.fin} color={FIN} edge={FIN_EDGE} />
      {spec.boltHorns ? (
        <g>
          <Bolt x={headCX - headR * 0.55} y={headCY - headR * 0.98} s={headR * 0.075} rot={-14} />
          <Bolt x={headCX + headR * 0.55} y={headCY - headR * 0.98} s={headR * 0.075} rot={14} />
        </g>
      ) : (
        <Horns hx={headCX} hy={headCY} headR={headR} h={spec.horn} color={HORN_FILL} edge={HORN_EDGE} />
      )}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={BODY} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={BELLY} />
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />

      {spec.cloud && <RainCloud x={headCX - headR * 1.55} y={headCY - headR * 0.85} s={headR / 20} drops={stage >= 8} />}
      {spec.breath && <Bolt x={headCX + headR * 0.6} y={headCY + headR * 0.72} s={headR * 0.09} rot={125} />}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 1;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
          color="#DCEBFF"
        />
      )}
    </g>
  );
}
