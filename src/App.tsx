import { useState } from "react";
import { AssembleExercise } from "./exercises/AssembleExercise";
import { FirstLetterExercise } from "./exercises/FirstLetterExercise";
import { EXERCISES, MODE_HINT } from "./levels";
import type { ExerciseId, View } from "./types";

const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

export default function App() {
  const [view, setView] = useState<View>({ kind: "hub" });

  if (view.kind === "play") {
    const back = () => setView({ kind: "hub" });
    const meta = EXERCISES.find((e) => e.id === view.exercise)!;
    return meta.mode ? (
      <AssembleExercise mode={meta.mode} level={view.level} onBack={back} />
    ) : (
      <FirstLetterExercise level={view.level} onBack={back} />
    );
  }

  const open = (exercise: ExerciseId, level: number) => setView({ kind: "play", exercise, level });

  return (
    <div
      className="flex min-h-[620px] w-full flex-col items-center overflow-hidden rounded-3xl px-5 pb-10 pt-8"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <div className="mb-1 leading-none" style={{ fontSize: "clamp(56px,18vw,90px)" }}>🦉</div>
      <h1 className="m-0 font-black text-[#5A3A1E]" style={{ fontSize: "clamp(28px,8vw,44px)" }}>
        Attrape-Lettres
      </h1>
      <p className="mb-6 mt-1 text-[#7A5A3A]">Choisis un jeu et un niveau.</p>

      {EXERCISES.map((ex) => (
        <section key={ex.id} className="mb-6 w-full max-w-md">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span style={{ fontSize: 26 }}>{ex.emoji}</span>
            <h2 className="m-0 text-xl font-extrabold text-[#5A3A1E]">{ex.name}</h2>
            {ex.mode && <span className="text-sm text-[#9A7A5A]">· {MODE_HINT[ex.mode]}</span>}
          </div>
          <div className="grid grid-cols-5 gap-2">
            {Array.from({ length: ex.levelCount }, (_, i) => i + 1).map((lvl) => (
              <button
                key={lvl}
                onClick={() => open(ex.id, lvl)}
                className="flex aspect-square items-center justify-center rounded-2xl bg-white/80 text-2xl font-black text-[#5A3A1E] shadow transition active:scale-95"
              >
                {lvl}
              </button>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
