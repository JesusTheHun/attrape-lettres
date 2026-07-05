import { useEffect, useId, useRef } from "react";
import type { MascotProps, Species } from "../types";
import { layoutFor, stageScale } from "./growth";
import { Unicorn } from "./Unicorn";
import { Cat } from "./Cat";
import { Fox } from "./Fox";

/**
 * Parametric, layered inline-SVG mascot — drop-in for <Ollie mood>.
 * Dispatches on config.species; growth is a PER-STAGE FEATURE TIMELINE
 * (see growth.ts + each species' STAGE_SPEC). Mood animation is WAAPI, run in
 * an effect OFF the React render path, and disabled under prefers-reduced-motion.
 *
 * Owned by AGENT A.
 */

const LABELS: Record<Species, string> = {
  unicorn: "Ma licorne",
  cat: "Mon chat",
  fox: "Mon renard",
};

const bob: Keyframe[] = [
  { transform: "translateY(0) rotate(-1.5deg)" },
  { transform: "translateY(-5px) rotate(1.5deg)", offset: 0.5 },
  { transform: "translateY(0) rotate(-1.5deg)" },
];
const pop: Keyframe[] = [
  { transform: "scale(1)" },
  { transform: "scale(1.16) rotate(4deg)", offset: 0.4 },
  { transform: "scale(0.98)", offset: 0.72 },
  { transform: "scale(1)" },
];
const cheer: Keyframe[] = [
  { transform: "scale(1) rotate(0deg)" },
  { transform: "scale(1.2) rotate(-6deg)", offset: 0.25 },
  { transform: "scale(1.1) rotate(6deg)", offset: 0.5 },
  { transform: "scale(1.22) rotate(-4deg)", offset: 0.74 },
  { transform: "scale(1) rotate(0deg)" },
];

export function Mascot({ config, mood, size = 88, preview = false }: MascotProps) {
  const ref = useRef<SVGSVGElement>(null);
  const uid = useId().replace(/:/g, "");

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Shop thumbnails never bob — a grid of jittering mascots is noise, not signal.
    if (preview) return;
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    if (reduced) return;
    el.style.transformOrigin = "50% 82%";
    const anim =
      mood === "idle"
        ? el.animate(bob, { duration: 2600, easing: "ease-in-out", iterations: Infinity })
        : mood === "cheer"
          ? el.animate(cheer, { duration: 680, easing: "ease-out" })
          : el.animate(pop, { duration: 480, easing: "ease-out" });
    return () => anim.cancel();
  }, [mood, preview]);

  const layout = layoutFor(config.stage);
  const rig =
    config.species === "cat" ? (
      <Cat config={config} layout={layout} stage={config.stage} mood={mood} uid={uid} preview={preview} />
    ) : config.species === "fox" ? (
      <Fox config={config} layout={layout} stage={config.stage} mood={mood} uid={uid} preview={preview} />
    ) : (
      <Unicorn config={config} layout={layout} stage={config.stage} mood={mood} uid={uid} preview={preview} />
    );

  // Per-stage growth scale, pivoted at the feet (see growth.stageScale).
  const k = stageScale(config.stage);
  const pivot = layout.feetY;

  return (
    <svg
      ref={ref}
      role="img"
      aria-label={LABELS[config.species]}
      viewBox="0 0 100 100"
      width={size}
      height={size}
      className="select-none"
      style={{ overflow: "visible", display: "block" }}
    >
      <ellipse cx={50} cy={layout.feetY + 3.5} rx={layout.bodyRX * 0.92 * k} ry={3.6} fill="#000" opacity={0.1} />
      <g transform={`translate(50 ${pivot}) scale(${k}) translate(-50 ${-pivot})`}>{rig}</g>
    </svg>
  );
}
