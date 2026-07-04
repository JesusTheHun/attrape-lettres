import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { EarnBadge } from "../components/EarnBadge";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import {
  buildSyllableRound,
  MODE_HINT,
  repeatSession,
  syllablePool,
  syllableTier,
  type SyllableRound,
} from "../levels";
import { useProfile } from "../hooks/useProfile";
import type { ExerciseId, Mood, SyllableMode, Verdict } from "../types";

const TRAY_COLORS = [
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#BA9EE8", ink: "#2C1846" },
  { bg: "#FF8A65", ink: "#4A2317" },
];

interface Props {
  exercise: ExerciseId;
  mode: SyllableMode;
  level: number;
  onBack: () => void;
}

/**
 * One engine for "fill-blank", "order" and "order-distractor". The mode only
 * changes how a round is *seeded* (buildSyllableRound); everything below —
 * slot assembly, forgiving picks, celebration — is shared.
 */
export function AssembleExercise({ exercise, mode, level, onBack }: Props) {
  const tier = useMemo(() => syllableTier(level), [level]);
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();

  const [session, setSession] = useState(() =>
    repeatSession(syllablePool(tier), tier.pick, tier.repeats)
  );
  const [idx, setIdx] = useState(0);
  const [round, setRound] = useState<SyllableRound>(() => buildSyllableRound(session[0], mode));
  const [slots, setSlots] = useState<(string | null)[]>(round.slots);
  // Which tray tile fills each slot, so a filled slot can be tapped to send its
  // tile back to the tray. `null` = empty, or a pre-revealed (locked) slot.
  const [slotTile, setSlotTile] = useState<(number | null)[]>(() => round.slots.map(() => null));
  const [used, setUsed] = useState<Set<number>>(new Set());
  const [mood, setMood] = useState<Mood>("idle");
  const [done, setDone] = useState(false);
  const [earned, setEarned] = useState(0);
  const locked = useRef(false);

  const loadRound = useCallback(
    (word: (typeof session)[number]) => {
      const r = buildSyllableRound(word, mode);
      setRound(r);
      setSlots(r.slots);
      setSlotTile(r.slots.map(() => null));
      setUsed(new Set());
      locked.current = false;
      audio.speak(word.word);
    },
    [audio, mode]
  );

  useEffect(() => {
    audio.unlock();
  }, [audio]);

  // Leaving the exercise fades the current line out over 200ms, then cuts.
  useEffect(() => () => audio.stop(), [audio]);

  useEffect(() => {
    const t = window.setTimeout(() => audio.speak(session[0].word), 350);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const restart = useCallback(() => {
    const next = repeatSession(syllablePool(tier), tier.pick, tier.repeats);
    setSession(next);
    setIdx(0);
    setMood("idle");
    setDone(false);
    loadRound(next[0]);
  }, [tier, loadRound]);

  // Drop a tapped tile into the next open slot. No per-tap judgement — the child
  // fills the whole row, and only a *complete* row is checked (below). Until then
  // any tile is welcome, and a wrong one can be tapped back out (removeAt).
  const pick = useCallback(
    (tileId: number, syllable: string): Verdict => {
      if (locked.current) return "reject";
      audio.unlock();
      audio.pop();

      const nextEmpty = slots.findIndex((s) => s === null);
      if (nextEmpty < 0) return "reject";

      const filled = slots.slice();
      filled[nextEmpty] = syllable;
      setSlots(filled);
      setSlotTile((prev) => {
        const n = prev.slice();
        n[nextEmpty] = tileId;
        return n;
      });
      setUsed((prev) => new Set(prev).add(tileId));

      if (!filled.every((s) => s !== null)) return "accept";

      // Row complete — judge the whole order at once.
      if (filled.every((s, i) => s === round.word.syllables[i])) {
        locked.current = true;
        setMood("happy");
        audio.success();
        fire();
        audio.speak(`Oui ! ${round.word.word}.`, { rate: 0.98 });
        window.setTimeout(() => {
          const nextIdx = idx + 1;
          if (nextIdx >= session.length) {
            setMood("cheer");
            setEarned(award(exercise, level));
            setDone(true);
            audio.speak("Bravo ! Tu as tout réussi !");
          } else {
            setMood("idle");
            setIdx(nextIdx);
            loadRound(session[nextIdx]);
          }
        }, 1500);
      } else {
        // Wrong order: "Oh non", then wipe the tray tiles back out so the child
        // retries the same word. Pre-revealed (locked) slots stay put.
        locked.current = true;
        audio.oops();
        audio.speak("Oh non ! On recommence.");
        window.setTimeout(() => {
          setSlots(round.slots.slice());
          setSlotTile(round.slots.map(() => null));
          setUsed(new Set());
          locked.current = false;
        }, 900);
      }
      return "accept";
    },
    [audio, award, exercise, fire, idx, level, loadRound, round, session, slots]
  );

  // Tap a filled slot to send its tile back to the tray — lets a child undo a
  // misplacement before the row is complete. Locked (pre-revealed) slots ignore.
  const removeAt = useCallback(
    (i: number) => {
      if (locked.current || round.locked[i]) return;
      const tileId = slotTile[i];
      if (tileId == null) return;
      audio.pop();
      setSlots((prev) => {
        const n = prev.slice();
        n[i] = null;
        return n;
      });
      setSlotTile((prev) => {
        const n = prev.slice();
        n[i] = null;
        return n;
      });
      setUsed((prev) => {
        const n = new Set(prev);
        n.delete(tileId);
        return n;
      });
    },
    [audio, round, slotTile]
  );

  return (
    <GameFrame onBack={onBack} done={idx} total={session.length} canvasRef={canvasRef}>
      {done ? (
        <Finished onReplay={restart} count={session.length} earned={earned} />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">{MODE_HINT[mode]}</p>
          <Mascot config={profile.config} mood={mood} />
          <div style={{ fontSize: "clamp(64px,22vw,120px)", lineHeight: 1.1, margin: "2px 0" }}>
            {round.word.emoji}
          </div>
          <button
            onPointerDown={() => audio.speak(round.word.word)}
            aria-label="Répéter le mot"
            className="mb-5 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>

          {/* Assembly slots — a filled, non-locked one is a button that pops its
              tile back to the tray so a misplacement can be fixed mid-row. */}
          <div className="mb-6 flex flex-wrap items-center justify-center gap-2">
            {slots.map((s, i) => {
              const removable = s != null && !round.locked[i];
              const style = {
                minWidth: "clamp(56px,16vw,84px)",
                height: "clamp(56px,16vw,84px)",
                padding: "0 10px",
                fontSize: "clamp(22px,6vw,40px)",
                borderRadius: 20,
                color: "#5A3A1E",
                background: s ? "#FFFFFF" : "transparent",
                border: s ? "none" : round.locked[i] ? "3px dashed #C9A87A" : "3px dashed #E4A15E",
                boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
              } as const;
              return removable ? (
                <button
                  key={i}
                  onPointerDown={() => removeAt(i)}
                  aria-label={`Retirer ${s}`}
                  className="flex items-center justify-center border-none font-black [touch-action:none]"
                  style={{ ...style, cursor: "pointer" }}
                >
                  {s}
                </button>
              ) : (
                <div
                  key={i}
                  className="flex items-center justify-center font-black"
                  style={style}
                >
                  {s ?? ""}
                </div>
              );
            })}
          </div>

          {/* Tray */}
          <div className="flex flex-wrap items-center justify-center gap-3">
            {round.tray.map((t, i) => (
              <Tile
                key={t.id}
                bg={TRAY_COLORS[i % TRAY_COLORS.length].bg}
                ink={TRAY_COLORS[i % TRAY_COLORS.length].ink}
                disabled={used.has(t.id)}
                onPick={() => pick(t.id, t.syllable)}
                onPreview={() => {
                  audio.unlock();
                  audio.speak(t.syllable);
                }}
                previewLabel={`Écouter ${t.syllable}`}
                size="clamp(64px,18vw,100px)"
                fontSize="clamp(20px,5.5vw,36px)"
                ariaLabel={`Syllabe ${t.syllable}`}
              >
                {t.syllable}
              </Tile>
            ))}
          </div>
        </div>
      )}
    </GameFrame>
  );
}

function Finished({
  onReplay,
  count,
  earned,
}: {
  onReplay: () => void;
  count: number;
  earned: number;
}) {
  return (
    <div className="relative z-[41] flex flex-1 flex-col items-center justify-center gap-5 px-6 text-center">
      <div style={{ fontSize: "clamp(64px,20vw,110px)", lineHeight: 1 }}>🤩</div>
      <EarnBadge earned={earned} />
      <div className="flex gap-1">
        {Array.from({ length: count }).map((_, i) => (
          <span key={i} style={{ fontSize: 28 }}>⭐</span>
        ))}
      </div>
      <h2 className="m-0 font-black text-[#5A3A1E]" style={{ fontSize: "clamp(26px,7vw,40px)" }}>
        Tu as tout réussi !
      </h2>
      <button
        onPointerDown={onReplay}
        className="mt-1 rounded-full bg-[#66BB6A] px-9 py-4 text-2xl font-extrabold text-white [touch-action:none]"
        style={{ boxShadow: "0 8px 0 #43A047, 0 14px 24px rgba(0,0,0,0.2)" }}
      >
        Rejouer
      </button>
    </div>
  );
}
