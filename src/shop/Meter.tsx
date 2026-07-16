import { useEffect, useRef } from "react";
import { meterFill, tipSparkle } from "./anim";

/* -------------------------------------------------------------------------- */
/* SavingsMeter — "how close am I to affording this?" without any arithmetic.   */
/* A fat gold bar filling toward the price: a 6yo reads fills, not subtraction. */
/* On mount it sweeps from the balance the child LAST SAW in the shop to        */
/* today's (parent passes `since`), so stars earned between visits show as      */
/* motion + a sparkle — never an invisibly small static delta. A non-empty      */
/* wallet always shows a sliver (min fill), so "I have some" is always visible. */
/* -------------------------------------------------------------------------- */

/** Never render progress smaller than this once the wallet is non-empty. */
const MIN_FILL = 0.07;

function fillRatio(balance: number, cost: number): number {
  if (balance <= 0 || cost <= 0) return 0;
  return Math.max(MIN_FILL, Math.min(1, balance / cost));
}

interface SavingsMeterProps {
  cost: number;
  balance: number;
  /** Balance at the previous shop visit — where the fill animates FROM. */
  since: number;
  /** Bar height in px. Keep it fat: thin bars hide small progress. */
  height?: number;
}

export function SavingsMeter({ cost, balance, since, height = 10 }: SavingsMeterProps) {
  const fillRef = useRef<HTMLSpanElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const shownRef = useRef(fillRatio(since, cost)); // ratio currently on screen
  const ratio = fillRatio(balance, cost);

  useEffect(() => {
    const from = shownRef.current;
    shownRef.current = ratio;
    if (from === ratio) return;
    meterFill(fillRef.current, from * 100, ratio * 100);
    if (ratio > from) tipSparkle(tipRef.current);
  }, [ratio]);

  return (
    <span
      className="relative block w-full overflow-visible"
      role="img"
      aria-label={`${balance} étoiles sur ${cost}`}
    >
      <span
        className="block w-full overflow-hidden rounded-full"
        style={{ height, background: "rgba(90,58,30,0.14)" }}
      >
        <span
          ref={fillRef}
          className="block h-full rounded-full"
          style={{
            width: `${ratio * 100}%`,
            background: "linear-gradient(90deg,#FFC107,#FFD54F)",
          }}
        />
      </span>
      {/* Sparkle parked (invisible) at the fill tip; tipSparkle() flashes it. */}
      <span
        ref={tipRef}
        aria-hidden
        className="pointer-events-none absolute text-sm leading-none opacity-0"
        style={{ left: `${ratio * 100}%`, top: "50%" }}
      >
        ✨
      </span>
    </span>
  );
}
