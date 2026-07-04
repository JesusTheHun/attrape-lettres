import { useEffect, useRef, useState } from "react";
import { useProfile } from "../hooks/useProfile";
import { Mascot } from "../mascot/Mascot";
import { GROWTH_STAGES } from "../types";
import { usePopFlourish } from "./usePopFlourish";

/**
 * The child's home base — owned by AGENT B.
 *
 * A live <Mascot>, the big animated ⭐ balance, a friendly growth meter, and the
 * doors to the shop / back to the menu. Everything reads from useProfile(); no
 * storage access. Mobile-first + tablet-fluid (clamp/%, no fixed widths that
 * break on tablets), safe-area handled by the #root shell. All motion is WAAPI,
 * off the React render path (invariant #2), and honors prefers-reduced-motion.
 */
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 38%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

export function Dashboard({
  onBack,
  onShop,
  onSwitch,
}: {
  onBack: () => void;
  onShop: () => void;
  onSwitch: () => void;
}) {
  const { profile } = useProfile();
  const { config, balance } = profile;
  const stage = config.stage;
  const pct = ((stage + 1) / GROWTH_STAGES) * 100;

  // Measure the card so the mascot art scales with it (contract: fluid callers
  // pass a clamp-derived number). Pure layout — not on any animation path.
  const rootRef = useRef<HTMLDivElement>(null);
  const [box, setBox] = useState(0);
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => setBox(entries[0]?.contentRect.width ?? 0));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const mascotSize = Math.round(Math.min(230, Math.max(140, box * 0.46)));

  // Balance pops in every time the dashboard opens.
  const balanceRef = usePopFlourish<HTMLDivElement>();

  // Growth bar fills up on entry (WAAPI width sweep; static when reduced-motion).
  const fillRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = fillRef.current;
    if (!el) return;
    el.style.width = `${pct}%`;
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    if (reduce) return;
    const anim = el.animate([{ width: "0%" }, { width: `${pct}%` }], {
      duration: 900,
      easing: "cubic-bezier(.2,.9,.3,1)",
    });
    return () => anim.cancel();
  }, [pct]);

  return (
    <div
      ref={rootRef}
      className="flex min-h-[620px] w-full flex-col items-center gap-5 rounded-3xl px-5 pb-10 pt-6"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <div className="flex w-full items-center justify-between">
        <button
          onClick={onBack}
          aria-label="Retour au menu"
          className="rounded-full bg-white/80 px-4 py-2 text-lg font-bold text-[#5A3A1E] shadow active:scale-95"
        >
          ← Menu
        </button>
        <span className="text-lg font-black text-[#7A5A3A]">Mon copain</span>
        <div className="w-[84px]" aria-hidden />
      </div>

      {/* Mascot on a soft pedestal */}
      <div
        className="flex aspect-square items-center justify-center rounded-full"
        style={{
          width: "clamp(190px,62%,300px)",
          background:
            "radial-gradient(circle at 50% 42%, #FFFFFF 0%, rgba(255,255,255,0.4) 55%, rgba(255,255,255,0) 72%)",
        }}
      >
        <Mascot config={config} mood="idle" size={mascotSize} />
      </div>

      {/* Big animated points balance */}
      <div
        ref={balanceRef}
        aria-label={`Tu as ${balance} ${balance > 1 ? "étoiles" : "étoile"}`}
        className="flex items-center gap-2 rounded-full font-black text-[#4A3B00]"
        style={{
          background: "linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)",
          padding: "clamp(8px,2.6vw,15px) clamp(22px,6.5vw,36px)",
          fontSize: "clamp(34px,11vw,62px)",
          boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)",
        }}
      >
        <span aria-hidden>⭐</span>
        <span>{balance}</span>
      </div>
      <p className="m-0 -mt-3 font-bold text-[#7A5A3A]">étoiles à dépenser</p>

      {/* Growth meter */}
      <div className="w-full max-w-[420px] rounded-3xl bg-white/70 p-4 shadow">
        <div className="mb-2 flex items-center justify-between text-lg font-black text-[#5A3A1E]">
          <span>🌱 Croissance</span>
          <span aria-hidden>
            {stage + 1}/{GROWTH_STAGES}
          </span>
        </div>
        <div
          className="h-5 w-full overflow-hidden rounded-full bg-[#E9DCC7]"
          role="progressbar"
          aria-valuemin={1}
          aria-valuemax={GROWTH_STAGES}
          aria-valuenow={stage + 1}
          aria-label="Croissance de ton copain"
        >
          <div
            ref={fillRef}
            className="h-full rounded-full"
            style={{ width: `${pct}%`, background: "linear-gradient(90deg,#AED581,#66BB6A)" }}
          />
        </div>
      </div>

      {/* Shop door */}
      <button
        onClick={onShop}
        className="mt-1 w-full max-w-[420px] rounded-full bg-[#66BB6A] px-8 py-4 text-2xl font-extrabold text-white active:scale-95"
        style={{ boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}
      >
        Boutique 🛍️
      </button>

      {/* Switch mascot — non-destructive; each friend keeps its own progress. */}
      <button
        onClick={onSwitch}
        className="w-full max-w-[420px] rounded-full bg-white/80 px-8 py-3 text-lg font-black text-[#5A3A1E] active:scale-95"
        style={{ boxShadow: "0 5px 0 rgba(0,0,0,0.08)" }}
      >
        Changer de copain 🔄
      </button>
    </div>
  );
}
