import { useRef } from "react";
import { useProfile } from "../hooks/useProfile";
import { Mascot } from "../mascot/Mascot";
import { GROWTH_STAGES, type Species } from "../types";
import { press } from "./anim";

/* -------------------------------------------------------------------------- */
/* Choose / switch mascot.                                                      */
/*   variant "first-run": the whole-app gate — "Choisis ton copain".            */
/*   variant "switch"   : reachable from the dashboard. Switching is            */
/*                        NON-DESTRUCTIVE — each species keeps its own growth,   */
/*                        look and items, so you can switch back anytime.        */
/* Each card shows that mascot at ITS real current look (grown, dressed).       */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 40%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const CHOICES: { species: Species; name: string }[] = [
  { species: "unicorn", name: "Licorne" },
  { species: "cat", name: "Chat" },
  { species: "fox", name: "Renard" },
];

function Choice({
  species,
  name,
  onPick,
}: {
  species: Species;
  name: string;
  onPick: () => void;
}) {
  const { profile } = useProfile();
  const ref = useRef<HTMLButtonElement>(null);
  const progress = profile.species[species];
  const isCurrent = profile.chosen && profile.current === species;
  // A mascot that's been played shows real growth; a fresh one is a stage-0 baby.
  const grown = progress.config.stage > 0 || progress.owned.length > 0;

  return (
    <button
      ref={ref}
      type="button"
      aria-label={`Choisir ${name}`}
      onPointerDown={() => press(ref.current)}
      onClick={onPick}
      className="flex w-full items-center gap-5 rounded-3xl p-5 text-left active:scale-[0.98] [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
      style={{
        background: "rgba(255,255,255,0.9)",
        border: isCurrent ? "3px solid #66BB6A" : "3px solid transparent",
        cursor: "pointer",
        boxShadow: "0 8px 18px rgba(0,0,0,0.10)",
      }}
    >
      <Mascot config={progress.config} mood="idle" size={84} />
      <span className="flex flex-1 flex-col">
        <span className="text-2xl font-black" style={{ color: INK }}>
          {name}
        </span>
        <span className="text-sm font-bold" style={{ color: "#9A7A5A" }}>
          {grown ? `Niveau ${progress.config.stage + 1}/${GROWTH_STAGES}` : "Tout neuf"}
        </span>
      </span>
      {isCurrent && (
        <span
          className="rounded-full px-3 py-1 text-sm font-black"
          style={{ background: "#E6F4E6", color: "#2E7D32" }}
        >
          Actuel ✓
        </span>
      )}
    </button>
  );
}

export function Picker({
  onDone,
  onCancel,
  variant = "first-run",
}: {
  onDone: () => void;
  onCancel?: () => void;
  variant?: "first-run" | "switch";
}) {
  const { chooseSpecies } = useProfile();
  const pick = (s: Species) => {
    chooseSpecies(s);
    onDone();
  };

  const switching = variant === "switch";

  return (
    <div
      className="flex min-h-[620px] w-full flex-col items-center gap-5 rounded-3xl px-6 pb-10 pt-8"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      {switching && onCancel && (
        <div className="flex w-full">
          <button
            type="button"
            onClick={onCancel}
            aria-label="Retour"
            className="rounded-full bg-white/80 px-4 py-2 text-base font-black shadow active:scale-95 [touch-action:manipulation]"
            style={{ color: INK, border: "none" }}
          >
            ← Retour
          </button>
        </div>
      )}

      <div className="leading-none" style={{ fontSize: "clamp(44px,14vw,72px)" }} aria-hidden>
        {switching ? "🔄" : "🥚"}
      </div>
      <h1
        className="m-0 text-center font-black"
        style={{ color: INK, fontSize: "clamp(26px,8vw,40px)" }}
      >
        {switching ? "Change de copain" : "Choisis ton copain"}
      </h1>
      <p className="m-0 mb-1 text-center" style={{ color: "#7A5A3A" }}>
        {switching
          ? "Tu retrouveras chacun comme tu l'as laissé."
          : "Il grandira avec toi."}
      </p>

      <div className="flex w-full flex-col gap-4">
        {CHOICES.map((c) => (
          <Choice
            key={c.species}
            species={c.species}
            name={c.name}
            onPick={() => pick(c.species)}
          />
        ))}
      </div>
    </div>
  );
}
