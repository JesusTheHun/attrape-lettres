import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { useAudio } from "../hooks/useAudio";
import { applyOption, useProfile } from "../hooks/useProfile";
import { CATALOG, DEFAULT_LOOKS, type DefaultLook } from "../mascot/catalog";
import { Mascot } from "../mascot/Mascot";
import { loadShopSeen, saveShopSeen } from "../storage";
import type { CustomizationOption, MascotConfig, Species } from "../types";
import { SHOP_BOUGHT, SHOP_GREW, SHOP_NEED_MORE, shopCostLine } from "../vo/utterances";
import { GrowthCard } from "./GrowthCard";
import { ItemPreview } from "./ItemPreview";
import { SavingsMeter } from "./Meter";
import { ShopItem } from "./ShopItem";
import { growBurst, pop, press, reducedMotion, starFlight } from "./anim";

/* -------------------------------------------------------------------------- */
/* Shop / dressing-room — the spending area.                                    */
/* Buying is a two-step ceremony a 6yo can follow: tapping an unowned tile      */
/* opens a TRY-ON DIALOG (the mascot wears it in the card, nothing is spent,   */
/* VO says the price), and only its big "Acheter · ⭐ N" button spends. Owned  */
/* items equip on tap, no dialog. Reads CATALOG + the wallet; mutates only via  */
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

/* The wallet figure counts down (or up) instead of jumping, so a child watches
 * the price actually LEAVE the purse. The count runs on rAF writing textContent
 * directly — off the React render path (invariant 2). React re-renders with the
 * same child string, so reconciliation never stomps a frame mid-count. */
function AnimatedNumber({ value }: { value: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  const shownRef = useRef(value); // what the DOM currently displays
  const rafRef = useRef(0);

  useLayoutEffect(() => {
    const el = ref.current;
    const from = shownRef.current;
    if (!el || from === value) return;
    if (reducedMotion()) {
      shownRef.current = value; // React already committed the new number
      return;
    }
    // React just committed the target; rewind to the old figure before paint,
    // then tick to the new one so the change reads as movement, not a jump.
    el.textContent = String(from);
    const t0 = performance.now();
    const dur = Math.min(900, 350 + Math.abs(value - from) * 12);
    const tick = (t: number) => {
      const k = Math.min(1, (t - t0) / dur);
      const eased = 1 - (1 - k) * (1 - k);
      const v = Math.round(from + (value - from) * eased);
      el.textContent = String(v);
      shownRef.current = v;
      if (k < 1) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [value]);

  return <span ref={ref}>{value}</span>;
}

/* A zone is a ROOM, not a heading: the wardrobe (what's yours, no prices) and
 * the store (what's for sale). Distinct tints + an icon chip make the split
 * spatial, so ownership is a place a pre-reader can see, not a text badge. */
function Zone({
  icon,
  title,
  tint,
  children,
}: {
  icon: string;
  title: string;
  tint: string;
  children: ReactNode;
}) {
  return (
    <section
      className="w-full rounded-3xl p-4"
      style={{ background: tint, boxShadow: "0 8px 18px rgba(0,0,0,0.07)" }}
    >
      <h2 className="mb-3 flex items-center gap-2.5 text-xl font-black" style={{ color: INK }}>
        <span
          aria-hidden
          className="flex h-10 w-10 items-center justify-center rounded-2xl text-xl shadow"
          style={{ background: "rgba(255,255,255,0.85)" }}
        >
          {icon}
        </span>
        {title}
      </h2>
      <div className="flex flex-col gap-4">{children}</div>
    </section>
  );
}

/** Tiles bucketed under one body-part label ("Queue", "Corps", "Accessoires"). */
interface TileGroup {
  label: string;
  tiles: ReactNode[];
}

function TileGroups({ groups }: { groups: TileGroup[] }) {
  return (
    <>
      {groups.map((g) => (
        <div key={g.label}>
          <h3 className="mb-1 text-sm font-bold" style={{ color: "#7A5A3A" }}>
            {g.label}
          </h3>
          <div className="grid grid-cols-2 gap-2.5">{g.tiles}</div>
        </div>
      ))}
    </>
  );
}

/* The try-on dialog — the ONE place a purchase is decided. Tapping an unowned
 * tile opens it: the mascot wears the item IN the card (the shop behind stays
 * as-is), the price sits on the buy button, and the meter shows the gap when
 * the wallet is short. Backdrop tap / Échap = "non merci". Nothing here spends;
 * the parent's confirmBuy does. */
function TryOnDialog({
  option,
  config,
  balance,
  sinceBalance,
  affordable,
  buyRef,
  onBuy,
  onCancel,
}: {
  option: CustomizationOption;
  config: MascotConfig;
  balance: number;
  sinceBalance: number;
  affordable: boolean;
  buyRef: RefObject<HTMLButtonElement>;
  onBuy: () => void;
  onCancel: () => void;
}) {
  const cardRef = useRef<HTMLDivElement>(null);

  // Mount ceremony: the card pops in, takes focus, and pins the page scroll.
  useEffect(() => {
    pop(cardRef.current);
    cardRef.current?.focus();
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-5"
      style={{ background: "rgba(74,48,24,0.45)" }}
      onClick={onCancel}
    >
      <div
        ref={cardRef}
        role="dialog"
        aria-modal="true"
        aria-label={`Essayer ${option.name}`}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape") onCancel();
        }}
        className="flex w-full max-w-sm flex-col items-center gap-3 rounded-3xl p-6 outline-none"
        style={{ background: STAGE, boxShadow: "0 24px 60px rgba(0,0,0,0.35)", fontFamily: ROUNDED }}
      >
        {/* The friend wears the item HERE — trying is the dialog's whole point. */}
        <div className="drop-shadow-lg">
          <Mascot config={applyOption(config, option)} mood="idle" size={150} />
        </div>
        <p className="text-xl font-black" style={{ color: INK }}>
          {option.name}
        </p>

        <div className="flex w-full items-center justify-center gap-2">
          <button
            type="button"
            onClick={onCancel}
            aria-label="Ne pas acheter"
            className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-xl font-black shadow active:scale-95 [touch-action:manipulation]"
            style={{ background: "rgba(255,255,255,0.9)", color: "#9A7A5A", border: "none" }}
          >
            ✕
          </button>
          <button
            ref={buyRef}
            type="button"
            disabled={!affordable}
            onPointerDown={() => press(buyRef.current)}
            onClick={onBuy}
            aria-label={
              affordable
                ? `Acheter ${option.name} pour ${option.cost} étoiles`
                : `Pas encore assez d'étoiles pour ${option.name}`
            }
            className="rounded-full px-6 py-3 text-lg font-black shadow [touch-action:manipulation] [-webkit-tap-highlight-color:transparent]"
            style={{
              background: affordable ? "#FFD54F" : "rgba(255,255,255,0.7)",
              color: affordable ? "#4A3B00" : "#B8A98E",
              border: "none",
              cursor: affordable ? "pointer" : "default",
              boxShadow: affordable ? "0 6px 0 rgba(0,0,0,0.12)" : "none",
            }}
          >
            {affordable ? `Acheter · ⭐ ${option.cost}` : `⭐ ${option.cost} · pas encore`}
          </button>
        </div>

        {/* Saving up: the meter shows how close the wallet is — no maths. */}
        {!affordable && (
          <div className="w-4/5">
            <SavingsMeter cost={option.cost} balance={balance} since={sinceBalance} height={12} />
          </div>
        )}
      </div>
    </div>
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
  const { profile, buy, setConfig, activeId } = useProfile();
  const { config, balance, owned } = profile;

  /* Balance this child last SAW here — the savings meters animate from it, so
   * stars earned since the previous visit land as motion, not a static bar.
   * Captured once per mount; every balance change updates the stored value. */
  const [sinceBalance] = useState(() => loadShopSeen()[activeId ?? ""] ?? 0);
  useEffect(() => {
    if (activeId) saveShopSeen({ ...loadShopSeen(), [activeId]: balance });
  }, [activeId, balance]);
  const audio = useAudio();
  const previewRef = useRef<HTMLDivElement>(null);
  const buyRef = useRef<HTMLButtonElement>(null);
  const walletRef = useRef<HTMLDivElement>(null);

  // How many stars fly wallet→mascot on a purchase: pricier = more of a shower.
  const flightSize = (cost: number) => Math.min(8, Math.max(3, Math.round(cost / 10)));

  /* The try-on "cart": at most ONE unowned option, shown worn in the dialog
   * but NOT bought. Any real config change (equip, buy, factory look) clears it. */
  const [cart, setCart] = useState<CustomizationOption | null>(null);
  const cartAffordable = cart !== null && balance >= cart.cost;

  const forMe = CATALOG.filter((o) => o.species === config.species);
  const defaults = DEFAULT_LOOKS[config.species];

  // Free try-on: open the dialog, say the price. Nothing is spent here.
  const tryOn = (o: CustomizationOption) => {
    audio.unlock();
    audio.pop();
    setCart(o);
    void audio.say(balance >= o.cost ? shopCostLine(o.cost) : `${shopCostLine(o.cost)} ${SHOP_NEED_MORE}`);
  };

  /* The ONLY place shop items are paid for: the dialog's buy button. The dialog
   * closes and the stars fly from the tapped button to the HEADER mascot — it
   * survives the unmount, so the celebration lands on the friend now wearing
   * the item (rects are captured synchronously; the flight layer lives on body). */
  const confirmBuy = () => {
    if (!cart) return;
    audio.unlock();
    if (!buy(cart)) return; // wallet raced empty — soft no-op, never an error
    starFlight(buyRef.current ?? walletRef.current, previewRef.current, flightSize(cart.cost));
    pop(previewRef.current);
    audio.success();
    void audio.say(SHOP_BOUGHT);
    setCart(null);
  };

  const cancelTry = () => {
    audio.unlock();
    audio.pop();
    setCart(null);
  };

  // Colours & style variants just apply (free re-apply once owned).
  const applyVariant = (o: CustomizationOption) => {
    buy(o);
    pop(previewRef.current);
  };

  // Accessories toggle: tapping the equipped one takes it off; else equip.
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
    audio.unlock();
    audio.pop();
    setCart(null);
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

    // Owned = equip/toggle right away (free). Unowned = try-on, never a spend.
    const onTap = () => {
      if (isOwned) {
        audio.unlock();
        audio.pop();
        setCart(null);
        if (o.category === "accessory") toggleAccessory(o);
        else applyVariant(o);
      } else {
        tryOn(o);
      }
    };

    return (
      <ShopItem
        key={o.id}
        option={o}
        owned={isOwned}
        equipped={equipped}
        locked={locked}
        affordable={affordable}
        trying={cart?.id === o.id}
        balance={balance}
        sinceBalance={sinceBalance}
        onTap={onTap}
      />
    );
  };

  /* Both zones bucket tiles by BODY PART ("Queue", "Corps", …) — the way a
   * child thinks about dressing — not by colour-vs-style. Accessories pool
   * under their own label, last. */
  const labelOf = (slot: string, category: CustomizationOption["category"]) =>
    category === "accessory" ? "Accessoires" : (SLOT_LABEL[slot] ?? slot);

  const grouped = (entries: { label: string; tile: ReactNode }[]): TileGroup[] => {
    const out: TileGroup[] = [];
    for (const e of entries) {
      const g = out.find((x) => x.label === e.label);
      if (g) g.tiles.push(e.tile);
      else out.push({ label: e.label, tiles: [e.tile] });
    }
    return out;
  };

  // The wardrobe: every factory look + everything bought. No prices in here.
  const armoireGroups = grouped([
    ...defaults.map((d) => {
      const kind = d.category === "color" ? ("colors" as const) : ("styles" as const);
      return {
        label: labelOf(d.slot, d.category),
        tile: (
          <DefaultTile
            key={`default.${d.category}.${d.slot}`}
            look={d}
            species={config.species}
            active={config[kind][d.slot] === undefined}
            locked={config.stage < (d.minStage ?? 0)}
            onTap={() => clearSlot(kind, d.slot)}
          />
        ),
      };
    }),
    ...forMe
      .filter((o) => owned.includes(o.id))
      .map((o) => ({ label: labelOf(o.slot, o.category), tile: renderItem(o) })),
  ]);

  // The store: only what is NOT yet owned — a bought item moves to the wardrobe.
  const shopGroups = grouped(
    forMe
      .filter((o) => !owned.includes(o.id))
      .map((o) => ({ label: labelOf(o.slot, o.category), tile: renderItem(o) }))
  );

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
            ref={walletRef}
            aria-label={`${balance} points`}
            className="rounded-full px-4 py-2 text-lg font-black shadow"
            style={{ background: "#FFD54F", color: "#4A3B00" }}
          >
            ⭐ <AnimatedNumber value={balance} />
          </div>
        </div>

        {/* The header friend is the REAL look — what's owned and worn. Trying
         * on happens in the dialog, so this never flickers with maybes. */}
        <div ref={previewRef} className="drop-shadow-lg">
          <Mascot config={config} mood="idle" size={128} />
        </div>

        <p className="text-sm font-bold" style={{ color: "#7A5A3A" }}>
          Habille ton copain !
        </p>
      </header>

      {cart && (
        <TryOnDialog
          option={cart}
          config={config}
          balance={balance}
          sinceBalance={sinceBalance}
          affordable={cartAffordable}
          buyRef={buyRef}
          onBuy={confirmBuy}
          onCancel={cancelTry}
        />
      )}

      <div className="flex w-full flex-col gap-5 px-5">
        {/* Yours first: dressing what you own is the everyday action. */}
        <Zone icon="🧺" title="Ton armoire" tint="linear-gradient(160deg,#EAF7E0,#F4FBEC)">
          <TileGroups groups={armoireGroups} />
        </Zone>

        {/* Then the store: growth (the headline spend) + everything unowned. */}
        <Zone icon="🛒" title="Le magasin" tint="linear-gradient(160deg,#E2F0FC,#EBF5FE)">
          <GrowthCard
            sinceBalance={sinceBalance}
            onGrew={(cost) => {
              starFlight(walletRef.current, previewRef.current, flightSize(cost));
              audio.success();
              void audio.say(SHOP_GREW);
              pop(previewRef.current);
              growBurst(previewRef.current);
            }}
          />
          {shopGroups.length > 0 ? (
            <TileGroups groups={shopGroups} />
          ) : (
            <p className="text-center text-base font-bold" style={{ color: "#7A5A3A" }}>
              Bientôt de nouveaux objets à découvrir ✨
            </p>
          )}
        </Zone>
      </div>
    </div>
  );
}
