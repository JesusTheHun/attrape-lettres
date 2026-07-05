import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { EarnBadge } from "../components/EarnBadge";
import { EndButtons } from "../components/EndButtons";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { WordIcon } from "../components/WordIcon";
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
  onNext: () => void;
}

/**
 * One engine for "fill-blank", "order" and "order-distractor". The mode only
 * changes how a round is *seeded* (buildSyllableRound); everything below —
 * slot assembly, forgiving picks, celebration — is shared.
 */
export function AssembleExercise({ exercise, mode, level, onBack, onNext }: Props) {
  const tier = useMemo(() => syllableTier(level), [level]);
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();

  const [session] = useState(() =>
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
  const mountedRef = useRef(true);

  const loadRound = useCallback(
    (word: (typeof session)[number]) => {
      const r = buildSyllableRound(word, mode);
      setRound(r);
      setSlots(r.slots);
      setSlotTile(r.slots.map(() => null));
      setUsed(new Set());
      locked.current = false;
      void audio.say(word.word);
    },
    [audio, mode]
  );

  useEffect(() => {
    audio.unlock();
  }, [audio]);

  // Leaving the exercise fades the current line out over 200ms, then cuts.
  useEffect(() => () => audio.stop(), [audio]);

  // Async celebrate/retry steps below bail if the exercise unmounted mid-line.
  // Set true on mount too, so StrictMode's dev remount doesn't leave it false.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const t = window.setTimeout(() => void audio.say(session[0].word), 350);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
        // Advance only after the success line has played in full. `ok` is false
        // if it was cut short (child left, watchdog) — then we don't advance.
        const nextIdx = idx + 1;
        void (async () => {
          const ok = await audio.say(`Oui ! ${round.word.word}.`, { rate: 0.98 });
          if (!ok || !mountedRef.current) return;
          if (nextIdx >= session.length) {
            setMood("cheer");
            setEarned(award(exercise, level));
            setDone(true);
            void audio.say("Bravo ! Tu as tout réussi !");
          } else {
            setMood("idle");
            setIdx(nextIdx);
            loadRound(session[nextIdx]);
          }
        })();
      } else {
        // Wrong order: "Oh non", then — once it has finished speaking — wipe the
        // tray tiles back out so the child retries. Waiting on the line means the
        // reset (and re-enabling taps) can't cut "Oh non" off. Pre-revealed
        // (locked) slots stay put.
        locked.current = true;
        audio.oops();
        void (async () => {
          await audio.say("Oh non ! On recommence.");
          if (!mountedRef.current) return;
          setSlots(round.slots.slice());
          setSlotTile(round.slots.map(() => null));
          setUsed(new Set());
          locked.current = false;
        })();
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
        <Finished onMenu={onBack} onNext={onNext} count={session.length} earned={earned} />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">{MODE_HINT[mode]}</p>
          <Mascot config={profile.config} mood={mood} />
          <div style={{ margin: "2px 0" }}>
            <WordIcon
              emoji={round.word.emoji}
              img={round.word.img}
              size="clamp(64px,22vw,120px)"
            />
          </div>
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              void audio.say(round.word.word);
            }}
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
                  if (locked.current) return; // don't cut the success line mid-celebration
                  audio.unlock();
                  void audio.say(t.syllable);
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
  onMenu,
  onNext,
  count,
  earned,
}: {
  onMenu: () => void;
  onNext: () => void;
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
      <EndButtons onMenu={onMenu} onNext={onNext} />
    </div>
  );
}
