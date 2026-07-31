import { useRef } from "react";
import type { CustomizationOption } from "../types";
import { ItemPreview } from "./ItemPreview";
import { SavingsMeter } from "./Meter";
import { press } from "./anim";

/* -------------------------------------------------------------------------- */
/* One buyable / equippable tile. Purely presentational — the parent computes  */
/* every state flag and owns the actual buy()/setConfig() side-effect in onTap. */
/* Tapping an UNOWNED tile is always a free try-on (the parent shows it on the  */
/* live mascot + a buy bar); only growth-locked tiles are truly disabled.       */
/*                                                                              */
/* State is coded for a PRE-READER — colour, shape and fill, never prose:       */
/*   gold price tag   = you can buy this now                                    */
/*   grey tag + meter = keep saving (the meter is the countdown, no arithmetic) */
/*   corner ✓ sticker = yours (green-filled when worn; ✕ = tap takes it off)    */
/*   🌱 chip          = your friend must grow first                             */
/* The art itself is NEVER greyed out — a grey drawing reads as "broken".       */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";

interface ShopItemProps {
  option: CustomizationOption;
  /** Already purchased (in profile.owned). */
  owned: boolean;
  /** Currently applied to the mascot config. */
  equipped: boolean;
  /** Growth-gated: mascot not mature enough yet (config.stage < minStage). */
  locked: boolean;
  /** owned || balance >= cost. */
  affordable: boolean;
  /** Being tried on right now (in the cart, worn by the preview mascot). */
  trying: boolean;
  /** Wallet, for the savings meter on unaffordable items. */
  balance: number;
  /** Wallet at the previous shop visit — the meter animates from there. */
  sinceBalance: number;
  onTap: () => void;
}

export function ShopItem({
  option,
  owned,
  equipped,
  locked,
  affordable,
  trying,
  balance,
  sinceBalance,
  onTap,
}: ShopItemProps) {
  const ref = useRef<HTMLButtonElement>(null);
  const minStage = option.minStage ?? 0;

  // Only accessories come off by tapping; a colour/style is changed by picking
  // another tile (its factory-look tile returns it to the original).
  const removable = equipped && option.category === "accessory";

  const stateLabel = equipped
    ? removable
      ? "équipé, appuie pour enlever"
      : "équipé"
    : owned
      ? "à toi"
      : locked
        ? `coûte ${option.cost} points, à débloquer au niveau ${minStage + 1}`
        : trying
          ? `coûte ${option.cost} points, en train d'essayer`
          : affordable
            ? `coûte ${option.cost} points`
            : `coûte ${option.cost} points, pas encore assez`;

  return (
    <button
      ref={ref}
      type="button"
      disabled={locked}
      aria-pressed={equipped}
      aria-label={`${option.name}, ${stateLabel}`}
      onPointerDown={() => press(ref.current)}
      onClick={onTap}
      className="relative flex min-h-[96px] select-none flex-col items-center justify-center gap-1 rounded-2xl p-3 text-center [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
      style={{
        background: equipped ? "#FFF6E0" : trying ? "#FFF9EB" : "rgba(255,255,255,0.9)",
        color: INK,
        border: "none",
        cursor: locked ? "default" : "pointer",
        opacity: locked ? 0.55 : 1,
        boxShadow: equipped
          ? "0 0 0 4px #66BB6A, 0 8px 16px rgba(0,0,0,0.12)"
          : trying
            ? "0 0 0 4px #FFB300, 0 8px 16px rgba(0,0,0,0.12)"
            : "0 6px 14px rgba(0,0,0,0.10)",
      }}
    >
      {/* "Yours" sticker — ✓ once bought, filled green while worn, ✕ when a
       * worn accessory would come off on tap. Icon, not words. */}
      {owned && (
        <span
          aria-hidden
          className="absolute -right-1.5 -top-1.5 flex h-7 w-7 items-center justify-center rounded-full text-sm font-black shadow"
          style={
            equipped
              ? { background: "#66BB6A", color: "#FFFFFF" }
              : { background: "#FFFFFF", color: "#3E7B3E" }
          }
        >
          {removable ? "✕" : "✓"}
        </span>
      )}

      <ItemPreview
        species={option.species}
        category={option.category}
        slot={option.slot}
        value={option.value}
      />
      <span className="text-sm font-bold leading-tight">{option.name}</span>

      {/* Price panel — only until owned; a bought item never shows a number. */}
      {!owned &&
        (locked ? (
          <span
            aria-hidden
            className="rounded-full px-2 py-0.5 text-xs font-black"
            style={{ background: "rgba(90,58,30,0.08)", color: "#9A7A5A" }}
          >
            🌱 niv. {minStage + 1} · ⭐ {option.cost}
          </span>
        ) : affordable ? (
          <span
            aria-hidden
            className="rounded-full px-2.5 py-0.5 text-sm font-black shadow"
            style={{ background: "#FFD54F", color: "#4A3B00" }}
          >
            ⭐ {option.cost}
          </span>
        ) : (
          <>
            <span
              aria-hidden
              className="rounded-full px-2.5 py-0.5 text-sm font-black"
              style={{ background: "rgba(90,58,30,0.10)", color: "#8A7B69" }}
            >
              ⭐ {option.cost}
            </span>
            <SavingsMeter cost={option.cost} balance={balance} since={sinceBalance} />
          </>
        ))}
    </button>
  );
}
