import { useState } from "react";
import { Mascot } from "../mascot/Mascot";
import { GROWTH_STAGES, type MascotConfig, type Mood, type Species } from "../types";

/* -------------------------------------------------------------------------- */
/* Dev demo — every growth stage of every mascot, side by side.                 */
/* Not part of the kid flow: reachable only at URL hash #stages (App routes it).*/
/* Lets you eyeball that stage 0 (baby) → stage 9 (majestic) reads as a         */
/* different design per step, per species, and try the moods.                   */
/* -------------------------------------------------------------------------- */

const STAGE_BG = "linear-gradient(180deg,#FFF3E0 0%,#EAF4FF 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const SPECIES: { species: Species; label: string }[] = [
  { species: "unicorn", label: "🦄 Licorne" },
  { species: "cat", label: "🐱 Chat" },
  { species: "fox", label: "🦊 Renard" },
];

const MOODS: Mood[] = ["idle", "happy", "cheer"];

const cfg = (species: Species, stage: number): MascotConfig => ({
  species,
  stage,
  colors: {},
  styles: {},
  accessories: [],
});

const STAGES = Array.from({ length: GROWTH_STAGES }, (_, i) => i);

export function MascotGallery({ onClose }: { onClose: () => void }) {
  const [mood, setMood] = useState<Mood>("idle");

  return (
    <div
      className="min-h-screen w-full px-4 py-6"
      style={{ background: STAGE_BG, fontFamily: ROUNDED, color: "#5A3A1E" }}
    >
      <div className="mx-auto max-w-6xl">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h1 className="m-0 text-2xl font-black">Évolutions des mascottes</h1>
          <div className="flex items-center gap-2">
            {MOODS.map((m) => (
              <button
                key={m}
                onClick={() => setMood(m)}
                className="rounded-full px-3 py-1.5 text-sm font-black active:scale-95"
                style={{
                  background: mood === m ? "#66BB6A" : "rgba(255,255,255,0.85)",
                  color: mood === m ? "#fff" : "#5A3A1E",
                  border: "none",
                }}
              >
                {m}
              </button>
            ))}
            <button
              onClick={onClose}
              className="rounded-full bg-white/85 px-4 py-1.5 text-sm font-black active:scale-95"
            >
              ✕ Fermer
            </button>
          </div>
        </div>

        {SPECIES.map(({ species, label }) => (
          <section key={species} className="mb-8">
            <h2 className="mb-3 text-xl font-black">{label}</h2>
            <div
              className="grid gap-3"
              style={{ gridTemplateColumns: "repeat(auto-fill, minmax(120px, 1fr))" }}
            >
              {STAGES.map((stage) => (
                <div
                  key={stage}
                  className="flex flex-col items-center gap-1 rounded-2xl bg-white/70 p-3 shadow"
                >
                  <Mascot config={cfg(species, stage)} mood={mood} size={104} />
                  <span className="text-sm font-black">Niveau {stage + 1}</span>
                  <span className="text-xs font-bold text-[#9A7A5A]">
                    {stage === 0 ? "bébé" : stage === GROWTH_STAGES - 1 ? "majestueux" : `stade ${stage}`}
                  </span>
                </div>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
