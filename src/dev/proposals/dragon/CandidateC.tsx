import { INK, mix, pick } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth } from "../../../mascot/parts";
import {
  AngularWings,
  Bolt,
  Cracks,
  Crest,
  EggCup,
  Horns,
  ShellCap,
  ShellShard,
  Snout,
  SpadeTail,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate C — « Orage », le dragon des tempêtes (reboot garçon).
 * Storm-navy drake, all angles and gold lightning — no clouds, no fluff:
 *  0 hatching in the cracked egg (brief) · 1 shell cap + shard (brief)
 *  2 horn nubs · 3 jagged angular wings · 4 lightning-bolt tail
 *  5 storm crest (navy spikes) · 6 charge! gold zigzags + first sparks
 *  7 golden bolt horns · 8 storm aura + gold-lit wings
 *  9 thunder titan: giant wings, mega bolt breath, storm glow, bolt ring.
 * NECK_K note: fox profile (0.86) — same low snout.
 */

interface CSpec {
  egg?: "full" | "bits";
  horn: number;
  boltHorns?: boolean;
  wing: number;
  crest: number;
  boltTail?: boolean;
  charge?: boolean;
  sparks: number;
  goldWings?: boolean;
  breath?: boolean;
  aura: number;
  ground?: boolean;
}

const STAGES: CSpec[] = [
  { egg: "full", horn: 0, wing: 0, crest: 0, sparks: 0, aura: 0 }, // 0
  { egg: "bits", horn: 0, wing: 0, crest: 0, sparks: 0, aura: 0 }, // 1
  { horn: 3.5, wing: 0, crest: 0, sparks: 0, aura: 0 }, // 2 nubs
  { horn: 5, wing: 0.9, crest: 0, sparks: 0, aura: 0 }, // 3 jagged wings
  { horn: 6, wing: 1.1, crest: 0, boltTail: true, sparks: 0, aura: 0 }, // 4 bolt tail
  { horn: 7, wing: 1.25, crest: 4, boltTail: true, sparks: 0, aura: 0 }, // 5 storm crest
  { horn: 8, wing: 1.45, crest: 4, boltTail: true, charge: true, sparks: 2, aura: 0.12 }, // 6 charge
  { horn: 9, boltHorns: true, wing: 1.7, crest: 5, boltTail: true, charge: true, sparks: 3, aura: 0.3 }, // 7 bolt horns
  { horn: 10, boltHorns: true, wing: 1.9, crest: 5, boltTail: true, charge: true, sparks: 4, goldWings: true, aura: 0.55 }, // 8 gold-lit wings
  { horn: 11, boltHorns: true, wing: 2.2, crest: 6, boltTail: true, charge: true, sparks: 7, goldWings: true, breath: true, aura: 1, ground: true }, // 9 thunder titan
];

const EGG_SPECKLE = "#B9C4DC";
const HORN_FILL = "#E8E4D8";
const HORN_EDGE = "#A8A294";
const WING_EDGE = "#3A4766";
const GOLD_EDGE = "#D9A93E";
const NAVY = "#5E6E96";
const GOLD = "#FFD54F";

export function CandidateC({ config, layout, stage, mood = "idle", uid }: PRigProps) {
  const body = pick(config.colors, "bodyColor", "#7E8FB5");
  const belly = pick(config.colors, "bellyColor", "#DDE3ED");
  const wingCol = pick(config.colors, "wingColor", NAVY);
  const spec = STAGES[Math.max(0, Math.min(9, stage))];
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const legW = 7;
  const tailEdge = mix(body, INK, 0.35);

  if (spec.egg === "full") {
    return (
      <g>
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.36} r={eyeR * 0.85} mood={mood} sleepy={stage === 0} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.42} w={headR * 0.1} mood={mood} />
        <EggCup uid={uid} speckle={EGG_SPECKLE} />
        <ShellCap x={headCX + 2} y={headCY - headR * 0.86} s={1} tilt={10} speckle={EGG_SPECKLE} />
        <SpadeTail p0={[81, 87]} p1={[89, 83]} p2={[87.5, 75]} w={4.5} color={body} edge={tailEdge} tipColor={belly} tipS={0.7} />
      </g>
    );
  }

  if (!layout.standing) {
    return (
      <g>
        <SpadeTail p0={[bodyCX + bodyRX * 0.65, bodyCY + 2]} p1={[bodyCX + bodyRX + 9, bodyCY]} p2={[bodyCX + bodyRX + 8, bodyCY - 9]} w={5.5} color={body} edge={tailEdge} tipColor={belly} tipS={0.8} />
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
        <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={INK} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
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
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#9FB4E8" opacity={0.9} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 22} color="#C8D2F0" opacity={spec.aura} />}

      <AngularWings cx={bodyCX} cy={bodyCY - bodyRY * 0.4} s={spec.wing} membrane={wingCol} edge={spec.goldWings ? GOLD_EDGE : WING_EDGE} />
      <SpadeTail
        p0={[bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5]}
        p1={[bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9]}
        p2={[bodyCX + bodyRX * 1.45, bodyCY - bodyRY * 0.35]}
        w={6}
        color={body}
        edge={tailEdge}
        tip={spec.boltTail ? "bolt" : "spade"}
        tipColor={spec.boltTail ? GOLD : belly}
        tipS={spec.boltTail ? 1.15 : 0.85}
      />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {spec.charge && <Cracks cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color={GOLD} />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      <Crest hx={headCX} hy={headCY} headR={headR} n={spec.crest} color={NAVY} edge={WING_EDGE} />
      {spec.boltHorns ? (
        <g>
          <Bolt x={headCX - headR * 0.55} y={headCY - headR * 0.98} s={headR * 0.075} rot={-14} />
          <Bolt x={headCX + headR * 0.55} y={headCY - headR * 0.98} s={headR * 0.075} rot={14} />
        </g>
      ) : (
        <Horns hx={headCX} hy={headCY} headR={headR} h={spec.horn} color={HORN_FILL} edge={HORN_EDGE} />
      )}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />

      {spec.breath && <Bolt x={headCX + headR * 0.6} y={headCY + headR * 0.72} s={headR * 0.09} rot={125} />}

      {/* spark ring — tiny bolts instead of sparkle stars */}
      {spec.sparks > 0 && (
        <g>
          {Array.from({ length: spec.sparks }).map((_, i) => {
            const a = (i / spec.sparks) * Math.PI * 2 + 1;
            return (
              <Bolt
                key={i}
                x={bodyCX + Math.cos(a) * (bodyRX + 13)}
                y={bodyCY + Math.sin(a) * (bodyRY + 9)}
                s={0.55 + 0.15 * (i % 2)}
                rot={(i * 47) % 360}
              />
            );
          })}
        </g>
      )}
    </g>
  );
}
