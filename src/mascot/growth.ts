import type { MascotConfig, Mood } from "../types";

/**
 * Growth model — CONTINUOUS PER-STAGE interpolation + a per-stage feature
 * timeline. Every stage 0..9 gets its OWN geometry (head:body ratio, limb
 * length, posture, eye size) via `ramp()` anchor curves, so a child clicking
 * 3→4→5 sees a real change each time. On top of that each species owns a
 * STAGE_SPEC selecting part variants (horn height, wing scale, tail step…).
 *
 * Silhouette arc: stade 0 fully folded helpless baby (oversized head, huge
 * eyes, stubby/absent limbs) → wobbling to rise (1) → crouched first steps (2)
 * → upright youngster (3-5) → slim adult (6) → proud (7-8) → majestic (9).
 *
 * Owned by AGENT A.
 */

export const INK = "#5A3A1E";
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;
const clamp = (n: number, lo: number, hi: number): number => Math.max(lo, Math.min(hi, n));

/** Read a colour/style slot with a per-species default. */
export const pick = (m: Record<string, string>, slot: string, fallback: string): string =>
  m[slot] ?? fallback;

/** Linear blend between two #rrggbb hex colours (t: 0=a … 1=b). */
export function mix(a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const r = Math.round(((pa >> 16) & 255) + (((pb >> 16) & 255) - ((pa >> 16) & 255)) * t);
  const g = Math.round(((pa >> 8) & 255) + (((pb >> 8) & 255) - ((pa >> 8) & 255)) * t);
  const c = Math.round((pa & 255) + ((pb & 255) - (pa & 255)) * t);
  return "#" + ((1 << 24) + (r << 16) + (g << 8) + c).toString(16).slice(1);
}

/** Piecewise-linear interpolation over [stage, value] anchor stops. */
export type Stop = [number, number];
export function ramp(stage: number, stops: Stop[]): number {
  const first = stops[0];
  const last = stops[stops.length - 1];
  if (stage <= first[0]) return first[1];
  if (stage >= last[0]) return last[1];
  for (let i = 0; i < stops.length - 1; i++) {
    const [s0, v0] = stops[i];
    const [s1, v1] = stops[i + 1];
    if (stage >= s0 && stage <= s1) return lerp(v0, v1, (stage - s0) / (s1 - s0));
  }
  return last[1];
}

/**
 * Overall on-screen size multiplier per stage — pivoted at the feet so the
 * creature grows UPWARD off the ground line. This is a big part of why the
 * later stages feel like a reward: the mascot visibly gets bigger, not just
 * more decorated. Stades 0-2 stay 1 (the untouched early game).
 */
const STAGE_SCALE = [1, 1, 1, 1.0, 1.05, 1.09, 1.13, 1.18, 1.23, 1.3];
export const stageScale = (stage: number): number => STAGE_SCALE[clamp(stage, 0, 9)];

export type Pose = "lying" | "wobbly" | "standing" | "proud";

export interface LegSpec {
  hipX: number;
  hipY: number;
  footX: number;
  footY: number;
  bend: number;
  side: number;
  back: boolean;
}

export interface Layout {
  pose: Pose;
  /** false only for lying babies (stades 0-1). */
  standing: boolean;
  bodyCX: number;
  bodyCY: number;
  bodyRX: number;
  bodyRY: number;
  headCX: number;
  headCY: number;
  headR: number;
  /** Centralised huge-baby → small-adult eye radius. */
  eyeR: number;
  neckX: number;
  neckY: number;
  feetY: number;
  legs: LegSpec[];
}

export interface RigProps {
  config: MascotConfig;
  layout: Layout;
  stage: number;
  mood: Mood;
  /** Instance-unique prefix for <defs> gradient ids (many mascots per page). */
  uid: string;
}

export function poseFor(stage: number): Pose {
  if (stage <= 1) return "lying";
  if (stage <= 3) return "wobbly";
  if (stage <= 6) return "standing";
  return "proud";
}

function quadLegs(
  bodyCX: number,
  bodyCY: number,
  bodyRY: number,
  feetY: number,
  sf: number,
  sb: number,
  bend: number
): LegSpec[] {
  const hipY = bodyCY + bodyRY * 0.4;
  const mk = (dx: number, back: boolean): LegSpec => {
    const side = Math.sign(dx) || 1;
    return { hipX: bodyCX + dx, hipY, footX: bodyCX + dx + side * bend * 0.6, footY: feetY, bend, side, back };
  };
  return [mk(-sb, true), mk(sb, true), mk(-sf, false), mk(sf, false)];
}

/** Fully-computed, per-stage-distinct geometry. */
export function layoutFor(stage: number): Layout {
  const s = clamp(stage, 0, 9);
  const pose = poseFor(s);
  // Head shrinks a LOT from baby→adult; body grows → dramatic baby ratio.
  const headR = ramp(s, [[0, 28], [1, 27], [2, 24.5], [3, 22], [4, 20], [5, 18.5], [6, 17.5], [7, 17], [9, 16.5]]);
  const bodyRX = ramp(s, [[0, 19], [1, 19], [2, 17.5], [3, 18], [4, 19], [5, 19.7], [6, 20.5], [9, 22]]);
  const bodyRY = ramp(s, [[0, 12], [1, 12.5], [2, 15], [3, 16.5], [4, 18], [5, 19], [6, 20], [7, 21], [9, 23]]);
  const eyeR = headR * ramp(s, [[0, 0.31], [1, 0.3], [2, 0.26], [3, 0.22], [4, 0.19], [5, 0.17], [6, 0.16], [9, 0.155]]);

  if (pose === "lying") {
    const bodyCX = 50;
    const bodyCY = 80;
    const lyRX = bodyRX + 8; // wide splayed belly
    const lyRY = bodyRY * 0.82; // flatter
    // stade 0 head resting low & forward; stade 1 head lifted (rising).
    const headCX = ramp(s, [[0, 59], [1, 54]]);
    const headCY = ramp(s, [[0, 70], [1, 56]]);
    return {
      pose,
      standing: false,
      bodyCX,
      bodyCY,
      bodyRX: lyRX,
      bodyRY: lyRY,
      headCX,
      headCY,
      headR,
      eyeR,
      neckX: (bodyCX + headCX) / 2,
      neckY: (bodyCY - lyRY * 0.4 + headCY) / 2,
      feetY: 91,
      legs: [],
    };
  }

  const feetY = 94;
  const legLen = ramp(s, [[2, 4.5], [3, 7.5], [4, 10.5], [5, 12.5], [6, 14], [7, 15], [8, 16], [9, 17]]);
  const bodyCX = 50;
  const bodyCY = feetY - legLen - bodyRY;
  const neckExtra = ramp(s, [[6, 0], [7, 1.5], [8, 3], [9, 4.5]]);
  const headCY = bodyCY - bodyRY * 0.7 - headR * 0.58 - neckExtra;
  const sf = ramp(s, [[2, 10.5], [3, 9.8], [4, 9], [6, 8], [9, 8]]);
  const sb = ramp(s, [[2, 17], [3, 16], [4, 15], [6, 14], [9, 13]]);
  const bend = ramp(s, [[2, 6], [3, 4.2], [4, 2.6], [5, 1.6], [6, 1], [7, 0.5], [9, 0]]);
  return {
    pose,
    standing: true,
    bodyCX,
    bodyCY,
    bodyRX,
    bodyRY,
    headCX: 50,
    headCY,
    headR,
    eyeR,
    neckX: 50,
    neckY: bodyCY - bodyRY * 0.55,
    feetY,
    legs: quadLegs(bodyCX, bodyCY, bodyRY, feetY, sf, sb, bend),
  };
}
