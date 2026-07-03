import { useCallback, useRef, type ReactNode } from "react";
import type { Verdict } from "../types";

interface TileProps {
  children: ReactNode;
  bg: string;
  ink: string;
  disabled?: boolean;
  highlight?: boolean;
  /** Runs synchronously on pointerdown; return "reject" to trigger a shake. */
  onPick: () => Verdict;
  size?: number | string;
  fontSize?: number | string;
  ariaLabel?: string;
}

const press: Keyframe[] = [
  { transform: "scale(1)" },
  { transform: "scale(0.9)" },
  { transform: "scale(1)" },
];
const shake: Keyframe[] = [
  { transform: "translateX(0)" },
  { transform: "translateX(-8px)" },
  { transform: "translateX(8px)" },
  { transform: "translateX(-5px)" },
  { transform: "translateX(0)" },
];

export function Tile({ children, bg, ink, disabled, highlight, onPick, size, fontSize, ariaLabel }: TileProps) {
  const ref = useRef<HTMLButtonElement>(null);

  const handle = useCallback(
    (_e: React.PointerEvent) => {
      if (disabled) return;
      const el = ref.current;
      el?.animate(press, { duration: 130, easing: "ease-out" });
      if (onPick() === "reject") {
        el?.animate(shake, { duration: 300, easing: "ease-in-out" });
      }
    },
    [disabled, onPick]
  );

  const dim = size ?? "clamp(92px, 27vw, 150px)";

  return (
    <button
      ref={ref}
      onPointerDown={handle}
      disabled={disabled}
      aria-label={ariaLabel}
      className="flex select-none items-center justify-center font-black outline-none [touch-action:none] [-webkit-tap-highlight-color:transparent] disabled:opacity-40"
      style={{
        width: dim,
        height: dim,
        fontSize: fontSize ?? "clamp(30px, 9vw, 64px)",
        background: bg,
        color: ink,
        borderRadius: 28,
        border: "none",
        cursor: disabled ? "default" : "pointer",
        boxShadow: highlight
          ? "0 0 0 6px #66BB6A, 0 10px 22px rgba(0,0,0,0.18)"
          : "0 8px 0 rgba(0,0,0,0.12), 0 12px 20px rgba(0,0,0,0.14)",
        transition: "box-shadow 0.15s",
      }}
    >
      {children}
    </button>
  );
}
