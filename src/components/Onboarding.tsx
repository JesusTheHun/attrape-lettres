import { useState } from "react";
import { Capacitor } from "@capacitor/core";
import { useEntitlement } from "../licensing/useEntitlement";
import { TRIAL_DAYS, UNLOCK_PRICE_EUR } from "../licensing/entitlement";
import { setConsent, track } from "../telemetry";

/* -------------------------------------------------------------------------- */
/* First launch, and the only screen written for the grown-up in the room.      */
/*                                                                             */
/* It does three jobs at once, on purpose. App Review 3.1.1 requires that a     */
/* time-based trial disclose its duration, what stops working, and the eventual */
/* charge BEFORE the trial starts — so a parent-facing screen has to exist      */
/* here anyway. Asking for analytics consent in a second modal would be one     */
/* interruption too many, and consent has to come from the parent regardless    */
/* (France sets the GDPR Art. 8 age at 15).                                     */
/*                                                                             */
/* The checkbox starts UNTICKED. Pre-ticked consent has been invalid since      */
/* CJEU Planet49, and an asymmetric pair of buttons reads as a dark pattern to  */
/* reviewers and to the CNIL alike — so "Commencer" carries no hidden opt-in    */
/* and declining costs the parent nothing.                                      */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const STAGE = "linear-gradient(180deg,#FFE7C9 0%,#FFEFD6 40%,#DCEFFB 100%)";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

const PRICE = UNLOCK_PRICE_EUR.toFixed(2).replace(".", ",");

export function Onboarding() {
  const { beginTrial, storeAvailable, priceLabel } = useEntitlement();
  const [analytics, setAnalytics] = useState(false);

  // Apple's Family Sharing really does cover the whole household on the £/€
  // unlock. Google Play Family Library explicitly does NOT share in-app
  // purchases, so the Android copy promises only what Android delivers.
  const ios = (() => {
    try {
      return Capacitor.getPlatform() === "ios";
    } catch {
      return false;
    }
  })();
  const scope = ios ? "pour toute la famille" : "sur vos appareils";

  const start = () => {
    setConsent(analytics);
    track("trial_started", { daysLeft: TRIAL_DAYS });
    beginTrial();
  };

  return (
    <div
      className="flex min-h-[100dvh] w-full items-center justify-center p-6"
      style={{ background: STAGE, fontFamily: ROUNDED }}
    >
      <div className="flex w-full max-w-md flex-col gap-5">
        <div className="leading-none" style={{ fontSize: "clamp(44px,13vw,64px)" }} aria-hidden>
          👋
        </div>

        <h1 className="m-0 font-black" style={{ color: INK, fontSize: "clamp(26px,7vw,36px)" }}>
          Nous aussi, on est parents.
        </h1>

        {storeAvailable ? (
          <div className="flex flex-col gap-3 text-base leading-snug" style={{ color: "#6B4A2C" }}>
            <p className="m-0">
              Attrape-Lettres est gratuit pendant <strong>{TRIAL_DAYS} jours</strong>. Ensuite, un
              achat unique de <strong>{priceLabel ?? `${PRICE} €`}</strong> débloque tout {scope},
              pour toujours. Pas d'abonnement, pas de publicité, rien à acheter dans le jeu.
            </p>
            <p className="m-0">
              Après {TRIAL_DAYS} jours, les exercices se mettent en pause. Les progrès, les étoiles
              et les mascottes sont gardés.
            </p>
          </div>
        ) : (
          <p className="m-0 text-base leading-snug" style={{ color: "#6B4A2C" }}>
            Attrape-Lettres apprend à lire aux enfants de six ans. Pas de publicité, pas de compte,
            rien à acheter — et tout fonctionne sans connexion.
          </p>
        )}

        <button
          type="button"
          onClick={start}
          className="w-full rounded-full px-8 py-4 text-xl font-extrabold text-white active:scale-95 [touch-action:manipulation]"
          style={{
            background: "#66BB6A",
            border: "none",
            boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)",
          }}
        >
          {storeAvailable ? `Commencer les ${TRIAL_DAYS} jours` : "Commencer"}
        </button>

        <label
          className="flex cursor-pointer items-start gap-3 rounded-2xl p-4"
          style={{ background: "rgba(255,255,255,0.7)" }}
        >
          <input
            type="checkbox"
            checked={analytics}
            onChange={(e) => setAnalytics(e.target.checked)}
            className="mt-1 h-5 w-5 shrink-0"
          />
          <span className="text-sm leading-snug" style={{ color: "#6B4A2C" }}>
            <strong style={{ color: INK }}>Nous aider à améliorer le jeu</strong>
            <br />
            On reçoit seulement : quel exercice, quel niveau, réussi ou non. Jamais le prénom de
            votre enfant, jamais rien qui l'identifie. Vous pouvez changer d'avis à tout moment.
          </span>
        </label>
      </div>
    </div>
  );
}
