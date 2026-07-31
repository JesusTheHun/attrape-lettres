import type { RigProps } from "./growth";
import { INK, mix, pick } from "./growth";
import { accessoryAnchors } from "./anchors";
import { Aura, Bow, Burst, Cheeks, Eyes, FoldedLegs, GroundGlow, Leg, Mouth, Plume, Sparkles, SwimRing, Swimsuit, fourStar } from "./parts";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";

/**
 * Rabbit — « Lune » timeline (tsuki no usagi). Each stade 3→9 adds a clearly
 * visible beat on the way to the legendary moon rabbit:
 *  0-2 (untouched) curled kit, ears laid back → head up → first hops
 *  3 downy chest + inner ears bloom lavender · 4 moonlit gold ear tips
 *  5 grand moon pompon + gold star · 6 gold crescent forehead mark + sparkles
 *  7 star-tipped ears · 8 a floating crescent moon glows beside the head
 *  9 full moon: lavender aura, moonlight pooling on the ground, ring of stars.
 * Blind-test lessons baked in: nightcap DROOPS below its brim (a symmetric cone
 * between two upright ears reads "crown"), the premium star-dust stays OFF the
 * zone right above the head (same crown bug), the star-tail draws OVER the hip
 * (half-hidden it read "held object"), the swimsuit has shoulder straps.
 */

interface RSpec {
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

const STAGES: RSpec[] = [
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
const CAP = "#8E9AD6";

/* -- Rabbit-specific parts ---------------------------------------------- */

/** One rabbit ear grown from a base point. `rot` is degrees from vertical:
 * 0 = straight up, ±12 = the classic upright tilt, negative sweeps back for
 * the lying baby. */
function Ear({ bx, by, len, wid, rot, outer, inner, dip, star }: { bx: number; by: number; len: number; wid: number; rot: number; outer: string; inner?: string; dip?: string; star?: string }) {
  const rad = (rot * Math.PI) / 180;
  const ux = Math.sin(rad);
  const uy = -Math.cos(rad);
  const at = (t: number): [number, number] => [bx + ux * len * t, by + uy * len * t];
  const [cx, cy] = at(0.5);
  const [icx, icy] = at(0.58);
  const [dcx, dcy] = at(0.86);
  const [sx, sy] = at(1.14);
  return (
    <g>
      <ellipse cx={cx} cy={cy} rx={wid * 0.5} ry={len * 0.52} fill={outer} transform={`rotate(${rot} ${cx} ${cy})`} />
      {inner && <ellipse cx={icx} cy={icy} rx={wid * 0.27} ry={len * 0.33} fill={inner} transform={`rotate(${rot} ${icx} ${icy})`} />}
      {dip && <ellipse cx={dcx} cy={dcy} rx={wid * 0.36} ry={len * 0.16} fill={dip} transform={`rotate(${rot} ${dcx} ${dcy})`} />}
      {star && <path d={fourStar(sx, sy, 2.3)} fill={star} />}
    </g>
  );
}

/** An ear whose top folds over — the bought "Oreilles pliées" look. */
function KinkEar({ bx, by, len, wid, rot, kink, outer, inner, dip }: { bx: number; by: number; len: number; wid: number; rot: number; kink: number; outer: string; inner?: string; dip?: string }) {
  const rad = (rot * Math.PI) / 180;
  const ux = Math.sin(rad);
  const uy = -Math.cos(rad);
  const ex = bx + ux * len * 0.6;
  const ey = by + uy * len * 0.6;
  const rot2 = rot + kink;
  const rad2 = (rot2 * Math.PI) / 180;
  const ux2 = Math.sin(rad2);
  const uy2 = -Math.cos(rad2);
  const tl = len * 0.55;
  const c1x = bx + ux * len * 0.34;
  const c1y = by + uy * len * 0.34;
  const c2x = ex + ux2 * tl * 0.45;
  const c2y = ey + uy2 * tl * 0.45;
  const icx = bx + ux * len * 0.38;
  const icy = by + uy * len * 0.38;
  const dcx = ex + ux2 * tl * 0.78;
  const dcy = ey + uy2 * tl * 0.78;
  return (
    <g>
      <ellipse cx={c1x} cy={c1y} rx={wid * 0.5} ry={len * 0.42} fill={outer} transform={`rotate(${rot} ${c1x} ${c1y})`} />
      {inner && <ellipse cx={icx} cy={icy} rx={wid * 0.26} ry={len * 0.26} fill={inner} transform={`rotate(${rot} ${icx} ${icy})`} />}
      <ellipse cx={c2x} cy={c2y} rx={wid * 0.44} ry={tl * 0.55} fill={outer} transform={`rotate(${rot2} ${c2x} ${c2y})`} />
      {dip && <ellipse cx={dcx} cy={dcy} rx={wid * 0.34} ry={tl * 0.24} fill={dip} transform={`rotate(${rot2} ${dcx} ${dcy})`} />}
    </g>
  );
}

/** Round fluffy pompon tail — circles with an outline underlay so it separates
 * from a same-tone body. */
function Pompon({ cx, cy, s, color, edge }: { cx: number; cy: number; s: number; color: string; edge: string }) {
  const puffs: Array<[number, number, number]> = [
    [0, 0, 4.4],
    [-3.2, -1.5, 3.1],
    [3, -1.7, 2.9],
    [-2.4, 2.3, 2.9],
    [2.7, 2.1, 2.8],
    [0, -3.2, 3],
  ];
  return (
    <g>
      {puffs.map(([dx, dy, r], i) => (
        <circle key={`e${i}`} cx={cx + dx * s} cy={cy + dy * s} r={(r + 0.9) * s} fill={edge} />
      ))}
      {puffs.map(([dx, dy, r], i) => (
        <circle key={i} cx={cx + dx * s} cy={cy + dy * s} r={r * s} fill={color} />
      ))}
    </g>
  );
}

/** Tiny rounded-triangle rabbit nose. */
function BunnyNose({ cx, y, s }: { cx: number; y: number; s: number }) {
  return (
    <path d={`M${cx - 1.9 * s} ${y} Q${cx} ${y - 1.7 * s} ${cx + 1.9 * s} ${y} Q${cx} ${y + 2.4 * s} ${cx - 1.9 * s} ${y} Z`} fill="#F0A0AE" />
  );
}

/** Three whisker-freckle dots per cheek — the rabbit face signature. */
function WhiskerDots({ cx, y, dx, s }: { cx: number; y: number; dx: number; s: number }) {
  return (
    <g fill={INK} opacity={0.3}>
      {([-1, 1] as const).flatMap((d) =>
        [0, 1, 2].map((i) => (
          <circle key={`${d}-${i}`} cx={cx + d * (dx + (i % 2) * 1.6 * s)} cy={y + (i - 1) * 1.7 * s} r={0.58 * s} />
        ))
      )}
    </g>
  );
}

/** Crescent moon, tips up/down, opening to the left. */
function Crescent({ cx, cy, r, fill, rot = 0 }: { cx: number; cy: number; r: number; fill: string; rot?: number }) {
  const d = `M${cx} ${cy - r} A${r} ${r} 0 1 1 ${cx} ${cy + r} A${r * 1.15} ${r * 1.15} 0 0 0 ${cx} ${cy - r} Z`;
  return <path d={d} fill={fill} transform={rot ? `rotate(${rot} ${cx} ${cy})` : undefined} />;
}

/** A shooting star: gold four-star head + a trail of shrinking stars (NOT a
 * straight stick — that read as a wand). `rot` = direction the trail streams. */
function Comet({ x, y, s, rot }: { x: number; y: number; s: number; rot: number }) {
  const rad = (rot * Math.PI) / 180;
  const ux = Math.cos(rad);
  const uy = Math.sin(rad);
  const px = -uy;
  const py = ux;
  return (
    <g>
      <path d={fourStar(x + (ux * 5.5 + px * 1.3) * s, y + (uy * 5.5 + py * 1.3) * s, 1.7 * s)} fill="#FFE082" opacity={0.9} />
      <path d={fourStar(x + (ux * 9.5 - px * 1.5) * s, y + (uy * 9.5 - py * 1.5) * s, 1.2 * s)} fill="#FFE8A3" opacity={0.8} />
      <circle cx={x + (ux * 12.5 + px * 0.9) * s} cy={y + (uy * 12.5 + py * 0.9) * s} r={0.65 * s} fill="#FFE8A3" opacity={0.6} />
      <path d={fourStar(x, y, 2.9 * s)} fill="#FFD54F" />
      <circle cx={x - s * 0.5} cy={y - s * 0.5} r={0.65 * s} fill="#FFF7DC" />
    </g>
  );
}

/** Sleeping cap seated on the dome: the cone folds over and DROOPS below its
 * own brim on the right, pompom hanging at the tip — the droop is what stops
 * it reading as a crown between two upright ears. */
function Nightcap({ hx, hy, headR }: { hx: number; hy: number; headR: number }) {
  const bl: [number, number] = [hx - headR * 0.72, hy - headR * 0.52];
  const br: [number, number] = [hx + headR * 0.72, hy - headR * 0.52];
  const tipX = hx + headR * 1.28;
  const tipY = hy - headR * 0.28;
  return (
    <g>
      <path
        d={`M${bl[0]} ${bl[1]} Q${hx - headR * 0.25} ${hy - headR * 1.8} ${hx + headR * 0.25} ${hy - headR * 1.5} Q${hx + headR * 0.78} ${hy - headR * 1.34} ${hx + headR * 0.98} ${hy - headR * 0.92} Q${hx + headR * 1.1} ${hy - headR * 0.55} ${tipX} ${tipY} Q${hx + headR * 0.92} ${hy - headR * 0.4} ${br[0]} ${br[1]} Z`}
        fill={CAP}
      />
      <path d={`M${hx + headR * 0.28} ${hy - headR * 1.44} Q${hx + headR * 0.74} ${hy - headR * 1.12} ${hx + headR * 0.92} ${hy - headR * 0.74}`} fill="none" stroke="#7A85C2" strokeWidth={headR * 0.06} strokeLinecap="round" />
      <path d={fourStar(hx - headR * 0.2, hy - headR * 1.05, headR * 0.09)} fill="#FFF6DE" opacity={0.95} />
      <path d={fourStar(hx + headR * 0.3, hy - headR * 0.95, headR * 0.07)} fill="#FFF6DE" opacity={0.85} />
      <path d={`M${bl[0]} ${bl[1]} Q${hx} ${hy - headR * 1.18} ${br[0]} ${br[1]}`} fill="none" stroke="#FFF3E0" strokeWidth={headR * 0.22} strokeLinecap="round" />
      <circle cx={tipX + headR * 0.06} cy={tipY + headR * 0.1} r={headR * 0.19} fill="#FFF3E0" />
      <circle cx={tipX + headR * 0.06} cy={tipY + headR * 0.1} r={headR * 0.19} fill="none" stroke="#E8D9C0" strokeWidth={0.7} opacity={0.7} />
    </g>
  );
}

/* -- The rig ------------------------------------------------------------- */

export function Rabbit({ config, layout, stage, mood, uid, preview }: RigProps) {
  const C = COLOR_SLOT.rabbit;
  const S = STYLE_SLOT.rabbit;
  const A = ACCESSORY.rabbit;
  const body = pick(config.colors, C.body, "#F6EFE3");
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const innerBase = pick(config.colors, C.inner, "#D9CCEE");
  const foldEars = pick(config.styles, S.ear, "hautes") === "pliees";
  const starTail = pick(config.styles, S.tail, "pompon") === "etoile";
  const flecks = pick(config.styles, S.fur, "uni") === "flocons";
  const has = (id: string) => config.accessories.includes(id);
  const stardust = has(A.stardust) && stage >= 4;

  let spec = STAGES[Math.max(0, Math.min(9, stage))];
  // Shop thumbnail: strip the free per-stage magic; keep ears + pompon (sold
  // parts) and the inner-ear bloom so colour tiles show their true tint.
  if (preview) spec = { earH: spec.earH, pompon: spec.pompon, bloom: spec.bloom };

  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, eyeR, feetY } = layout;
  const anchor = accessoryAnchors("rabbit", layout);
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
          the hip later — half-hidden it read as a "held object") */}
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
      {/* lying nappy-culotte hugs the rump end of the loaf (gated stade 2+ in
          the catalog, kept for safety) */}
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
          <KinkEar key={i} bx={e.bx} by={e.by} len={earL * e.k} wid={earW} rot={e.rot} kink={(layout.standing ? (i === 0 ? -1 : 1) : -1) * 100} outer={body} inner={inner} dip={spec.dip ? GOLD : undefined} />
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
      {layout.standing && has(A.swimsuit) && (
        <g>
          <Swimsuit id={`${uid}-suit`} cx={bodyCX} cy={bodyCY + bodyRY * 0.15} rx={bodyRX * 0.98} ry={bodyRY * 0.88} color="#5AA9E0" star={stage >= 6} />
          {/* shoulder straps make it read "maillot une-pièce", not a shirt */}
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
          <Bow x={anchor.neck.x} y={anchor.neck.y} s={Math.max(0.75, headR * 0.048)} color={CAP} />
          <path d={fourStar(anchor.neck.x, anchor.neck.y, Math.max(0.75, headR * 0.048) * 1.7)} fill={GOLD} />
        </g>
      )}
      {has(A.nightcap) && <Nightcap hx={headCX} hy={headCY} headR={headR} />}

      {/* premium « Poussière d'étoiles » — gold star-dust orbiting the pet.
          Own beats: denser dust at 7+, a shooting star joins at 9. */}
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

      {/* stade-9 ring of stars + free sparkles */}
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
