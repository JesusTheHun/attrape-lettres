import { EarnBadge } from "./EarnBadge";
import { EndButtons } from "./EndButtons";

/**
 * Shared end-of-session screen. The star row mirrors the in-game strip: gold =
 * cleared first-try, greyed = the round took a wrong tap. Every finished run
 * now pays at least the curve's floor, so the `earned > 0` guard on the pill no
 * longer fires; it stays because the day an exercise pays nothing again, a
 * « +0 » would read as a punishment.
 */
export function Finished({
  onMenu,
  onNext,
  stars,
  earned,
  title,
}: {
  onMenu: () => void;
  onNext: () => void;
  /** Per-round first-try flags, same array the GameFrame strip rendered. */
  stars: ReadonlyArray<boolean>;
  earned: number;
  title: string;
}) {
  return (
    <div className="relative z-[41] flex flex-1 flex-col items-center justify-center gap-5 px-6 text-center">
      <div style={{ fontSize: "clamp(64px,20vw,110px)", lineHeight: 1 }}>🤩</div>
      {earned > 0 && <EarnBadge earned={earned} />}
      <div className="flex flex-wrap justify-center gap-1">
        {stars.map((s, i) => (
          <span
            key={i}
            style={{ fontSize: 28, ...(s ? null : { filter: "grayscale(1)", opacity: 0.45 }) }}
          >
            ⭐
          </span>
        ))}
      </div>
      <h2 className="m-0 font-black text-[#5A3A1E]" style={{ fontSize: "clamp(26px,7vw,40px)" }}>
        {title}
      </h2>
      <EndButtons onMenu={onMenu} onNext={onNext} />
    </div>
  );
}
