import { useRef, useState } from "react";
import { useProfile } from "../hooks/useProfile";
import { Mascot } from "../mascot/Mascot";
import { press } from "../shop/anim";
import type { ChildProfile } from "../types";

/* -------------------------------------------------------------------------- */
/* Welcome screen — "Qui joue ?". Siblings share the device: pick who you are,   */
/* or make a new profile. A child's avatar IS their current mascot (or the owl   */
/* until they've chosen a species). Selecting sets the active player; creating   */
/* drops the new child straight into the species picker (chosen=false).          */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 40%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

function Avatar({ child, size }: { child: ChildProfile; size: number }) {
  const p = child.profile;
  if (!p.chosen) {
    return (
      <span className="leading-none" style={{ fontSize: size * 0.72 }} aria-hidden>
        🦉
      </span>
    );
  }
  return <Mascot config={p.species[p.current].config} mood="idle" size={size} />;
}

function NewProfile({ onCancel }: { onCancel: (() => void) | null }) {
  const { createChild } = useProfile();
  const [name, setName] = useState("");
  const ok = name.trim().length > 0;

  return (
    <form
      className="flex w-full max-w-sm flex-col items-center gap-5"
      onSubmit={(e) => {
        e.preventDefault();
        if (ok) createChild(name); // activeId flips → App shows the species picker
      }}
    >
      <div className="leading-none" style={{ fontSize: "clamp(48px,15vw,76px)" }} aria-hidden>
        👋
      </div>
      <h1 className="m-0 text-center font-black" style={{ color: INK, fontSize: "clamp(24px,7vw,36px)" }}>
        Comment tu t'appelles ?
      </h1>
      <input
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={14}
        autoFocus
        aria-label="Ton prénom"
        placeholder="Ton prénom"
        className="w-full rounded-3xl px-6 py-4 text-center text-2xl font-black shadow"
        style={{ color: INK, background: "rgba(255,255,255,0.95)", border: "none", outline: "none" }}
      />
      <button
        type="submit"
        disabled={!ok}
        className="w-full rounded-full px-8 py-4 text-2xl font-extrabold text-white active:scale-95 disabled:opacity-40 [touch-action:manipulation]"
        style={{ background: "#66BB6A", border: "none", boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}
      >
        C'est parti ! 🎉
      </button>
      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          className="text-base font-bold underline"
          style={{ color: "#7A5A3A", background: "none", border: "none" }}
        >
          Retour
        </button>
      )}
    </form>
  );
}

function ChildCard({
  child,
  editing,
  onPick,
  onDelete,
}: {
  child: ChildProfile;
  editing: boolean;
  onPick: () => void;
  onDelete: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  return (
    <div className="relative">
      <button
        ref={ref}
        type="button"
        aria-label={`Jouer avec ${child.name}`}
        onPointerDown={() => press(ref.current)}
        onClick={onPick}
        className="flex w-full flex-col items-center gap-2 rounded-3xl p-4 active:scale-[0.97] [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
        style={{ background: "rgba(255,255,255,0.92)", border: "none", cursor: "pointer", boxShadow: "0 8px 18px rgba(0,0,0,0.10)" }}
      >
        <span className="flex h-24 items-center justify-center">
          <Avatar child={child} size={84} />
        </span>
        <span className="max-w-full truncate text-xl font-black" style={{ color: INK }}>
          {child.name}
        </span>
      </button>
      {editing && (
        <button
          type="button"
          aria-label={`Supprimer ${child.name}`}
          onClick={onDelete}
          className="absolute -right-2 -top-2 flex h-9 w-9 items-center justify-center rounded-full text-lg font-black text-white shadow active:scale-90"
          style={{ background: "#EF5350", border: "2px solid white" }}
        >
          ✕
        </button>
      )}
    </div>
  );
}

export function WhoIsPlaying() {
  const { children, selectChild, deleteChild } = useProfile();
  const [creating, setCreating] = useState(children.length === 0);
  const [editing, setEditing] = useState(false);

  return (
    <div
      className="flex min-h-[620px] w-full flex-col items-center gap-6 rounded-3xl px-6 pb-10 pt-10"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      {creating ? (
        <NewProfile onCancel={children.length ? () => setCreating(false) : null} />
      ) : (
        <>
          <div className="flex w-full items-center justify-between">
            <span className="text-lg font-black" style={{ color: "#7A5A3A" }}>
              Qui joue ?
            </span>
            <button
              type="button"
              onClick={() => setEditing((e) => !e)}
              className="rounded-full bg-white/80 px-4 py-2 text-base font-black shadow active:scale-95 [touch-action:manipulation]"
              style={{ color: INK, border: "none" }}
            >
              {editing ? "Terminé" : "Modifier"}
            </button>
          </div>

          <div className="grid w-full max-w-md grid-cols-2 gap-4">
            {children.map((c) => (
              <ChildCard
                key={c.id}
                child={c}
                editing={editing}
                onPick={() => selectChild(c.id)}
                onDelete={() => {
                  if (window.confirm(`Supprimer le profil de ${c.name} ? Tout sera perdu.`)) {
                    deleteChild(c.id);
                  }
                }}
              />
            ))}

            <button
              type="button"
              aria-label="Nouveau profil"
              onClick={() => setCreating(true)}
              className="flex flex-col items-center justify-center gap-2 rounded-3xl p-4 active:scale-[0.97] [touch-action:manipulation]"
              style={{ background: "rgba(255,255,255,0.55)", border: "3px dashed #E4A15E", cursor: "pointer", minHeight: 150 }}
            >
              <span className="leading-none" style={{ fontSize: 46 }} aria-hidden>
                ＋
              </span>
              <span className="text-lg font-black" style={{ color: INK }}>
                Nouveau
              </span>
            </button>
          </div>
        </>
      )}
    </div>
  );
}
