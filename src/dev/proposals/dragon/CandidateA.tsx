import { INK, mix, pick, ramp } from "../../../mascot/growth";
import { accessoryAnchors } from "../../../mascot/anchors";
import { Aura, Bow, Cheeks, Eyes, FoldedLegs, GroundGlow, Halo, Leg, Mouth, Sparkles, SwimRing, Swimsuit } from "../../../mascot/parts";
import { P_ACCESSORY, P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import {
  BatWings,
  BellyPlates,
  ChestArmor,
  Crest,
  EggCup,
  EggShield,
  FlamePuff,
  Goggles,
  Horns,
  ShellCap,
  ShellShard,
  Snout,
  SpadeTail,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate A — « Flamme », le dragonnet des volcans (RECOMMENDED).
 * Classic storybook green dragon growing into a fire-breathing legend:
 *  0 hatching: visible in its cracked egg (brief) · 1 out, shell cap + shard (brief)
 *  2 first steps, tiny horn nubs · 3 bat-wing buds + real horns
 *  4 wings spread + dome crest · 5 belly plates + big tail spade
 *  6 first flame puff + sparkle · 7 grand horns with gold tips
 *  8 halo + ember aura · 9 fire-breathing spectacle: huge wings, breath,
 *    ground glow, ember sparkles.
 * NECK_K note: fox profile (0.86) fits the dragon muzzle best — same low snout.
 */

interface DSpec {
  egg?: "full" | "bits";
  horn: number;
  wing: number;
  crest: number;
  plates: boolean;
  flame: number;
  aura: number;
  sparkle: number;
  goldTips?: boolean;
  halo?: number;
  ground?: boolean;
}

const STAGES: DSpec[] = [
  { egg: "full", horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, sparkle: 0 }, // 0 in the egg
  { egg: "bits", horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, sparkle: 0 }, // 1 shell cap + shard
  { horn: 3.5, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, sparkle: 0 }, // 2 horn nubs
  { horn: 5, wing: 0.8, crest: 0, plates: false, flame: 0, aura: 0, sparkle: 0 }, // 3 wing buds
  { horn: 6, wing: 1.1, crest: 3, plates: false, flame: 0, aura: 0, sparkle: 0 }, // 4 crest
  { horn: 7, wing: 1.25, crest: 4, plates: true, flame: 0, aura: 0, sparkle: 0 }, // 5 belly plates
  { horn: 8, wing: 1.4, crest: 4, plates: true, flame: 0.45, aura: 0.15, sparkle: 1 }, // 6 first flame
  { horn: 10, wing: 1.7, crest: 5, plates: true, flame: 0.6, aura: 0.35, sparkle: 2, goldTips: true }, // 7 grand horns
  { horn: 11, wing: 1.9, crest: 5, plates: true, flame: 0.7, aura: 0.6, sparkle: 3, goldTips: true, halo: 0.55 }, // 8 halo
  { horn: 13, wing: 2.2, crest: 6, plates: true, flame: 1.05, aura: 1, sparkle: 6, goldTips: true, halo: 0.6, ground: true }, // 9 legend
];

const EGG_SPECKLE = "#D8E8C4";
const HORN_FILL = "#FFE8C9";
const HORN_EDGE = "#D9B98A";

export function CandidateA({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.dragon;
  const S = P_STYLE_SLOT.dragon;
  const A = P_ACCESSORY.dragon;
  const body = pick(config.colors, C.body, "#8FCF9F");
  const belly = pick(config.colors, C.belly, "#FFF1CE");
  const wingCol = pick(config.colors, C.wing, "#FF9E7A");
  const curlyHorns = pick(config.styles, S.horn, "straight") === "curly";
  const roundCrest = pick(config.styles, S.crest, "pointy") === "round";
  const longTail = pick(config.styles, S.tail, "long") === "long";
  const has = (id: string) => config.accessories.includes(id);

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: keep horns/crest/wings/tail (sold parts) but drop the egg
  // and all per-stage magic so a ghost dragon shows only what a tile changes.
  if (preview) spec = { ...spec, egg: undefined, flame: 0, aura: 0, sparkle: 0, goldTips: false, halo: undefined, ground: false };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const anchor = accessoryAnchors("fox", layout);
  const legW = 7;
  const wingEdge = "#C96F4A";
  const crestCol = wingCol;
  // Tail outline derives from the body colour so every recolour keeps its edge.
  const tailEdge = mix(body, INK, 0.35);

  /* -- Stade 0: hatching in the cracked egg ------------------------------ */
  if (spec.egg === "full") {
    return (
      <g>
        {has(A.swimRing) && <SwimRing id={`${uid}-ring`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.16} color="#FFD54F" />}
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.36} r={eyeR * 0.85} mood={mood} sleepy={stage === 0} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.42} w={headR * 0.1} mood={mood} />
        {/* the shell wraps the body — dragon visible from the broken front */}
        <EggCup uid={uid} speckle={EGG_SPECKLE} suitColor={has(A.swimsuit) ? "#5AA9E0" : undefined} />
        <ShellCap x={headCX + 2} y={headCY - headR * 0.86} s={1} tilt={10} speckle={EGG_SPECKLE} />
        {/* tail spade poking through a crack */}
        <SpadeTail p0={[81, 87]} p1={[89, 83]} p2={[87.5, 75]} w={4.5} color={body} edge={tailEdge} tipColor={belly} tipS={0.7} />
        {has(A.armor) && <EggShield x={34} y={86} />}
        {has(A.bowtie) && <Bow x={anchor.neck.x} y={anchor.neck.y + 1} s={headR * 0.075} color="#E0533B" />}
        {has(A.goggles) && <Goggles x={anchor.headTop.x} y={anchor.headTop.y} headR={headR} />}
      </g>
    );
  }

  /* -- Stade 1: hatched, shell souvenirs ---------------------------------- */
  if (!layout.standing) {
    return (
      <g>
        {has(A.swimRing) && <SwimRing id={`${uid}-ring`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 1.15} color="#FFD54F" />}
        <SpadeTail p0={[bodyCX + bodyRX * 0.65, bodyCY + 2]} p1={[bodyCX + bodyRX + 9, bodyCY]} p2={[bodyCX + bodyRX + 8, bodyCY - 9]} w={5.5} color={body} edge={tailEdge} tipColor={belly} tipS={0.8} />
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
        <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={INK} />
        {has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#5AA9E0" lying />}
        {has(A.armor) && <EggShield x={bodyCX - bodyRX * 0.55} y={bodyCY - 2} />}
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />
        {/* shell souvenirs, worn cutely (stripped in preview mode) */}
        {spec.egg === "bits" && (
          <g>
            <ShellCap x={headCX + 3} y={headCY - headR * 0.88} s={0.82} tilt={-12} speckle={EGG_SPECKLE} />
            <ShellShard x={bodyCX - bodyRX * 0.62} y={bodyCY + bodyRY * 0.42} s={0.9} tilt={-10} speckle={EGG_SPECKLE} />
          </g>
        )}
        {has(A.bowtie) && <Bow x={anchor.neck.x} y={anchor.neck.y + 1} s={headR * 0.075} color="#E0533B" />}
        {has(A.goggles) && <Goggles x={anchor.headTop.x} y={anchor.headTop.y} headR={headR} />}
      </g>
    );
  }

  /* -- Stades 2-9: on its feet -------------------------------------------- */
  const tf = ramp(stage, [[2, 0], [5, 0.5], [9, 1]]);
  const tailK = longTail ? 1 : 0.62;
  const tailRoot: [number, number] = [bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5];
  const tailCtrl: [number, number] = [bodyCX + bodyRX * (1 + 0.5 * tailK), bodyCY + bodyRY * 0.9];
  const tailEnd: [number, number] = [bodyCX + bodyRX * (1 + (0.42 + 0.1 * tf) * tailK), bodyCY + bodyRY * (0.45 - (0.5 + 0.55 * tf) * tailK)];
  const flameX = headCX + headR * 0.3;
  const flameY = headCY + headR * 0.6;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color="#FFB27A" opacity={0.85} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 22} color="#FFCF8A" opacity={spec.aura} />}

      <BatWings cx={bodyCX} cy={bodyCY - bodyRY * 0.4} s={spec.wing} membrane={wingCol} edge={wingEdge} />
      <SpadeTail p0={tailRoot} p1={tailCtrl} p2={tailEnd} w={6 + 1.5 * tf} color={body} edge={tailEdge} tipColor={belly} tipS={(0.85 + 0.8 * tf) * (longTail ? 1 : 0.75)} />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}
      {has(A.swimRing) && <SwimRing id={`${uid}-ringb`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="back" />}

      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {spec.plates && <BellyPlates cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} line="#DBB98A" />}
      {has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color="#5AA9E0" star={stage >= 6} />}
      {has(A.armor) && <ChestArmor cx={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} bodyRY={bodyRY} pads={stage >= 6} gem={stage >= 7} />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      {spec.halo && <Halo id={`${uid}-halo`} cx={headCX} cy={headCY} r={headR * 1.7} opacity={spec.halo} color="#FFD59A" />}

      <Crest hx={headCX} hy={headCY} headR={headR} n={spec.crest} round={roundCrest} color={crestCol} edge={wingEdge} />
      <Horns hx={headCX} hy={headCY} headR={headR} h={spec.horn} curly={curlyHorns && stage >= 3} color={HORN_FILL} edge={HORN_EDGE} tipDot={spec.goldTips ? "#FFD54F" : undefined} />
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />

      {spec.flame > 0 && <FlamePuff x={flameX} y={flameY} s={spec.flame} rot={125} />}

      {/* accessories — placement from the shared per-stage anchor resolver */}
      {has(A.swimRing) && <SwimRing id={`${uid}-ringf`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="front" duck={stage >= 7} />}
      {has(A.bowtie) && <Bow x={anchor.neck.x} y={anchor.neck.y} s={headR * 0.075} color="#E0533B" />}
      {has(A.goggles) && <Goggles x={anchor.headTop.x} y={anchor.headTop.y} headR={headR} />}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 1;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
          color="#FFE2A8"
        />
      )}
    </g>
  );
}
