import { useEffect, useRef } from "react";

/**
 * One-shot WAAPI "pop" flourish that fires once on mount, entirely OFF the React
 * render path (invariant #2 — no re-render loop to animate). Honors
 * prefers-reduced-motion (invariant #6). Attach the returned ref to whatever
 * element should celebrate into view.
 */
const POP_IN: Keyframe[] = [
  { transform: "scale(0.4)", opacity: 0 },
  { transform: "scale(1.18)", opacity: 1, offset: 0.68 },
  { transform: "scale(1)", opacity: 1 },
];

const POP_OPTIONS: KeyframeAnimationOptions = {
  duration: 480,
  easing: "cubic-bezier(.2,1.35,.4,1)",
};

export function usePopFlourish<T extends HTMLElement>(
  keyframes: Keyframe[] = POP_IN,
  options: KeyframeAnimationOptions = POP_OPTIONS
) {
  const ref = useRef<T>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const reduce =
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    if (reduce) return;
    const anim = el.animate(keyframes, options);
    return () => anim.cancel();
    // Mount-only: a fresh flourish each time the element appears.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return ref;
}
