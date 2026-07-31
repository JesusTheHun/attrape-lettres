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
  /**
   * Optional "hear it first" affordance. When set, a separate full-width
   * Écouter button is stacked BELOW the tile (its own finger-sized target,
   * gap-separated so it can't be mis-tapped for the pick) that speaks the
   * tile's sound without committing the pick. Lets a child audition a
   * letter/syllable before choosing.
   */
  onPreview?: () => void;
  previewLabel?: string;
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

export function Tile({
  children,
  bg,
  ink,
  disabled,
  highlight,
  onPick,
  onPreview,
  previewLabel,
  size,
  fontSize,
  ariaLabel,
}: TileProps) {
  const ref = useRef<HTMLButtonElement>(null);
  const listenRef = useRef<HTMLButtonElement>(null);

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

  // A standalone sibling button — no propagation to worry about — so hearing a
  // tile can never commit its pick. Speaks on pointerdown, same beat as pick.
  const handlePreview = useCallback(() => {
    if (disabled) return;
    listenRef.current?.animate(press, { duration: 130, easing: "ease-out" });
    onPreview?.();
  }, [disabled, onPreview]);

  const dim = size ?? "clamp(92px, 27vw, 150px)";

  const tile = (
    <button
      ref={ref}
      onPointerDown={handle}
      disabled={disabled}
      aria-label={ariaLabel}
      className="flex select-none items-center justify-center font-black outline-none [touch-action:none] [-webkit-tap-highlight-color:transparent] disabled:opacity-40"
      style={{
        minWidth: dim,
        width: "auto",
        height: dim,
        padding: "0 clamp(10px, 3vw, 20px)",
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

  if (!onPreview) return tile;

  // Column: big pick tile on top, its own Écouter button below with a real
  // gap. Both are finger-sized, single-purpose targets that never overlap.
  return (
    <div className="flex flex-col items-stretch gap-2" style={{ minWidth: dim }}>
      {tile}
      <button
        ref={listenRef}
        type="button"
        onPointerDown={handlePreview}
        disabled={disabled}
        aria-label={previewLabel ?? "Écouter"}
        className="flex select-none items-center justify-center gap-1 rounded-full bg-white font-bold text-[#5A3A1E] outline-none [touch-action:none] [-webkit-tap-highlight-color:transparent] disabled:opacity-40"
        style={{
          height: "clamp(40px, 11vw, 52px)",
          fontSize: "clamp(16px, 4.5vw, 22px)",
          border: "none",
          cursor: disabled ? "default" : "pointer",
          boxShadow: "0 3px 0 rgba(0,0,0,0.10), 0 5px 12px rgba(0,0,0,0.12)",
        }}
      >
        🔊
      </button>
    </div>
  );
}
