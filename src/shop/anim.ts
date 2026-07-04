/* -------------------------------------------------------------------------- */
/* Shop micro-animations — WAAPI only, OFF the React render path (invariant 2). */
/* Call these synchronously from event handlers, exactly like Tile.tsx.         */
/* prefers-reduced-motion is respected here so every caller inherits it.        */
/* -------------------------------------------------------------------------- */

const reducedMotion = (): boolean =>
  typeof window !== "undefined" &&
  typeof window.matchMedia === "function" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** A happy little bounce — used on the live preview when something is equipped. */
const POP: Keyframe[] = [
  { transform: "scale(1)" },
  { transform: "scale(1.12)" },
  { transform: "scale(1)" },
];

/** A tactile squish — used on tiles/buttons on pointerdown for instant feel. */
const PRESS: Keyframe[] = [
  { transform: "scale(1)" },
  { transform: "scale(0.94)" },
  { transform: "scale(1)" },
];

export function pop(el: Element | null | undefined): void {
  if (!el || reducedMotion()) return;
  el.animate(POP, { duration: 260, easing: "ease-out" });
}

export function press(el: Element | null | undefined): void {
  if (!el || reducedMotion()) return;
  el.animate(PRESS, { duration: 130, easing: "ease-out" });
}
