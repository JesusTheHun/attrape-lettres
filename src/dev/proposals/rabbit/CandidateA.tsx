import { INK, mix, pick } from "../../../mascot/growth";
import { accessoryAnchors } from "../../../mascot/anchors";
import { Aura, Bow, Burst, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth, Plume, Sparkles, SwimRing, Swimsuit, fourStar } from "../../../mascot/parts";
import { P_ACCESSORY, P_COLOR_SLOT, P_STYLE_SLOT } from "./ids";
import { BunnyNose, Comet, Crescent, Ear, KinkEar, Nightcap, Pompon, WhiskerDots, type PRigProps } from "./rabbitParts";

/**
 * Candidate A — « Lune », le lapin de lune (PICKED design + its wardrobe).
 * Growth arc: helpless kit → the legendary moon rabbit.
 *  0-2 (untouched) curled kit, ears laid back → head up → first hops
 *  3 downy chest + inner ears bloom lavender · 4 moonlit gold ear tips
 *  5 grand moon pompon + gold star · 6 gold crescent forehead mark + sparkles
 *  7 star-tipped ears · 8 a floating crescent moon glows beside the head
 *  9 full moon: lavender aura, moonlight pooling on the ground, ring of stars.
 * Items run adds:
 *  - colours: body ×4 (gris souris/caramel/pêche/lilas), oreilles ×2, ventre crème
 *  - styles: oreilles pliées · petit pompon · flocons d'étoiles
 *  - accessories: nœud étoilé, bonnet de nuit, premium « Poussière d'étoiles »
 *    (comets with their own beats at 7+/9), swim pair.
 * NECK_K note: cat profile (0.94) — same small high muzzle.
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
const FLECK = "#B8A6E0";

export function CandidateA({ config, layout, stage, mood = "idle", uid, preview }: PRigProps) {
  const C = P_COLOR_SLOT.rabbit;
  const S = P_STYLE_SLOT.rabbit;
  const A = P_ACCESSORY.rabbit;
  const body = pick(config.colors, C.body, "#F6EFE3");
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const innerBase = pick(config.colors, C.inner, "#D9CCEE");
  const foldEars = pick(config.styles, S.ear, "hautes") === "pliees";
  const starTail = pick(config.styles, S.tail, "pompon") === "etoile";
  const flecks = pick(config.styles, S.fur, "uni") === "flocons";
  const has = (id: string) => config.accessories.includes(id);
  const stardust = has(A.stardust) && stage >= 4;

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the free per-stage magic; keep ears + pompon (sold parts).
  if (preview) spec = { earH: spec.earH, pompon: spec.pompon, bloom: spec.bloom };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR, feetY } = layout;
  const anchor = accessoryAnchors("cat", layout);
  const inner = mix(body, innerBase, spec.bloom ? 1 : 0.2);
  const tailEdge = mix(body, INK, 0.18);
  const pom = spec.pompon;
  const legW = 7;
  const earW = headR * 0.4;
  const earL = headR * spec.earH;
  const hoofC = mix(body, INK, 0.3);

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

      {/* lying baby rests ON its swim ring (drawn behind), face clear of the tube */}
      {!layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ring`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 1.15} color="#FFD54F" />}

      {/* fluffy pompon tail peeking on the rump (star-tail variant drawn OVER
          the hip later — half-hidden behind the body it read as a "held
          triangle" in the blind test) */}
      {!starTail && (
        <g>
          <Pompon cx={pomX} cy={pomY} s={pom} color="#FFFFFF" edge={tailEdge} />
          {spec.pomStar && <path d={fourStar(pomX, pomY - 2 * pom, 2.4)} fill={GOLD} />}
        </g>
      )}

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={hoofC} />
      ))}

      {/* swim ring, back half — behind the body so the pet sits IN the tube */}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringb`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="back" />}

      {/* body + belly */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />
      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {/* star-flecks scatter across body AND belly — big enough to read at a glance */}
      {flecks &&
        [
          [-0.55, -0.3, 2.6],
          [0.42, -0.5, 2.2],
          [-0.05, -0.62, 1.9],
          [0.6, 0.12, 2.4],
          [-0.5, 0.38, 2.2],
          [0.18, 0.32, 2.0],
          [-0.16, 0.72, 1.8],
          [0.45, 0.62, 1.6],
        ].map(([dx, dy, r], i) => (
          <path key={`fl${i}`} d={fourStar(bodyCX + dx * bodyRX, bodyCY + dy * bodyRY, r * 1.15)} fill={i % 2 ? "#CFC2EC" : FLECK} opacity={0.95} />
        ))}
      {/* bought star-tail: a full gold star riding the hip edge, never occluded */}
      {starTail && (
        <g transform={`rotate(12 ${pomX} ${pomY})`}>
          <path d={fourStar(pomX, pomY, 6.8 * pom)} fill={GOLD} />
          <path d={fourStar(pomX, pomY, 4.2 * pom)} fill="#FFF3D6" />
        </g>
      )}
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={hoofC} />}
      {/* lying nappy-culotte hugs the rump END of the loaf (a full-height band
          read as a "backpack" in the blind test) */}
      {!layout.standing && has(A.swimsuit) && <Swimsuit id={`${uid}-suit`} cx={bodyCX - bodyRX * 0.3} cy={bodyCY + bodyRY * 0.1} rx={bodyRX * 0.7} ry={bodyRY * 0.9} color="#5AA9E0" lying />}

      {/* neck */}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={hoofC} />
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
          <KinkEar key={i} bx={e.bx} by={e.by} len={earL * e.k} wid={earW} rot={e.rot} kink={(layout.standing ? (i === 0 ? -1 : 1) : -1) * 100} tip={0.55} outer={body} inner={inner} dip={spec.dip ? GOLD : undefined} />
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

      {/* accessories — placement from the shared anchor resolver */}
      {/* standing suit rides LOW on the hips; shoulder STRAPS make it read
          "maillot une-pièce", not a shirt/scarf (blind-test lesson) */}
      {layout.standing && has(A.swimsuit) && (
        <g>
          <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 0.98} ry={bodyRY * 0.88} color="#5AA9E0" star={stage >= 6} />
          {([-1, 1] as const).map((d) => (
            <path
              key={d}
              d={`M${bodyCX + d * bodyRX * 0.36} ${bodyCY + bodyRY * 0.08} Q${bodyCX + d * bodyRX * 0.44} ${bodyCY - bodyRY * 0.45} ${bodyCX + d * bodyRX * 0.4} ${bodyCY - bodyRY * 0.92}`}
              stroke="#5AA9E0"
              strokeWidth={2.6}
              fill="none"
              strokeLinecap="round"
            />
          ))}
        </g>
      )}
      {layout.standing && has(A.swimRing) && <SwimRing id={`${uid}-ringf`} cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 1.22} color="#FFD54F" part="front" duck={stage >= 7} />}
      {has(A.bow) && (
        <g>
          <Bow x={anchor.neck.x} y={anchor.neck.y} s={Math.max(0.75, headR * 0.048)} color="#8E9AD6" />
          <path d={fourStar(anchor.neck.x, anchor.neck.y, Math.max(0.75, headR * 0.048) * 1.7)} fill={GOLD} />
        </g>
      )}
      {has(A.nightcap) && <Nightcap hx={headCX} hy={headCY} headR={headR} />}

      {/* premium « Poussière d'étoiles » — gold star-dust orbiting the pet.
          Kept OFF the zone right above the head: a cluster there read as a
          "crown" in the blind test. Own beats: more dust at 7+, a shooting
          star joins the show at 9. */}
      {stardust && (
        <g>
          <Sparkles
            color={GOLD}
            points={(
              [
                [10, 15, 2.6],
                [60, 13, 1.8],
                [150, 16, 2.2],
                [200, 14, 1.7],
                [335, 18, 2.8],
                ...(stage >= 7 ? ([[25, 21, 2.0], [170, 22, 2.4]] as Array<[number, number, number]>) : []),
              ] as Array<[number, number, number]>
            ).map(([deg, rad, r]) => {
              const a = (deg * Math.PI) / 180;
              return [bodyCX + Math.cos(a) * (bodyRX + rad), bodyCY + Math.sin(a) * (bodyRY * 0.55 + rad) - 4, r] as [number, number, number];
            })}
          />
          <g fill="#FFE8A3">
            {([[95, 10, 1.2], [185, 12, 1.0], [350, 9, 1.1]] as Array<[number, number, number]>).map(([deg, rad, r], i) => {
              const a = (deg * Math.PI) / 180;
              return <circle key={i} cx={bodyCX + Math.cos(a) * (bodyRX + rad)} cy={bodyCY + Math.sin(a) * (bodyRY * 0.55 + rad) - 4} r={r} opacity={0.8} />;
            })}
          </g>
          {stage >= 9 && <Comet x={bodyCX - bodyRX - 18} y={bodyCY - bodyRY - 20} s={0.9} rot={200} />}
        </g>
      )}

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
