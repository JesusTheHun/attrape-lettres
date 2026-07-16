import type { ReactNode, RefObject } from "react";

interface GameFrameProps {
  onBack: () => void;
  done: number;
  total: number;
  /**
   * Per-round first-try flags (index = round). A round's star starts winnable,
   * goes gold when cleared first-try, and greys out THE INSTANT a wrong tap
   * lands — that immediacy is the point: the child sees the bonus slip at the
   * moment of the miss, not at the recap.
   */
  stars: ReadonlyArray<boolean>;
  canvasRef: RefObject<HTMLCanvasElement>;
  children: ReactNode;
}

const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

/** Greys a lost star; keeps it visible so the round still counts as played. */
const LOST = { filter: "grayscale(1)", opacity: 0.45 } as const;

export function GameFrame({ onBack, done, total, stars, canvasRef, children }: GameFrameProps) {
  return (
    <div
      className="relative flex min-h-[620px] w-full flex-col items-center overflow-hidden rounded-3xl"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <canvas
        ref={canvasRef}
        className="pointer-events-none absolute inset-0 h-full w-full"
        style={{ zIndex: 40 }}
      />
      <div className="relative z-[41] flex w-full items-center gap-3 px-4 pt-4">
        <button
          onClick={onBack}
          className="shrink-0 rounded-full bg-white/70 px-4 py-2 text-lg font-bold text-[#5A3A1E] shadow"
        >
          ← Menu
        </button>
        <div className="flex min-w-0 flex-1 flex-wrap items-center justify-center gap-x-1 gap-y-0.5">
          {Array.from({ length: total }).map((_, i) => {
            const fontSize = total > 9 ? 16 : 20;
            // Played rounds keep their verdict; the live round's star pulses
            // while winnable and greys the instant a wrong tap lands; the
            // rest are dots still to come.
            if (i < done || (i === done && !stars[i])) {
              return (
                <span key={i} style={{ fontSize, ...(stars[i] ? null : LOST) }}>
                  ⭐
                </span>
              );
            }
            if (i === done) {
              return (
                <span key={i} className="motion-safe:animate-pulse" style={{ fontSize, opacity: 0.8 }}>
                  ⭐
                </span>
              );
            }
            return (
              <span key={i} style={{ fontSize, opacity: 0.28 }}>
                •
              </span>
            );
          })}
        </div>
        <div className="hidden w-[84px] shrink-0 sm:block" aria-hidden />
      </div>
      {children}
    </div>
  );
}
