import { useRef } from "react";
import type { CustomizationOption } from "../types";
import { ItemPreview } from "./ItemPreview";
import { press } from "./anim";

/* -------------------------------------------------------------------------- */
/* One buyable / equippable tile. Purely presentational — the parent computes  */
/* every state flag and owns the actual buy()/setConfig() side-effect in onTap. */
/* Tapping an UNOWNED tile is always a free try-on (the parent shows it on the  */
/* live mascot + a buy bar); only growth-locked tiles are truly disabled. An    */
/* unaffordable item can still be tried — buying it is gated, not seeing it.    */
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
  onTap: () => void;
}

export function ShopItem({ option, owned, equipped, locked, affordable, trying, onTap }: ShopItemProps) {
  const ref = useRef<HTMLButtonElement>(null);
  const minStage = option.minStage ?? 0;

  const price = `⭐ ${option.cost}`;
  // Only accessories come off by tapping; a colour/style is changed by picking
  // another tile (its factory-look tile returns it to the original).
  const removable = equipped && option.category === "accessory";

  // Price is always shown until owned — even when unaffordable or growth-locked,
  // so a kid can see what to save up for. Owned/equipped items drop the number.
  const badge = equipped
    ? removable
      ? "Enlever ✕"
      : "Équipé ✓"
    : owned
      ? "À toi"
      : locked
        ? `🌱 niv. ${minStage + 1} · ${price}`
        : price;

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
      <ItemPreview
        species={option.species}
        category={option.category}
        slot={option.slot}
        value={option.value}
      />
      <span className="text-sm font-bold leading-tight">{option.name}</span>
      <span
        className="text-xs font-black"
        style={{ color: equipped ? "#3E7B3E" : trying ? "#B07A00" : "#9A7A5A" }}
      >
        {badge}
      </span>
    </button>
  );
}
