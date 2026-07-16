/* -------------------------------------------------------------------------- */
/* Shop micro-animations — WAAPI only, OFF the React render path (invariant 2). */
/* Call these synchronously from event handlers, exactly like Tile.tsx.         */
/* prefers-reduced-motion is respected here so every caller inherits it.        */
/* -------------------------------------------------------------------------- */

export const reducedMotion = (): boolean =>
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

/* -------------------------------------------------------------------------- */
/* Growth celebration — a cloud "poof" + a ring of sparkles bursting from the   */
/* mascot. Spawned as throwaway DOM on document.body (fixed, pointer-events:    */
/* none) and driven by WAAPI, so it never touches the React tree; the layer     */
/* removes itself once the last particle finishes. Reduced-motion → nothing.    */
/* -------------------------------------------------------------------------- */

const SPARKLES = ["✨", "⭐", "🌟", "💫"];

/* -------------------------------------------------------------------------- */
/* Purchase ceremony — a handful of stars fly from the WALLET to the mascot,    */
/* so a child SEES stars leave their purse when something is bought. Throwaway  */
/* DOM on document.body driven by WAAPI, exactly like growBurst below.          */
/* -------------------------------------------------------------------------- */

export function starFlight(
  from: Element | null | undefined,
  to: Element | null | undefined,
  count: number
): void {
  if (!from || !to || reducedMotion() || typeof document === "undefined") return;
  const a = from.getBoundingClientRect();
  const b = to.getBoundingClientRect();
  if (a.width === 0 || b.width === 0) return;
  const x0 = a.left + a.width / 2;
  const y0 = a.top + a.height / 2;
  const x1 = b.left + b.width / 2;
  const y1 = b.top + b.height / 2;

  const layer = document.createElement("div");
  layer.setAttribute("aria-hidden", "true");
  layer.style.cssText =
    "position:fixed;inset:0;pointer-events:none;z-index:60;overflow:visible;";
  document.body.appendChild(layer);

  let pending = 0;
  for (let i = 0; i < count; i++) {
    const el = document.createElement("span");
    el.textContent = "⭐";
    el.style.cssText =
      `position:fixed;left:${x0}px;top:${y0}px;font-size:22px;line-height:1;` +
      "will-change:transform,opacity;";
    layer.appendChild(el);
    pending++;
    // Each star arcs on its own bow: the midpoint bulges sideways/upward a bit
    // more per star, so the flock fans out instead of forming a single file.
    const bow = (i % 2 === 0 ? 1 : -1) * (14 + i * 7);
    const mx = (x1 - x0) / 2 + bow;
    const my = (y1 - y0) / 2 - 36 - i * 4;
    const anim = el.animate(
      [
        { transform: "translate(-50%,-50%) scale(0.5)", opacity: 0 },
        {
          transform: `translate(calc(-50% + ${mx}px),calc(-50% + ${my}px)) scale(1.15)`,
          opacity: 1,
          offset: 0.5,
        },
        {
          transform: `translate(calc(-50% + ${x1 - x0}px),calc(-50% + ${y1 - y0}px)) scale(0.35)`,
          opacity: 0.2,
        },
      ],
      { duration: 620 + i * 45, delay: i * 70, easing: "cubic-bezier(.3,.6,.4,1)", fill: "forwards" }
    );
    anim.addEventListener("finish", () => {
      if (--pending <= 0) layer.remove();
    });
  }
}

export function growBurst(anchor: Element | null | undefined): void {
  if (!anchor || reducedMotion() || typeof document === "undefined") return;
  const rect = anchor.getBoundingClientRect();
  if (rect.width === 0) return;
  const cx = rect.left + rect.width / 2;
  const cy = rect.top + rect.height / 2;

  const layer = document.createElement("div");
  layer.setAttribute("aria-hidden", "true");
  layer.style.cssText =
    "position:fixed;inset:0;pointer-events:none;z-index:60;overflow:visible;";
  document.body.appendChild(layer);

  let pending = 0;
  const spawn = (
    glyph: string,
    size: number,
    frames: Keyframe[],
    opts: KeyframeAnimationOptions
  ) => {
    const el = document.createElement("span");
    el.textContent = glyph;
    el.style.cssText =
      `position:fixed;left:${cx}px;top:${cy}px;font-size:${size}px;line-height:1;` +
      "will-change:transform,opacity;";
    layer.appendChild(el);
    pending++;
    const done = () => {
      if (--pending <= 0) layer.remove();
    };
    el.animate(frames, opts).addEventListener("finish", done);
  };

  // Cloud puffs rising and fading — the "poof".
  const cloud = rect.width * 0.42;
  for (const [dx, delay, scale] of [
    [0, 0, 1.3],
    [-cloud * 0.5, 60, 1],
    [cloud * 0.5, 120, 1],
  ] as const) {
    spawn(
      "☁️",
      cloud,
      [
        { transform: `translate(calc(-50% + ${dx}px),-40%) scale(0.3)`, opacity: 0 },
        {
          transform: `translate(calc(-50% + ${dx}px),-70%) scale(${scale})`,
          opacity: 0.95,
          offset: 0.4,
        },
        { transform: `translate(calc(-50% + ${dx}px),-120%) scale(${scale * 1.15})`, opacity: 0 },
      ],
      { duration: 900, delay, easing: "ease-out", fill: "forwards" }
    );
  }

  // Sparkles flung outward in a ring.
  const N = 12;
  for (let i = 0; i < N; i++) {
    const angle = (i / N) * Math.PI * 2 + (i % 2) * 0.26;
    const dist = rect.width * (0.55 + (i % 3) * 0.14);
    const dx = Math.cos(angle) * dist;
    const dy = Math.sin(angle) * dist - rect.height * 0.12;
    spawn(
      SPARKLES[i % SPARKLES.length],
      16 + (i % 3) * 6,
      [
        { transform: "translate(-50%,-50%) scale(0.2) rotate(0deg)", opacity: 0 },
        {
          transform: `translate(calc(-50% + ${dx * 0.6}px),calc(-50% + ${dy * 0.6}px)) scale(1) rotate(90deg)`,
          opacity: 1,
          offset: 0.35,
        },
        {
          transform: `translate(calc(-50% + ${dx}px),calc(-50% + ${dy}px)) scale(0.3) rotate(200deg)`,
          opacity: 0,
        },
      ],
      { duration: 720 + (i % 3) * 120, easing: "cubic-bezier(.2,.7,.3,1)", fill: "forwards" }
    );
  }
}
