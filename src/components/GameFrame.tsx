import type { ReactNode, RefObject } from "react";

interface GameFrameProps {
  onBack: () => void;
  done: number;
  total: number;
  canvasRef: RefObject<HTMLCanvasElement>;
  children: ReactNode;
}

const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

export function GameFrame({ onBack, done, total, canvasRef, children }: GameFrameProps) {
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
      <div className="relative z-[41] flex w-full items-center justify-between px-4 pt-4">
        <button
          onClick={onBack}
          className="rounded-full bg-white/70 px-4 py-2 text-lg font-bold text-[#5A3A1E] shadow"
        >
          ← Menu
        </button>
        <div className="flex gap-1">
          {Array.from({ length: total }).map((_, i) => (
            <span key={i} style={{ fontSize: 20, opacity: i < done ? 1 : 0.28 }}>
              {i < done ? "⭐" : "•"}
            </span>
          ))}
        </div>
        <div className="w-[84px]" aria-hidden />
      </div>
      {children}
    </div>
  );
}
