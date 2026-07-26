import { INK, mix, pick } from "../../../mascot/growth";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth } from "../../../mascot/parts";
import {
  BellyPlates,
  Crest,
  DustPuffs,
  EggCup,
  FrillBand,
  Horns,
  ShellCap,
  ShellShard,
  Snout,
  SpadeTail,
  TailClub,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate B — « Roc », le dragon-dino cuirassé (reboot garçon).
 * WINGLESS heavy drake — a walking fortress: bone back-plates, a spiked
 * club tail, a triceratops-style bone casque. Strength, not sparkle:
 *  0 hatching in the cracked egg (brief) · 1 shell cap + shard (brief)
 *  2 horn nubs · 3 bone plates on the dome · 4 spiked club tail
 *  5 belly armour plates · 6 stomp! dust clouds at the feet
 *  7 bone casque (frill) · 8 gold studs + stone aura
 *  9 titan: giant casque, huge glowing club, quake glow, rock ring.
 * NECK_K note: cat profile (0.94) — the heavier round muzzle sits higher.
 */

interface BSpec {
  egg?: "full" | "bits";
  horn: number;
  plates: number;
  club: number;
  belly: boolean;
  dust?: boolean;
  frill: number;
  studs?: boolean;
  aura: number;
  rocks: number;
  ground?: boolean;
}

const STAGES: BSpec[] = [
  { egg: "full", horn: 0, plates: 0, club: 0, belly: false, frill: 0, aura: 0, rocks: 0 }, // 0
  { egg: "bits", horn: 0, plates: 0, club: 0, belly: false, frill: 0, aura: 0, rocks: 0 }, // 1
  { horn: 3.5, plates: 0, club: 0, belly: false, frill: 0, aura: 0, rocks: 0 }, // 2 nubs
  { horn: 4.5, plates: 3, club: 0, belly: false, frill: 0, aura: 0, rocks: 0 }, // 3 bone plates
  { horn: 5, plates: 3, club: 0.85, belly: false, frill: 0, aura: 0, rocks: 0 }, // 4 club tail
  { horn: 5.5, plates: 4, club: 0.95, belly: true, frill: 0, aura: 0, rocks: 0 }, // 5 belly armour
  { horn: 6, plates: 4, club: 1.05, belly: true, dust: true, frill: 0, aura: 0, rocks: 0 }, // 6 stomp dust
  { horn: 7, plates: 5, club: 1.15, belly: true, dust: true, frill: 0.85, aura: 0, rocks: 0 }, // 7 bone casque
  { horn: 8, plates: 5, club: 1.3, belly: true, dust: true, frill: 1, studs: true, aura: 0.5, rocks: 0 }, // 8 gold studs
  { horn: 9, plates: 6, club: 1.55, belly: true, dust: true, frill: 1.2, studs: true, aura: 1, rocks: 8, ground: true }, // 9 titan
];

const EGG_SPECKLE = "#CFC5B0";
const BONE = "#C8B89A";
const BONE_EDGE = "#8F8268";
const ROCK = "#A99F8C";

export function CandidateB({ config, layout, stage, mood = "idle", uid }: PRigProps) {
  const body = pick(config.colors, "bodyColor", "#9AA8B8");
  const belly = pick(config.colors, "bellyColor", "#D9CDB4");
  const plate = pick(config.colors, "plateColor", BONE);
  const spec = STAGES[Math.max(0, Math.min(9, stage))];
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const legW = 7.6;
  const tailEdge = mix(body, INK, 0.35);
  const plateEdge = mix(plate, INK, 0.4);

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
        {/* the club-to-be already pokes out as a stubby ball */}
        <SpadeTail p0={[81, 87]} p1={[89, 83]} p2={[87.5, 76]} w={4.5} color={body} edge={tailEdge} tip="none" />
        <TailClub x={87.5} y={76} s={0.55} color={plate} edge={plateEdge} />
      </g>
    );
  }

  if (!layout.standing) {
    return (
      <g>
        <SpadeTail p0={[bodyCX + bodyRX * 0.65, bodyCY + 2]} p1={[bodyCX + bodyRX + 9, bodyCY]} p2={[bodyCX + bodyRX + 8, bodyCY - 8]} w={5.5} color={body} edge={tailEdge} tip="none" />
        <TailClub x={bodyCX + bodyRX + 8} y={bodyCY - 8} s={0.62} color={plate} edge={plateEdge} />
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

  const clubX = bodyCX + bodyRX * 1.52;
  const clubY = bodyCY - bodyRY * 0.28;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#D9CDB4" opacity={0.95} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 20} color="#E3D9BE" opacity={spec.aura} />}

      {spec.club > 0 ? (
        <g>
          <SpadeTail p0={[bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5]} p1={[bodyCX + bodyRX * 1.55, bodyCY + bodyRY * 0.8]} p2={[clubX, clubY]} w={6.5} color={body} edge={tailEdge} tip="none" />
          <TailClub x={clubX} y={clubY} s={spec.club} color={plate} edge={plateEdge} />
        </g>
      ) : (
        <SpadeTail p0={[bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5]} p1={[bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9]} p2={[bodyCX + bodyRX * 1.42, bodyCY - bodyRY * 0.05]} w={6} color={body} edge={tailEdge} tipColor={belly} tipS={0.85} />
      )}

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {spec.belly && <BellyPlates cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} line={BONE_EDGE} />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}
      {spec.dust && <DustPuffs cx={bodyCX} y={layout.feetY - 1} spread={bodyRX + 8} />}

      {/* casque + bone plates behind the head */}
      <FrillBand hx={headCX} hy={headCY} headR={headR} f={spec.frill} color={plate} edge={plateEdge} />
      {spec.studs &&
        [-0.55, 0, 0.55].map((f) => {
          const a = -Math.PI / 2 + f;
          const rr = headR * (1.02 + 0.16 * spec.frill);
          return <circle key={f} cx={headCX + Math.cos(a) * rr} cy={headCY + Math.sin(a) * rr} r={1.9} fill="#FFD54F" stroke={BONE_EDGE} strokeWidth={0.6} />;
        })}
      <Crest hx={headCX} hy={headCY} headR={headR} n={spec.plates} round scale={1.7} color={plate} edge={plateEdge} />
      <Horns hx={headCX} hy={headCY} headR={headR} h={spec.horn} color={plate} edge={plateEdge} />
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />

      {/* rock ring instead of sparkles — the quake's debris */}
      {spec.rocks > 0 && (
        <g fill={ROCK} stroke={BONE_EDGE} strokeWidth={0.5}>
          {Array.from({ length: spec.rocks }).map((_, i) => {
            const a = (i / spec.rocks) * Math.PI * 2 + 0.4;
            const x = bodyCX + Math.cos(a) * (bodyRX + 14);
            const y = bodyCY + Math.sin(a) * (bodyRY + 10);
            const s = 1.6 + (i % 3) * 0.7;
            return <path key={i} d={`M${x} ${y - s} L${x + s} ${y + s * 0.6} L${x - s} ${y + s * 0.7} Z`} />;
          })}
        </g>
      )}
    </g>
  );
}
