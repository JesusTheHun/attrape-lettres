import { useState } from "react";
import { useEntitlement } from "../licensing/useEntitlement";
import { UNLOCK_PRICE_EUR } from "../licensing/entitlement";
import { hasConsent, setConsent, track } from "../telemetry";
import { ParentalGate } from "./ParentalGate";

/* -------------------------------------------------------------------------- */
/* End of the trial.                                                           */
/*                                                                             */
/* Three layers, and the order is the whole design. A child who taps an         */
/* exercise sees a calm, kid-legible "ask a grown-up" — never a price, never a  */
/* buy button (Kids Category 1.3 forbids putting a purchase in front of them,   */
/* and invariant 3 forbids making a child feel they failed at something). The   */
/* parental gate is the door. Only behind it does money appear.                 */
/*                                                                             */
/* Nothing is confiscated: the hub, the mascot, the shop and every star stay    */
/* exactly where they were. Only starting a NEW round is paused.                */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 40%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const PRICE = UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",");

type Step = "child" | "gate" | "parent";

export function Paywall({ onBack }: { onBack: () => void }) {
  const { purchase, restore, priceLabel, storeAvailable } = useEntitlement();
  const [step, setStep] = useState<Step>("child");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [analytics, setAnalytics] = useState(hasConsent);

  const buy = async () => {
    setBusy(true);
    setNote(null);
    const ok = await purchase();
    setBusy(false);
    track(ok ? "purchase_completed" : "purchase_failed");
    if (!ok) setNote("L'achat n'a pas abouti. Rien n'a été débité.");
  };

  const redo = async () => {
    setBusy(true);
    setNote(null);
    const ok = await restore();
    setBusy(false);
    track("purchase_restored");
    setNote(ok ? "Achat restauré." : "Aucun achat trouvé sur ce compte.");
  };

  if (step === "gate") {
    return (
      <ParentalGate
        reason="Cette page contient un achat. Elle est réservée aux adultes."
        onPass={() => {
          setStep("parent");
          track("paywall_shown");
        }}
        onCancel={() => setStep("child")}
      />
    );
  }

  if (step === "child") {
    return (
      <div
        className="flex min-h-[100dvh] w-full flex-col items-center justify-center gap-6 p-6 text-center"
        style={{ background: STAGE, fontFamily: ROUNDED }}
      >
        <div className="leading-none" style={{ fontSize: "clamp(56px,17vw,90px)" }} aria-hidden>
          🌙
        </div>
        <h1 className="m-0 font-black" style={{ color: INK, fontSize: "clamp(24px,7vw,34px)" }}>
          Les jeux font une pause
        </h1>
        <p className="m-0 max-w-xs text-lg leading-snug" style={{ color: "#6B4A2C" }}>
          Demande à un grand&nbsp;! Tes étoiles et ta mascotte t'attendent.
        </p>
        <button
          type="button"
          onClick={onBack}
          className="w-full max-w-xs rounded-full px-8 py-4 text-xl font-extrabold text-white active:scale-95 [touch-action:manipulation]"
          style={{
            background: "#66BB6A",
            border: "none",
            boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)",
          }}
        >
          Voir ma mascotte
        </button>
        <button
          type="button"
          onClick={() => setStep("gate")}
          className="text-base font-semibold underline [touch-action:manipulation]"
          style={{ color: "#8A6A4A", background: "none", border: "none" }}
        >
          Je suis un adulte
        </button>
      </div>
    );
  }

  return (
    <div
      className="flex min-h-[100dvh] w-full items-center justify-center p-6"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <div className="flex w-full max-w-md flex-col gap-5">
        <h1 className="m-0 font-black" style={{ color: INK, fontSize: "clamp(24px,7vw,32px)" }}>
          Débloquer Attrape-Lettres
        </h1>
        <p className="m-0 text-base leading-snug" style={{ color: "#6B4A2C" }}>
          Un achat unique de <strong>{priceLabel ?? `${PRICE} €`}</strong>. Pas d'abonnement, pas de
          publicité, rien d'autre à acheter. Les progrès de vos enfants sont déjà enregistrés.
        </p>

        {storeAvailable ? (
          <>
            <button
              type="button"
              onClick={() => void buy()}
              disabled={busy}
              className="w-full rounded-full px-8 py-4 text-xl font-extrabold text-white active:scale-95 disabled:opacity-50 [touch-action:manipulation]"
              style={{
                background: "#66BB6A",
                border: "none",
                boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)",
              }}
            >
              {busy ? "…" : `Débloquer — ${priceLabel ?? `${PRICE} €`}`}
            </button>
            {/* Apple requires a restore control to exist for non-consumables. */}
            <button
              type="button"
              onClick={() => void redo()}
              disabled={busy}
              className="w-full rounded-full px-6 py-3 font-semibold disabled:opacity-50 [touch-action:manipulation]"
              style={{ color: INK, background: "#F0E6D8", border: "none" }}
            >
              Restaurer un achat
            </button>
          </>
        ) : (
          <p className="m-0 text-base" style={{ color: "#6B4A2C" }}>
            L'achat se fait depuis l'application installée sur le téléphone ou la tablette.
          </p>
        )}

        {note && (
          <p role="status" className="m-0 text-sm" style={{ color: "#8A6A4A" }}>
            {note}
          </p>
        )}

        {/* Withdrawal must be as easy as consent (GDPR Art. 7(3)), so the same
            toggle lives here, behind the gate the parent has already passed. */}
        <label
          className="flex cursor-pointer items-start gap-3 rounded-2xl p-4"
          style={{ background: "rgba(255,255,255,0.7)" }}
        >
          <input
            type="checkbox"
            checked={analytics}
            onChange={(e) => {
              setAnalytics(e.target.checked);
              setConsent(e.target.checked);
            }}
            className="mt-1 h-5 w-5 shrink-0"
          />
          <span className="text-sm leading-snug" style={{ color: "#6B4A2C" }}>
            Nous aider à améliorer le jeu — exercice, niveau, réussi ou non. Jamais le prénom de
            votre enfant.
          </span>
        </label>

        <button
          type="button"
          onClick={onBack}
          className="text-base font-semibold underline [touch-action:manipulation]"
          style={{ color: "#8A6A4A", background: "none", border: "none" }}
        >
          Retour au jeu
        </button>
      </div>
    </div>
  );
}
