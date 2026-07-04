import type { RigProps } from "./growth";
import { INK, pick, ramp } from "./growth";
import { COLOR_SLOT, STYLE_SLOT, ACCESSORY } from "./ids";
import { Aura, Cheeks, Eyes, FoldedLegs, Leg, Mouth, Plume, Sparkles } from "./parts";

/**
 * Fox timeline:
 *  curled fox-kit (can't stand) → 1 lifting head → 2-6 distinct beats (legs
 *  lengthen, ruff fills, ear-tufts sprout) → 7-8 aura → 9 majestic fox: huge
 *  bushy tail ending in a tapering, fur-textured, recolourable TIP + sparkles.
 */

export interface FSpec {
  tail: number;
  ruff: number;
  tuft: boolean;
  aura: number;
  sparkle: number;
}

export const F_STAGES: FSpec[] = [
  { tail: 0.55, ruff: 0.4, tuft: false, aura: 0, sparkle: 0 }, // 0
  { tail: 0.65, ruff: 0.45, tuft: false, aura: 0, sparkle: 0 }, // 1
  { tail: 0.8, ruff: 0.55, tuft: false, aura: 0, sparkle: 0 }, // 2
  { tail: 0.92, ruff: 0.65, tuft: false, aura: 0, sparkle: 0 }, // 3
  { tail: 1.05, ruff: 0.75, tuft: false, aura: 0, sparkle: 0 }, // 4
  { tail: 1.18, ruff: 0.85, tuft: false, aura: 0, sparkle: 0 }, // 5
  { tail: 1.3, ruff: 0.95, tuft: true, aura: 0, sparkle: 0 }, // 6
  { tail: 1.45, ruff: 1.05, tuft: true, aura: 0.4, sparkle: 2 }, // 7
  { tail: 1.6, ruff: 1.15, tuft: true, aura: 0.7, sparkle: 3 }, // 8
  { tail: 1.85, ruff: 1.3, tuft: true, aura: 1, sparkle: 5 }, // 9
];

export function Fox({ config, layout, stage, mood, uid, spec: specOverride }: RigProps & { spec?: FSpec }) {
  const C = COLOR_SLOT.fox;
  const S = STYLE_SLOT.fox;
  const A = ACCESSORY.fox;
  const body = pick(config.colors, C.body, "#FF8A65");
  // Default belly is pure white so the warm "Ventre crème" option reads clearly.
  const belly = pick(config.colors, C.belly, "#FFFFFF");
  const tailTip = pick(config.colors, C.tailTip, "#FFFFFF");
  const pattern = pick(config.styles, S.fur, "plain");
  const longTail = pick(config.styles, S.tail, "long") === "long";
  const has = (id: string) => config.accessories.includes(id);

  const spec = specOverride ?? F_STAGES[Math.max(0, Math.min(9, stage))];
  const tailF = spec.tail * (longTail ? 1 : 0.66);
  const { bodyCX, bodyCY, bodyRX, bodyRY, headCX, headCY, headR, neckX, neckY, eyeR } = layout;
  const legW = 7;

  // Bushy tail = fur clumps along a curve; it tapers to a pointed TIP whose fur
  // clumps carry the recolour, so the tint follows the tail silhouette to a point.
  const rootX = bodyCX + bodyRX * 0.55;
  const rootY = bodyCY - bodyRY * 0.22;
  const ctrlX = bodyCX + bodyRX * 1.28;
  const ctrlY = bodyCY + bodyRY * 0.18;
  const endX = bodyCX + bodyRX * 0.92;
  const endY = bodyCY + bodyRY * 0.98;
  const rB = 5 + 6 * tailF;
  const bez = (t: number): [number, number] => [
    (1 - t) * (1 - t) * rootX + 2 * (1 - t) * t * ctrlX + t * t * endX,
    (1 - t) * (1 - t) * rootY + 2 * (1 - t) * t * ctrlY + t * t * endY,
  ];
  const bodyClumps = Array.from({ length: 6 }).map((_, i) => {
    const t = (i / 5) * 0.72;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.75 + 0.35 * Math.sin(Math.PI * (t / 0.72))) };
  });
  const tipClumps = Array.from({ length: 4 }).map((_, i) => {
    const t = 0.76 + (i / 3) * 0.24;
    const [px, py] = bez(t);
    return { px, py, r: rB * (0.62 - 0.36 * (i / 3)) };
  });

  return (
    <g>
      {/* bushy tail behind body; recolourable tapering tip */}
      {spec.aura > 0 && <Aura id={`${uid}-tail`} cx={endX} cy={endY} r={rB * 1.3} color="#FFF1C4" opacity={spec.aura} />}
      {bodyClumps.map((b, i) => <circle key={`bu${i}`} cx={b.px} cy={b.py} r={b.r} fill={body} />)}
      {tipClumps.map((b, i) => <circle key={`tp${i}`} cx={b.px} cy={b.py} r={b.r} fill={tailTip} />)}

      {/* back legs */}
      {layout.legs.filter((l) => l.back).map((l, i) => (
        <Leg key={`b${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      {/* body */}
      <ellipse cx={bodyCX} cy={bodyCY} rx={bodyRX} ry={bodyRY} fill={body} />

      {/* fur pattern (clearly visible) */}
      {pattern === "spots" &&
        [
          [-0.42, -0.28],
          [0.28, -0.4],
          [0.02, -0.05],
          [-0.15, 0.28],
          [0.36, 0.05],
        ].map(([dx, dy], i) => (
          <ellipse key={i} cx={bodyCX + dx * bodyRX} cy={bodyCY + dy * bodyRY} rx={3.2} ry={3.2} fill="#A24A2C" opacity={0.85} />
        ))}
      {pattern === "stripes" &&
        [-0.4, -0.05, 0.3].map((dy, i) => (
          <path key={i} d={`M${bodyCX - bodyRX * 0.72} ${bodyCY + dy * bodyRY} Q${bodyCX} ${bodyCY + dy * bodyRY - 5} ${bodyCX + bodyRX * 0.72} ${bodyCY + dy * bodyRY}`} fill="none" stroke="#8A4326" strokeWidth={2.6} opacity={0.4} strokeLinecap="round" />
        ))}

      <ellipse cx={bodyCX} cy={bodyCY + bodyRY * 0.3} rx={bodyRX * 0.55} ry={bodyRY * 0.6} fill={belly} />
      {!layout.standing && <FoldedLegs bodyCX={bodyCX} bodyCY={bodyCY} bodyRX={bodyRX} color={body} hoof={INK} />}

      {/* neck */}
      <path d={`M${bodyCX} ${bodyCY - bodyRY * 0.5} L${headCX} ${headCY + headR * 0.4}`} stroke={body} strokeWidth={headR * 0.95} strokeLinecap="round" />

      {/* front legs */}
      {layout.legs.filter((l) => !l.back).map((l, i) => (
        <Leg key={`f${i}`} spec={l} w={legW} color={body} hoof={INK} />
      ))}

      {/* chest ruff */}
      <path d={`M${headCX - headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Q${headCX} ${headCY + headR * 1.5 * spec.ruff} ${headCX + headR * 0.5 * spec.ruff} ${headCY + headR * 0.7} Z`} fill={belly} />

      {/* head */}
      <ellipse cx={headCX} cy={headCY} rx={headR} ry={headR * 0.96} fill={body} />

      {/* ears — tall but ROUNDED tips, soft dark tip */}
      {([-1, 1] as const).map((d) => {
        const ex = headCX + d * headR * 0.58;
        const ey = headCY - headR * 0.5;
        const th = headR * ramp(stage, [[0, 0.55], [4, 0.72], [9, 0.78]]);
        const tipX = ex + d * th * 0.32;
        const tipY = ey - th;
        return (
          <g key={d}>
            <path d={`M${ex - d * headR * 0.5} ${ey} Q${tipX - d * th * 0.15} ${tipY - 2} ${tipX + d * 1.5} ${tipY + th * 0.14} Q${tipX + d * th * 0.12} ${tipY + th * 0.1} ${ex + d * headR * 0.05} ${ey - headR * 0.05} Z`} fill={body} />
            <path d={`M${tipX} ${tipY + th * 0.05} Q${tipX + d * th * 0.14} ${tipY + th * 0.12} ${tipX + d * 1} ${tipY + th * 0.3} Q${tipX - d * th * 0.06} ${tipY + th * 0.24} ${tipX} ${tipY + th * 0.05} Z`} fill={INK} />
            {spec.tuft && <Plume x={ex - d * headR * 0.02} y={ey - headR * 0.02} color={belly} len={5} wide={3} rot={d * 10} n={2} />}
          </g>
        );
      })}

      {/* white cheek ruff (grows) */}
      {([-1, 1] as const).map((d) => (
        <Plume key={d} x={headCX + d * headR * 0.72} y={headCY + headR * 0.3} color={belly} len={6 + 5 * spec.ruff} wide={5} rot={d * 60} n={3} />
      ))}

      {/* snout */}
      <ellipse cx={headCX} cy={headCY + headR * 0.42} rx={headR * 0.42} ry={headR * 0.32} fill={belly} />
      <ellipse cx={headCX} cy={headCY + headR * 0.3} rx={2.4} ry={2} fill={INK} />

      {/* face — softened, rounder eyes */}
      <Eyes cx={headCX} y={headCY - headR * 0.02} dx={headR * 0.4} r={eyeR * 1.05} mood={mood} sleepy={stage === 0} />
      <Cheeks cx={headCX} y={headCY + headR * 0.32} dx={headR * 0.62} r={headR * 0.13} />
      <Mouth cx={headCX} y={headCY + headR * 0.5} w={headR * 0.14} mood={mood} />

      {/* accessories */}
      {has(A.scarf) && (
        <g>
          <path d={`M${neckX - headR * 0.7} ${neckY} Q${neckX} ${neckY + 7} ${neckX + headR * 0.7} ${neckY}`} fill="none" stroke="#66BB6A" strokeWidth={5} strokeLinecap="round" />
          <path d={`M${neckX + headR * 0.35} ${neckY + 2} l3 10 l-6 1 Z`} fill="#4FA84E" />
        </g>
      )}
      {has(A.beanie) && (
        <g>
          <path d={`M${headCX - headR} ${headCY - headR * 0.5} Q${headCX} ${headCY - headR * 1.7} ${headCX + headR} ${headCY - headR * 0.5} Z`} fill="#4FC3F7" />
          <rect x={headCX - headR} y={headCY - headR * 0.62} width={headR * 2} height={headR * 0.3} rx={headR * 0.15} fill="#2E9BD6" />
          <circle cx={headCX} cy={headCY - headR * 1.55} r={3.4} fill="#FFF3E6" />
        </g>
      )}
      {has(A.boots) &&
        layout.legs.map((l, i) => (
          <g key={i}>
            <rect x={l.footX - 4} y={l.footY - 6} width={8} height={8} rx={3} fill="#8D5A3B" />
            <rect x={l.footX - 5} y={l.footY - 7} width={10} height={3} rx={1.5} fill="#C98A5B" />
          </g>
        ))}

      {spec.sparkle > 0 && (
        <Sparkles
          points={Array.from({ length: spec.sparkle }).map((_, i) => {
            const a = (i / spec.sparkle) * Math.PI * 2 + 1;
            return [bodyCX + Math.cos(a) * (bodyRX + 12), bodyCY + Math.sin(a) * (bodyRY + 8), 1.6 + (i % 3)] as [number, number, number];
          })}
        />
      )}
    </g>
  );
}
