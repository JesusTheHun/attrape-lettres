import { useRef } from "react";
import { useProfile } from "../hooks/useProfile";
import { Mascot } from "../mascot/Mascot";
import { GROWTH_STAGES } from "../types";
import { press } from "./anim";

/* -------------------------------------------------------------------------- */
/* Growth upgrade — the headline spend. Grows the mascot one stage at a time.   */
/* Price rises with maturity so growing stays a meaningful goal. No fail state: */
/* too poor = disabled + "pas encore"; at the top = "Niveau max ✨".            */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";

/** Local price curve — rises with the current stage. */
const growthPrice = (stage: number): number => 30 * (stage + 1);

export function GrowthCard({ onGrew }: { onGrew?: (cost: number) => void }) {
  const { profile, spend, setConfig } = useProfile();
  const { config, balance } = profile;
  const btnRef = useRef<HTMLButtonElement>(null);

  const atMax = config.stage >= GROWTH_STAGES - 1;
  const price = growthPrice(config.stage);
  const affordable = balance >= price;
  const disabled = atMax || !affordable;
  const nextStage = Math.min(config.stage + 1, GROWTH_STAGES - 1);

  const grow = () => {
    if (atMax) return;
    if (spend(price)) {
      setConfig((c) => ({ ...c, stage: Math.min(c.stage + 1, GROWTH_STAGES - 1) }));
      onGrew?.(price);
    }
  };

  const label = atMax
    ? "Niveau maximum atteint"
    : affordable
      ? `Faire grandir pour ${price} points`
      : `Pas encore assez de points pour grandir, il en faut ${price}`;

  return (
    <section
      className="flex w-full items-center gap-4 rounded-3xl p-4"
      style={{
        background: "linear-gradient(135deg,#E9F9E0,#D6F0FB)",
        boxShadow: "0 8px 18px rgba(0,0,0,0.08)",
      }}
    >
      {/* A peek at what the next stage looks like. */}
      <div className="shrink-0 rounded-2xl bg-white/70 p-2" aria-hidden>
        <Mascot config={{ ...config, stage: nextStage }} mood="idle" size={64} />
      </div>

      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex items-baseline justify-between gap-2">
          <span className="text-lg font-black" style={{ color: INK }}>
            Faire grandir 🌱
          </span>
          <span className="text-sm font-bold" style={{ color: "#7A5A3A" }}>
            {config.stage + 1}/{GROWTH_STAGES}
          </span>
        </div>

        {/* Growth meter. */}
        <div
          className="flex gap-1"
          role="img"
          aria-label={`Croissance ${config.stage + 1} sur ${GROWTH_STAGES}`}
        >
          {Array.from({ length: GROWTH_STAGES }, (_, i) => (
            <span
              key={i}
              className="h-2 flex-1 rounded-full"
              style={{ background: i <= config.stage ? "#66BB6A" : "rgba(90,58,30,0.15)" }}
            />
          ))}
        </div>

        <button
          ref={btnRef}
          type="button"
          disabled={disabled}
          aria-label={label}
          onPointerDown={() => press(btnRef.current)}
          onClick={grow}
          className="rounded-full px-5 py-3 text-base font-extrabold text-white [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
          style={{
            background: atMax ? "#B8A98E" : "#66BB6A",
            border: "none",
            cursor: disabled ? "default" : "pointer",
            opacity: disabled && !atMax ? 0.6 : 1,
            boxShadow: disabled ? "none" : "0 6px 0 rgba(0,0,0,0.12)",
          }}
        >
          {atMax ? "Niveau max ✨" : affordable ? `Grandir · ⭐ ${price}` : `pas encore · ⭐ ${price}`}
        </button>
      </div>
    </section>
  );
}
