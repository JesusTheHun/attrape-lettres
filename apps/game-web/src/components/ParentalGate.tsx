import { useMemo, useRef, useState } from "react";

/* -------------------------------------------------------------------------- */
/* Parental gate. Kids Category guideline 1.3: an app in the Kids Category may  */
/* not put a purchase, an external link or any other "distraction" in front of  */
/* a child unless it sits behind one of these.                                  */
/*                                                                             */
/* A two-digit multiplication is the standard because it is the cheapest thing  */
/* a six-year-old genuinely cannot do and an adult does without thinking. The   */
/* operands are re-rolled on every open, so a child who watches once learns     */
/* nothing. Deliberately NOT kid-styled: the tone change is half the signal     */
/* that this screen is not for them.                                            */
/* -------------------------------------------------------------------------- */

const INK = "#5A3A1E";
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";

/** Two single digits 3..9 — product 9..81, never a trivial ×1 or ×2. */
function roll(): [number, number] {
  const d = () => 3 + Math.floor(Math.random() * 7);
  return [d(), d()];
}

export function ParentalGate({
  onPass,
  onCancel,
  reason,
}: {
  onPass: () => void;
  onCancel: () => void;
  /** One line telling the adult what they are unlocking. */
  reason: string;
}) {
  const [[a, b]] = useState(roll);
  const [value, setValue] = useState("");
  const [wrong, setWrong] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const answer = useMemo(() => a * b, [a, b]);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (Number(value) === answer) return onPass();
    setWrong(true);
    setValue("");
    inputRef.current?.focus();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-6"
      style={{ background: "rgba(30,20,10,0.55)", fontFamily: ROUNDED }}
      role="dialog"
      aria-modal="true"
      aria-label="Espace parents"
    >
      <form
        onSubmit={submit}
        className="flex w-full max-w-sm flex-col gap-4 rounded-3xl p-6 shadow-2xl"
        style={{ background: "#FFFDF8" }}
      >
        <h2 className="m-0 text-lg font-bold" style={{ color: INK }}>
          Espace parents
        </h2>
        <p className="m-0 text-sm leading-snug" style={{ color: "#7A5B3C" }}>
          {reason}
        </p>
        <label className="text-sm font-semibold" style={{ color: INK }} htmlFor="gate-answer">
          Combien font {a} × {b} ?
        </label>
        <input
          id="gate-answer"
          ref={inputRef}
          value={value}
          onChange={(e) => {
            setValue(e.target.value.replace(/\D/g, "").slice(0, 3));
            setWrong(false);
          }}
          inputMode="numeric"
          autoFocus
          autoComplete="off"
          aria-describedby={wrong ? "gate-error" : undefined}
          className="w-full rounded-2xl px-4 py-3 text-center text-2xl font-bold"
          style={{
            color: INK,
            background: "#FFF",
            border: `2px solid ${wrong ? "#E5736A" : "#E6D8C6"}`,
            outline: "none",
          }}
        />
        {wrong && (
          <p id="gate-error" role="alert" className="m-0 text-sm" style={{ color: "#C4544A" }}>
            Ce n'est pas le bon résultat.
          </p>
        )}
        <div className="flex gap-3">
          <button
            type="button"
            onClick={onCancel}
            className="flex-1 rounded-full px-4 py-3 font-semibold [touch-action:manipulation]"
            style={{ color: INK, background: "#F0E6D8", border: "none" }}
          >
            Annuler
          </button>
          <button
            type="submit"
            disabled={value.length === 0}
            className="flex-1 rounded-full px-4 py-3 font-bold text-white disabled:opacity-40 [touch-action:manipulation]"
            style={{ background: "#66BB6A", border: "none" }}
          >
            Continuer
          </button>
        </div>
      </form>
    </div>
  );
}
