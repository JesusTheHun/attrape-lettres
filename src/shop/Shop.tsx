import { useRef, type ReactNode } from "react";
import { useProfile } from "../hooks/useProfile";
import { CATALOG, DEFAULT_LOOKS, type DefaultLook } from "../mascot/catalog";
import { Mascot } from "../mascot/Mascot";
import type { CustomizationOption, Species } from "../types";
import { GrowthCard } from "./GrowthCard";
import { ItemPreview } from "./ItemPreview";
import { ShopItem } from "./ShopItem";
import { growBurst, pop, press } from "./anim";

/* -------------------------------------------------------------------------- */
/* Shop / dressing-room — the spending area.                                    */
/* A live <Mascot> preview reflects the CURRENT config and re-renders from      */
/* profile the instant anything is bought/equipped; a WAAPI pop celebrates it.  */
/* Reads CATALOG (may be empty/partial) + the wallet; mutates only via          */
/* useProfile().buy / setConfig. Never touches storage. No hard errors.         */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 40%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

/** The body part each colour/style slot dresses — labels its group in the list. */
const SLOT_LABEL: Record<string, string> = {
  bodyColor: "Corps",
  hornColor: "Corne",
  maneColor: "Crinière",
  tailColor: "Queue",
  bellyColor: "Ventre",
  tailTipColor: "Bout de queue",
  tailStyle: "Queue",
  hornStyle: "Corne",
  hair: "Poil",
  tailSize: "Queue",
  furPattern: "Pelage",
};

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="w-full">
      <h2 className="mb-2 text-lg font-black" style={{ color: INK }}>
        {title}
      </h2>
      <div className="grid grid-cols-2 gap-2.5">{children}</div>
    </section>
  );
}

/* The mascot's factory look for one slot, shown as an ordinary ALREADY-OWNED
 * tile (its real name, "À toi" / "Équipé ✓") — never "reset/default" jargon.
 * Selecting it clears the slot, so the rig falls back to this exact look. */
function DefaultTile({
  look,
  species,
  active,
  locked,
  onTap,
}: {
  look: DefaultLook;
  species: Species;
  active: boolean;
  locked: boolean;
  onTap: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const minStage = look.minStage ?? 0;
  const badge = locked ? `🌱 niv. ${minStage + 1}` : active ? "Équipé ✓" : "À toi";
  const stateLabel = locked
    ? `à débloquer au niveau ${minStage + 1}`
    : active
      ? "en place"
      : "à toi";

  return (
    <button
      ref={ref}
      type="button"
      disabled={locked}
      aria-pressed={active}
      aria-label={`${look.name}, ${stateLabel}`}
      onPointerDown={() => press(ref.current)}
      onClick={onTap}
      className="relative flex min-h-[96px] select-none flex-col items-center justify-center gap-1 rounded-2xl p-3 text-center [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
      style={{
        background: active ? "#FFF6E0" : "rgba(255,255,255,0.9)",
        color: INK,
        border: "none",
        cursor: locked ? "default" : "pointer",
        opacity: locked ? 0.55 : 1,
        boxShadow: active
          ? "0 0 0 4px #66BB6A, 0 8px 16px rgba(0,0,0,0.12)"
          : "0 6px 14px rgba(0,0,0,0.10)",
      }}
    >
      <ItemPreview species={species} category={look.category} slot={look.slot} value={look.value} />
      <span className="text-sm font-bold leading-tight">{look.name}</span>
      <span className="text-xs font-black" style={{ color: active ? "#3E7B3E" : "#9A7A5A" }}>
        {badge}
      </span>
    </button>
  );
}

export function Shop({ onBack }: { onBack: () => void }) {
  const { profile, buy, setConfig } = useProfile();
  const { config, balance, owned } = profile;
  const previewRef = useRef<HTMLDivElement>(null);

  const forMe = CATALOG.filter((o) => o.species === config.species);
  const defaults = DEFAULT_LOOKS[config.species];

  // Colours & style variants just apply (free re-apply once owned).
  const applyVariant = (o: CustomizationOption) => {
    buy(o);
    pop(previewRef.current);
  };

  // Accessories toggle: tapping the equipped one takes it off; else buy + equip.
  const toggleAccessory = (o: CustomizationOption) => {
    if (config.accessories.includes(o.id)) {
      setConfig((c) => ({
        ...c,
        accessories: c.accessories.filter((id) => id !== o.id),
      }));
    } else {
      buy(o);
    }
    pop(previewRef.current);
  };

  // Return one colour/style slot to its factory look (clearing it → rig default).
  const clearSlot = (kind: "colors" | "styles", slot: string) => {
    setConfig((c) => {
      const next = { ...c[kind] };
      delete next[slot];
      return { ...c, [kind]: next };
    });
    pop(previewRef.current);
  };

  const renderItem = (o: CustomizationOption) => {
    const isOwned = owned.includes(o.id);
    const equipped =
      o.category === "accessory"
        ? config.accessories.includes(o.id)
        : o.category === "color"
          ? config.colors[o.slot] === o.value
          : config.styles[o.slot] === o.value;
    const locked = config.stage < (o.minStage ?? 0);
    const affordable = isOwned || balance >= o.cost;

    return (
      <ShopItem
        key={o.id}
        option={o}
        owned={isOwned}
        equipped={equipped}
        locked={locked}
        affordable={affordable}
        onTap={() => (o.category === "accessory" ? toggleAccessory(o) : applyVariant(o))}
      />
    );
  };

  const itemsIn = (key: CustomizationOption["category"]) =>
    forMe.filter((o) => o.category === key);

  /* One section per colour/style category: variants grouped by the body part
   * they change (catalog order), each group led by its factory-look tile. */
  const renderSlotGroups = (
    title: string,
    category: "color" | "style",
    kind: "colors" | "styles"
  ) => {
    const groups: { slot: string; items: CustomizationOption[] }[] = [];
    for (const o of itemsIn(category)) {
      const g = groups.find((s) => s.slot === o.slot);
      if (g) g.items.push(o);
      else groups.push({ slot: o.slot, items: [o] });
    }
    if (groups.length === 0) return null;

    return (
      <section className="w-full">
        <h2 className="mb-2 text-lg font-black" style={{ color: INK }}>
          {title}
        </h2>
        <div className="flex flex-col gap-3">
          {groups.map((g) => {
            const look = defaults.find((d) => d.category === category && d.slot === g.slot);
            return (
              <div key={g.slot}>
                <h3 className="mb-1 text-sm font-bold" style={{ color: "#7A5A3A" }}>
                  {SLOT_LABEL[g.slot] ?? title}
                </h3>
                <div className="grid grid-cols-2 gap-2.5">
                  {look && (
                    <DefaultTile
                      look={look}
                      species={config.species}
                      active={config[kind][g.slot] === undefined}
                      locked={config.stage < (look.minStage ?? 0)}
                      onTap={() => clearSlot(kind, g.slot)}
                    />
                  )}
                  {g.items.map(renderItem)}
                </div>
              </div>
            );
          })}
        </div>
      </section>
    );
  };

  return (
    <div
      className="flex min-h-[620px] w-full flex-col items-center gap-4 rounded-3xl pb-10"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <header
        className="sticky top-0 z-10 flex w-full flex-col items-center gap-2 rounded-b-3xl px-5 pb-4 pt-4"
        style={{
          background: "rgba(255,244,224,0.82)",
          backdropFilter: "blur(8px)",
          WebkitBackdropFilter: "blur(8px)",
        }}
      >
        <div className="flex w-full items-center justify-between">
          <button
            type="button"
            onClick={onBack}
            aria-label="Retour"
            className="rounded-full px-4 py-2 text-base font-black shadow active:scale-95 [touch-action:manipulation]"
            style={{ background: "rgba(255,255,255,0.85)", color: INK, border: "none" }}
          >
            ← Retour
          </button>
          <div
            aria-label={`${balance} points`}
            className="rounded-full px-4 py-2 text-lg font-black shadow"
            style={{ background: "#FFD54F", color: "#4A3B00" }}
          >
            ⭐ {balance}
          </div>
        </div>

        <div ref={previewRef} className="drop-shadow-lg">
          <Mascot config={config} mood="idle" size={128} />
        </div>
        <p className="text-sm font-bold" style={{ color: "#7A5A3A" }}>
          Habille ton copain !
        </p>
      </header>

      <div className="flex w-full flex-col gap-5 px-5">
        <GrowthCard
          onGrew={() => {
            pop(previewRef.current);
            growBurst(previewRef.current);
          }}
        />

        {renderSlotGroups("Couleurs", "color", "colors")}
        {renderSlotGroups("Styles", "style", "styles")}

        {itemsIn("accessory").length > 0 && (
          <Section title="Accessoires">{itemsIn("accessory").map(renderItem)}</Section>
        )}

        {forMe.length === 0 && (
          <p
            className="mt-2 text-center text-base font-bold"
            style={{ color: "#7A5A3A" }}
          >
            Bientôt de nouveaux objets à découvrir ✨
          </p>
        )}
      </div>
    </div>
  );
}
