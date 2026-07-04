import { useEffect, useState } from "react";
import { AssembleExercise } from "./exercises/AssembleExercise";
import { FirstLetterExercise } from "./exercises/FirstLetterExercise";
import { SpellSoundExercise } from "./exercises/SpellSoundExercise";
import { Dashboard } from "./components/Dashboard";
import { WhoIsPlaying } from "./components/WhoIsPlaying";
import { Mascot } from "./mascot/Mascot";
import { MascotGallery } from "./dev/MascotGallery";
import { Shop } from "./shop/Shop";
import { Picker } from "./shop/Picker";
import { useProfile } from "./hooks/useProfile";
import { EXERCISES, MODE_HINT } from "./levels";
import type { ExerciseId, View } from "./types";

const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

export default function App() {
  const [view, setView] = useState<View>({ kind: "hub" });
  const { profile, preview, children, activeId, switchChild } = useProfile();

  // Dev-only gallery of all growth stages, reachable at #stages (out of kid flow).
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const onHash = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  if (hash === "#stages")
    return <MascotGallery onClose={() => (window.location.hash = "")} />;

  // Who's-playing gate: siblings share the device. No active player ⇒ the
  // welcome screen (pick a child or create one). selectChild/createChild set
  // activeId, so this falls through next render.
  if (!activeId) return <WhoIsPlaying />;

  // First-run gate: until the active child picks a friend (species), the Picker
  // is the whole app. chooseSpecies() flips profile.chosen → true.
  if (!profile.chosen)
    return <Picker variant="first-run" onDone={() => setView({ kind: "hub" })} />;

  if (view.kind === "play") {
    const back = () => setView({ kind: "hub" });
    const exIdx = EXERCISES.findIndex((e) => e.id === view.exercise);
    const meta = EXERCISES[exIdx];
    // "Suivant" bumps to the next level; past an exercise's last level it rolls
    // into level 1 of the next exercise, and past the final one back to the hub.
    // The key ⇒ any of these remounts the exercise so a fresh run seeds cleanly.
    const next = () => {
      if (view.level < meta.levelCount)
        return setView({ kind: "play", exercise: view.exercise, level: view.level + 1 });
      const nextEx = EXERCISES[exIdx + 1];
      setView(nextEx ? { kind: "play", exercise: nextEx.id, level: 1 } : { kind: "hub" });
    };
    const key = `${view.exercise}-${view.level}`;
    if (view.exercise === "spell-sound")
      return (
        <SpellSoundExercise key={key} exercise={view.exercise} level={view.level} onBack={back} onNext={next} />
      );
    return meta.mode ? (
      <AssembleExercise key={key} exercise={view.exercise} mode={meta.mode} level={view.level} onBack={back} onNext={next} />
    ) : (
      <FirstLetterExercise key={key} exercise={view.exercise} level={view.level} onBack={back} onNext={next} />
    );
  }

  if (view.kind === "dashboard")
    return (
      <Dashboard
        onBack={() => setView({ kind: "hub" })}
        onShop={() => setView({ kind: "shop" })}
        onSwitch={() => setView({ kind: "pick" })}
      />
    );
  if (view.kind === "shop") return <Shop onBack={() => setView({ kind: "dashboard" })} />;
  if (view.kind === "pick")
    return (
      <Picker
        variant="switch"
        onDone={() => setView({ kind: "dashboard" })}
        onCancel={() => setView({ kind: "dashboard" })}
      />
    );

  const open = (exercise: ExerciseId, level: number) => setView({ kind: "play", exercise, level });

  return (
    <div
      className="flex min-h-[620px] w-full flex-col items-center overflow-hidden rounded-3xl px-5 pb-10 pt-8"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <div className="mb-4 flex w-full max-w-md items-center justify-between">
        <button
          onClick={switchChild}
          aria-label="Changer de joueur"
          className="max-w-[55%] truncate rounded-full bg-white/80 px-4 py-2 text-lg font-black text-[#5A3A1E] shadow active:scale-95"
        >
          👤 {children.find((c) => c.id === activeId)?.name ?? ""}
        </button>
        <button
          onClick={() => setView({ kind: "dashboard" })}
          aria-label="Mon copain et mes points"
          className="rounded-full bg-white/80 px-4 py-2 text-lg font-black text-[#5A3A1E] shadow active:scale-95"
        >
          ⭐ {profile.balance}
        </button>
      </div>

      <button
        onClick={() => setView({ kind: "dashboard" })}
        aria-label="Voir mon copain"
        className="mb-1 active:scale-95 [touch-action:manipulation]"
        style={{ background: "none", border: "none", cursor: "pointer" }}
      >
        <Mascot config={profile.config} mood="idle" size={112} />
      </button>
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
            {Array.from({ length: ex.levelCount }, (_, i) => i + 1).map((lvl) => {
              const pts = preview(ex.id, lvl);
              // First clear is the jackpot (10) — a big gold star pill; repeats
              // decay to a small coin pill so the child sees the reward up front.
              const jackpot = pts === 10;
              return (
                <button
                  key={lvl}
                  onClick={() => open(ex.id, lvl)}
                  aria-label={`Niveau ${lvl}, gagne ${pts} ${pts > 1 ? "étoiles" : "étoile"}`}
                  className="relative flex aspect-square items-center justify-center rounded-2xl bg-white/80 text-2xl font-black text-[#5A3A1E] shadow transition active:scale-95"
                >
                  {lvl}
                  <span
                    aria-hidden
                    className={
                      jackpot
                        ? "absolute -right-2 -top-2 flex items-center gap-0.5 rounded-full bg-[#FFC107] px-2 py-0.5 text-sm font-black leading-none text-[#4A3B00] shadow ring-2 ring-white"
                        : "absolute -right-1 -top-1 flex items-center gap-0.5 rounded-full bg-white px-1.5 py-0.5 text-[11px] font-black leading-none text-[#B07A00] shadow ring-1 ring-[#FFE08A]"
                    }
                  >
                    +{pts}
                    <span>{jackpot ? "⭐" : "🪙"}</span>
                  </span>
                </button>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}
