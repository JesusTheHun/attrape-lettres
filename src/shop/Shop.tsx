import { useRef, type ReactNode } from "react";
import { useProfile } from "../hooks/useProfile";
import { CATALOG } from "../mascot/catalog";
import { Mascot } from "../mascot/Mascot";
import type { CustomizationOption } from "../types";
import { GrowthCard } from "./GrowthCard";
import { ShopItem } from "./ShopItem";
import { pop } from "./anim";

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

const SECTIONS: { key: CustomizationOption["category"]; title: string }[] = [
  { key: "color", title: "Couleurs" },
  { key: "style", title: "Styles" },
  { key: "accessory", title: "Accessoires" },
];

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

export function Shop({ onBack }: { onBack: () => void }) {
  const { profile, buy, setConfig } = useProfile();
  const { config, balance, owned } = profile;
  const previewRef = useRef<HTMLDivElement>(null);

  const forMe = CATALOG.filter((o) => o.species === config.species);

  // Colours / styles: buy() applies the value (free re-apply once owned).
  const applyColorStyle = (o: CustomizationOption) => {
    buy(o);
    pop(previewRef.current);
  };

  // Accessories are toggleable: equipped → unequip; owned/idle → re-equip (free).
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
        onTap={() =>
          o.category === "accessory" ? toggleAccessory(o) : applyColorStyle(o)
        }
      />
    );
  };

  const populated = SECTIONS.map((s) => ({
    ...s,
    items: forMe.filter((o) => o.category === s.key),
  })).filter((s) => s.items.length > 0);

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
        <GrowthCard onGrew={() => pop(previewRef.current)} />

        {populated.map((s) => (
          <Section key={s.key} title={s.title}>
            {s.items.map(renderItem)}
          </Section>
        ))}

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
