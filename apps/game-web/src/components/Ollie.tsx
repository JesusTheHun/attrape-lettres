import type { Mood } from "../types";

const FACE: Record<Mood, string> = { idle: "🦉", happy: "🥳", cheer: "🤩" };

export function Ollie({ mood }: { mood: Mood }) {
  const anim =
    mood === "idle"
      ? "animate-[ollieBob_2.6s_ease-in-out_infinite]"
      : "animate-[olliePop_0.5s_ease]";
  return (
    <div
      className={`ollie-anim select-none leading-none drop-shadow-lg ${anim}`}
      style={{ fontSize: "clamp(52px, 15vw, 88px)" }}
    >
      {FACE[mood]}
    </div>
  );
}
