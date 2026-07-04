import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { EarnBadge } from "../components/EarnBadge";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import {
  buildSoundRound,
  buildSoundSession,
  soundLevel,
  soundPrompt,
  soundSuccess,
  type SoundRound,
} from "../levels";
import { useProfile } from "../hooks/useProfile";
import type { ExerciseId, Mood, Verdict } from "../types";

const TRAY_COLORS = [
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#BA9EE8", ink: "#2C1846" },
  { bg: "#FF8A65", ink: "#4A2317" },
];

interface Props {
  exercise: ExerciseId;
  level: number;
  onBack: () => void;
}

/**
 * Hear a sound, re-spell it by tapping letters in order. Same forgiving loop as
 * AssembleExercise (feedback on pointerdown, WAAPI shake, canvas confetti), but
 * the tiles are LETTERS and the target is a grapheme. The upper levels reuse a
 * sound across several spellings so the child learns o / au / eau, f / ph, ….
 */
export function SpellSoundExercise({ exercise, level, onBack }: Props) {
  const cfg = useMemo(() => soundLevel(level), [level]);
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();

  const [session, setSession] = useState(() => buildSoundSession(level));
  const [idx, setIdx] = useState(0);
  const [round, setRound] = useState<SoundRound>(() =>
    buildSoundRound(session[0], cfg.distractors)
  );
  const [slots, setSlots] = useState<(string | null)[]>(round.slots);
  const [used, setUsed] = useState<Set<number>>(new Set());
  const [mood, setMood] = useState<Mood>("idle");
  const [done, setDone] = useState(false);
  const [earned, setEarned] = useState(0);
  const locked = useRef(false);

  const loadRound = useCallback(
    (target: (typeof session)[number]) => {
      const r = buildSoundRound(target, cfg.distractors);
      setRound(r);
      setSlots(r.slots);
      setUsed(new Set());
      locked.current = false;
      audio.speak(soundPrompt(target));
    },
    [audio, cfg.distractors]
  );

  useEffect(() => {
    audio.unlock();
  }, [audio]);

  // Leaving the exercise fades the current line out over 200ms, then cuts.
  useEffect(() => () => audio.stop(), [audio]);

  useEffect(() => {
    const t = window.setTimeout(() => audio.speak(soundPrompt(session[0])), 350);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const restart = useCallback(() => {
    const next = buildSoundSession(level);
    setSession(next);
    setIdx(0);
    setMood("idle");
    setDone(false);
    loadRound(next[0]);
  }, [level, loadRound]);

  const pick = useCallback(
    (tileId: number, letter: string): Verdict => {
      if (locked.current) return "reject";
      audio.unlock();
      audio.pop();

      const nextEmpty = slots.findIndex((s) => s === null);
      if (nextEmpty < 0) return "reject";
      if (letter !== round.target.spelling[nextEmpty]) {
        audio.nudge();
        return "reject";
      }

      const filled = slots.slice();
      filled[nextEmpty] = letter;
      setSlots(filled);
      setUsed((prev) => new Set(prev).add(tileId));

      if (filled.every((s) => s !== null)) {
        locked.current = true;
        setMood("happy");
        audio.success();
        fire();
        audio.speak(soundSuccess(round.target), { rate: 0.98 });
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
      }
      return "accept";
    },
    [audio, award, exercise, fire, idx, level, loadRound, round, session, slots]
  );

  const target = round.target;

  return (
    <GameFrame onBack={onBack} done={idx} total={session.length} canvasRef={canvasRef}>
      {done ? (
        <Finished onReplay={restart} count={session.length} earned={earned} />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">
            Écoute le son et écris-le avec les lettres
          </p>
          <Mascot config={profile.config} mood={mood} />
          <div
            aria-hidden
            style={{ fontSize: "clamp(56px,18vw,104px)", lineHeight: 1.1, margin: "2px 0" }}
          >
            {target.emoji ?? "🎧"}
          </div>
          <div
            className="mb-1 rounded-2xl bg-white/70 px-4 py-1 font-black text-[#5A3A1E]"
            style={{ fontSize: "clamp(22px,6vw,36px)" }}
          >
            «&nbsp;{target.sound}&nbsp;»
          </div>
          <button
            onPointerDown={() => audio.speak(soundPrompt(target))}
            aria-label="Réécouter le son"
            className="mb-5 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>

          {/* Spelling slots */}
          <div className="mb-6 flex flex-wrap items-center justify-center gap-2">
            {slots.map((s, i) => (
              <div
                key={i}
                className="flex items-center justify-center font-black"
                style={{
                  minWidth: "clamp(52px,15vw,76px)",
                  height: "clamp(52px,15vw,76px)",
                  padding: "0 8px",
                  fontSize: "clamp(24px,7vw,44px)",
                  borderRadius: 20,
                  color: "#5A3A1E",
                  background: s ? "#FFFFFF" : "transparent",
                  border: s ? "none" : "3px dashed #E4A15E",
                  boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
                }}
              >
                {s ?? ""}
              </div>
            ))}
          </div>

          {/* Letter tray */}
          <div className="flex flex-wrap items-center justify-center gap-3">
            {round.tray.map((t, i) => (
              <Tile
                key={t.id}
                bg={TRAY_COLORS[i % TRAY_COLORS.length].bg}
                ink={TRAY_COLORS[i % TRAY_COLORS.length].ink}
                disabled={used.has(t.id)}
                onPick={() => pick(t.id, t.letter)}
                size="clamp(60px,17vw,92px)"
                fontSize="clamp(26px,7vw,48px)"
                ariaLabel={`Lettre ${t.letter}`}
              >
                {t.letter}
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
