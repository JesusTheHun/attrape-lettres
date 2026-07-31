import { usePopFlourish } from "./usePopFlourish";

/**
 * The "+N ⭐" earn pill shown on a Finished screen. It pops in with a one-shot
 * WAAPI flourish (see usePopFlourish) so the reward reads as a little event, not
 * static text. Owned by AGENT B.
 */
export function EarnBadge({ earned }: { earned: number }) {
  const ref = usePopFlourish<HTMLDivElement>();
  return (
    <div
      ref={ref}
      aria-label={`Tu gagnes ${earned} étoiles`}
      className="flex items-center gap-2 rounded-full font-black text-[#4A3B00]"
      style={{
        background: "linear-gradient(180deg,#FFDE6B 0%,#FFC107 100%)",
        padding: "clamp(8px,2.4vw,14px) clamp(18px,5vw,30px)",
        fontSize: "clamp(28px,8vw,46px)",
        boxShadow: "0 8px 0 #E0A800, 0 16px 26px rgba(0,0,0,0.2)",
      }}
    >
      <span>+{earned}</span>
      <span aria-hidden>⭐</span>
    </div>
  );
}
