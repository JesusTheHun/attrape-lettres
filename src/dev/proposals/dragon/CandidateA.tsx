import { INK, mix, pick, ramp } from "../../../mascot/growth";
import { accessoryAnchors } from "../../../mascot/anchors";
import { Aura, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth } from "../../../mascot/parts";
import { P_ACCESSORY, P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import {
  BatWings,
  BellyPlates,
  CapeBack,
  CapeClasp,
  Claws,
  Cracks,
  Crest,
  EggCup,
  FangPendant,
  Fangs,
  FlamePuff,
  Goggles,
  GoldPile,
  Horns,
  ShellCap,
  ShellShard,
  SmokePuffs,
  Snout,
  SpadeTail,
  type PRigProps,
} from "./dragonParts";

/**
 * Candidate A — « Braise », le dragon de feu (PICKED design + its wardrobe v2).
 * Growth arc: see the v2 proposal. Wardrobe (after user feedback — no swim
 * pair, no shield/helmet, no tail-size item):
 *  - colours: body ×4 (braise/charbon/nuit/terre), ventre magma, ailes ×2,
 *    cornes ×2 (or/noires)
 *  - styles: cornes doubles · crête de lave · queue massue · queue de feu
 *  - accessories: cape de chevalier (stade 2+), lunettes d'aviateur (3+),
 *    collier de croc (2+), petit trésor (dès l'œuf), premium « Flamme bleue »
 *    (legendary blue breath, own beats at 7+/9).
 * NECK_K note: fox profile (0.86) — same low snout.
 */

interface ASpec {
  egg?: "full" | "bits";
  horn: number;
  wing: number;
  crest: number;
  plates: boolean;
  flame: number;
  fierce?: boolean;
  cracks?: boolean;
  smoke?: boolean;
  aura: number;
  ember: number;
  goldTips?: boolean;
  ground?: boolean;
}

const STAGES: ASpec[] = [
  { egg: "full", horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0 }, // 0 in the egg
  { egg: "bits", horn: 0, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0 }, // 1 shell souvenirs
  { horn: 3.5, wing: 0, crest: 0, plates: false, flame: 0, aura: 0, ember: 0 }, // 2 horn nubs
  { horn: 5, wing: 0.9, crest: 0, plates: false, flame: 0, aura: 0, ember: 0 }, // 3 ember wings
  { horn: 6, wing: 1.15, crest: 3, plates: false, flame: 0, aura: 0, ember: 0 }, // 4 charcoal mohawk
  { horn: 7, wing: 1.3, crest: 4, plates: true, flame: 0, aura: 0, ember: 0 }, // 5 belly plates
  { horn: 8, wing: 1.45, crest: 4, plates: true, flame: 0.5, aura: 0.12, ember: 2 }, // 6 first flame
  { horn: 10, wing: 1.7, crest: 5, plates: true, flame: 0.62, fierce: true, aura: 0.3, ember: 3, goldTips: true }, // 7 fangs + claws
  { horn: 11, wing: 1.9, crest: 5, plates: true, flame: 0.72, fierce: true, cracks: true, smoke: true, aura: 0.55, ember: 4, goldTips: true }, // 8 volcano wakes
  { horn: 13, wing: 2.2, crest: 6, plates: true, flame: 1.1, fierce: true, cracks: true, smoke: true, aura: 1, ember: 8, goldTips: true, ground: true }, // 9 fire storm
];

const EGG_SPECKLE = "#E8C49A";
const HORN_FILL = "#EDE3CE";
const HORN_EDGE = "#B8A98C";
const CREST = "#5F6470";
const CREST_EDGE = "#3E4148";
const LAVA_CREST = "#FF8A50";
const LAVA_CREST_EDGE = "#C2502E";
const WING_EDGE = "#A83E28";
const EMBER = "#FF8A50";
const BLUE_OUTER = "#5BC8FF";
const BLUE_INNER = "#E8F7FF";
const BLUE_EMBER = "#7FD1FF";

export function CandidateA({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.dragon;
  const S = P_STYLE_SLOT.dragon;
  const A = P_ACCESSORY.dragon;
  const body = pick(config.colors, C.body, "#7DB874");
  const belly = pick(config.colors, C.belly, "#E9DFB2");
  const wingCol = pick(config.colors, C.wing, "#E2694F");
  const hornCol = pick(config.colors, C.horn, HORN_FILL);
  const hornEdge = hornCol === HORN_FILL ? HORN_EDGE : mix(hornCol, INK, 0.35);
  const doubleHorns = pick(config.styles, S.horn, "straight") === "double";
  const lavaCrest = pick(config.styles, S.crest, "charbon") === "lava";
  const tailPick = pick(config.styles, S.tail, "spade");
  const has = (id: string) => config.accessories.includes(id);
  const blue = has(A.blueFlame);

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the egg + all free per-stage magic so a ghost dragon
  // shows only what a tile sells (sold styles/accessories stay visible).
  if (preview) spec = { ...spec, egg: undefined, flame: 0, aura: 0, ember: 0, fierce: false, cracks: false, smoke: false, goldTips: false, ground: false };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR } = layout;
  const anchor = accessoryAnchors("fox", layout);
  const legW = 7;
  const tailEdge = mix(body, INK, 0.35);
  const crestCol = lavaCrest ? LAVA_CREST : CREST;
  const crestEdge = lavaCrest ? LAVA_CREST_EDGE : CREST_EDGE;
  // The flame tail lights up with the walking stades; before that it stays a spade.
  const tailTip = tailPick === "flame" && stage < 2 && !preview ? "spade" : (tailPick as "spade" | "club" | "flame");
  const tailTipColor = tailTip === "club" ? mix(body, INK, 0.12) : tailTip === "spade" ? belly : undefined;

  /* -- Stade 0: hatching in the cracked egg ------------------------------ */
  if (spec.egg === "full") {
    return (
      <g>
        {has(A.treasure) && <GoldPile x={16} groundY={layout.feetY + 2} s={0.75} />}
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.36} r={eyeR * 0.85} mood={mood} sleepy={stage === 0} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.42} w={headR * 0.1} mood={mood} />
        <EggCup uid={uid} speckle={EGG_SPECKLE} />
        <ShellCap x={headCX + 2} y={headCY - headR * 0.86} s={1} tilt={10} speckle={EGG_SPECKLE} />
        <SpadeTail p0={[81, 87]} p1={[89, 83]} p2={[87.5, 75]} w={4.5} color={body} edge={tailEdge} tip={tailTip} tipColor={tailTipColor} tipS={0.7} />
      </g>
    );
  }

  /* -- Stade 1: hatched, shell souvenirs ---------------------------------- */
  if (!layout.standing) {
    return (
      <g>
        {has(A.treasure) && <GoldPile x={bodyCX - bodyRX - 9} groundY={layout.feetY + 2} s={0.85} />}
        <SpadeTail
          p0={[bodyCX + bodyRX * 0.65, bodyCY + 2]}
          p1={[bodyCX + bodyRX + 9, bodyCY]}
          p2={[bodyCX + bodyRX + 8, bodyCY - 9]}
          w={5.5}
          color={body}
          edge={tailEdge}
          tip={tailTip}
          tipColor={tailTipColor}
          tipS={0.8}
        />
        <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
        <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
        <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={INK} />
        <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
        <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />
        <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
        <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
        <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
        <Mouth cx={headCX} y={headCY + headR * 0.52} w={headR * 0.13} mood={mood} />
        {spec.egg === "bits" && (
          <g>
            <ShellCap x={headCX + 3} y={headCY - headR * 0.88} s={0.82} tilt={-12} speckle={EGG_SPECKLE} />
            <ShellShard x={bodyCX - bodyRX * 0.62} y={bodyCY + bodyRY * 0.42} s={0.9} tilt={-10} speckle={EGG_SPECKLE} />
          </g>
        )}
      </g>
    );
  }

  /* -- Stades 2-9: on its feet -------------------------------------------- */
  const tf = ramp(stage, [[2, 0], [5, 0.5], [9, 1]]);
  const tailRoot: [number, number] = [bodyCX + bodyRX * 0.45, bodyCY + bodyRY * 0.5];
  const tailCtrl: [number, number] = [bodyCX + bodyRX * 1.5, bodyCY + bodyRY * 0.9];
  const tailEnd: [number, number] = [bodyCX + bodyRX * (1.42 + 0.1 * tf), bodyCY + bodyRY * (0.45 - (0.5 + 0.55 * tf))];
  const mouthY = headCY + headR * 0.52;
  const mouthW = headR * 0.13;
  // ramp() clamps below its first stop — gate by stage or the "blue from 4"
  // breath would leak down to the wobbly stades.
  const flameS = blue && stage >= 4 ? Math.max(spec.flame, ramp(stage, [[4, 0.5], [6, 0.62], [7, 0.85], [9, 1.25]])) : spec.flame;
  const emberN = blue && stage >= 7 ? Math.max(spec.ember, 4) : spec.ember;
  const emberCol = blue ? BLUE_EMBER : EMBER;

  return (
    <g>
      {spec.ground && <GroundGlow id={`${uid}-ground`} cx={50} y={layout.feetY + 2} rx={bodyRX + 18} color={blue ? "#9FD4FF" : "#FF9A66"} opacity={0.9} />}
      {spec.aura > 0 && <Aura id={`${uid}-aura`} cx={bodyCX} cy={bodyCY - 2} r={bodyRX + 22} color={blue ? "#B8E2FF" : "#FFB27A"} opacity={spec.aura} />}

      <BatWings cx={bodyCX} cy={bodyCY - bodyRY * 0.4} s={spec.wing} membrane={wingCol} edge={WING_EDGE} />
      {has(A.cape) && <CapeBack cx={bodyCX} topY={bodyCY - bodyRY * 0.62} w={bodyRX * 1.3} h={layout.feetY - 2 - (bodyCY - bodyRY * 0.62)} />}
      <SpadeTail p0={tailRoot} p1={tailCtrl} p2={tailEnd} w={6 + 1.5 * tf} color={body} edge={tailEdge} tip={tailTip} tipColor={tailTipColor} tipS={0.85 + 0.8 * tf} />

      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      {spec.cracks && <Cracks cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} color={emberCol} />}
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {spec.plates && <BellyPlates cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} line="#C4B584" />}

      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}
      {spec.fierce && layout.legs.filter((l) => !l.back).map((l, i) => <Claws key={`c${i}`} x={l.footX - 1.4} y={l.footY - 0.6} w={4} />)}

      <Crest hx={headCX} hy={headCY} headR={headR} n={spec.crest} color={crestCol} edge={crestEdge} />
      <Horns
        hx={headCX}
        hy={headCY}
        headR={headR}
        h={spec.horn}
        variant={doubleHorns ? "double" : "straight"}
        color={hornCol}
        edge={hornEdge}
        tipDot={spec.goldTips ? "#FFD54F" : undefined}
      />
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      <Snout hx={headCX} hy={headCY} headR={headR} color={belly} />
      {spec.smoke && <SmokePuffs hx={headCX} hy={headCY} headR={headR} />}
      <Eyes cx={headCX} y={headCY - headR * 0.05} dx={headR * 0.4} r={eyeR} mood={mood} sleepy={false} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={mouthY} w={mouthW} mood={mood} />
      {spec.fierce && <Fangs cx={headCX} y={mouthY + 0.4} w={mouthW * 1.15} />}

      {flameS > 0 && (
        <g>
          <FlamePuff x={headCX + headR * 0.3} y={headCY + headR * 0.6} s={flameS} rot={125} outer={blue ? BLUE_OUTER : undefined} inner={blue ? BLUE_INNER : undefined} />
          {/* legendary double breath — the blue flame's own growth beat */}
          {blue && stage >= 7 && <FlamePuff x={headCX + headR * 0.05} y={headCY + headR * 0.72} s={flameS * 0.55} rot={145} outer={BLUE_OUTER} inner={BLUE_INNER} />}
        </g>
      )}

      {/* accessories over the body */}
      {stage >= 3 && has(A.goggles) && <Goggles x={headCX} y={headCY - headR * 0.72} headR={headR} />}
      {/* if the cape clasp already sits on the throat, the fang cord drops a touch */}
      {has(A.fang) && <FangPendant x={anchor.neck.x} y={anchor.neck.y + (has(A.cape) ? 3 : 0)} w={anchor.neck.w} />}
      {has(A.cape) && <CapeClasp x={anchor.neck.x} y={anchor.neck.y} w={anchor.neck.w} />}
      {has(A.treasure) && <GoldPile x={bodyCX - bodyRX - 10} groundY={layout.feetY + 1} s={0.85 + 0.5 * tf} rich={stage >= 7} />}

      {emberN > 0 && (
        <g fill={emberCol}>
          {Array.from({ length: emberN }).map((_, i) => {
            const a = (i / emberN) * Math.PI * 2 + 0.8;
            const r = 1.1 + (i % 3) * 0.55;
            return <circle key={i} cx={bodyCX + Math.cos(a) * (bodyRX + 12)} cy={bodyCY + Math.sin(a) * (bodyRY + 9) - 4} r={r} opacity={0.55 + 0.15 * (i % 3)} />;
          })}
        </g>
      )}
    </g>
  );
}
