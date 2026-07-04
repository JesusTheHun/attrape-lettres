import { useCallback, useEffect, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { EarnBadge } from "../components/EarnBadge";
import { EndButtons } from "../components/EndButtons";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import { useProfile } from "../hooks/useProfile";
import { FIRST_LETTER_LEVELS, firstLetterPool, repeatSession, shuffle } from "../levels";
import type { ExerciseId, FirstLetterRound, Mood, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#FF8A65", ink: "#4A2317" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#4FC3F7", ink: "#062E3D" },
];

function buildSession(level: number): FirstLetterRound[] {
  const cfg = FIRST_LETTER_LEVELS[level - 1];
  const pool = firstLetterPool(level);
  const catalog = cfg.letters ?? [...new Set(pool.map((w) => w.letter))];
  // Pick distinct words, replay a few (spaced) to reinforce — never back-to-back.
  return repeatSession(pool, cfg.pick, cfg.repeats).map((target) => {
    const distractors = shuffle(catalog.filter((l) => l !== target.letter)).slice(0, 2);
    return { target, choices: shuffle([target.letter, ...distractors]) };
  });
}

export function FirstLetterExercise({
  exercise,
  level,
  onBack,
  onNext,
}: {
  exercise: ExerciseId;
  level: number;
  onBack: () => void;
  onNext: () => void;
}) {
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();
  const [session] = useState<FirstLetterRound[]>(() => buildSession(level));
  const [idx, setIdx] = useState(0);
  const [mood, setMood] = useState<Mood>("idle");
  const [flash, setFlash] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [earned, setEarned] = useState(0);
  const locked = useRef(false);
  const advanceTimer = useRef<number | null>(null);

  const round = session[idx];

  const prompt = useCallback(
    (word: string) => audio.speak(`Trouve la première lettre de ${word}.`),
    [audio]
  );

  useEffect(() => {
    audio.unlock();
  }, [audio]);

  // Leaving the exercise fades the current line out over 200ms, then cuts.
  useEffect(() => () => audio.stop(), [audio]);

  // Never leave a pending advance running past unmount.
  useEffect(
    () => () => {
      if (advanceTimer.current !== null) window.clearTimeout(advanceTimer.current);
    },
    []
  );

  useEffect(() => {
    if (done || !round) return;
    // Settle the new round before announcing (matches Assemble/SpellSound). The
    // clearTimeout cleanup also collapses StrictMode's double-mount to one announce.
    const t = window.setTimeout(() => prompt(round.target.word), 350);
    return () => window.clearTimeout(t);
  }, [idx, done, round, prompt]);

  const pick = useCallback(
    (letter: string): Verdict => {
      if (locked.current) return "reject";
      audio.unlock();
      audio.pop();
      if (letter !== round.target.letter) {
        audio.nudge();
        return "reject";
      }
      locked.current = true;
      setFlash(letter);
      setMood("happy");
      audio.success();
      fire();
      // Advance only once the success line has finished — the clips run 2–4.5s,
      // so a fixed timer used to cut them off. The fallback rescues a stalled
      // clip (backgrounded tab, media error) so the child is never stranded.
      const next = idx + 1;
      let advanced = false;
      const advance = () => {
        if (advanced) return;
        advanced = true;
        if (advanceTimer.current !== null) {
          window.clearTimeout(advanceTimer.current);
          advanceTimer.current = null;
        }
        setFlash(null);
        locked.current = false;
        if (next >= session.length) {
          setMood("cheer");
          setEarned(award(exercise, level));
          setDone(true);
          audio.speak("Bravo ! Tu as tout trouvé !");
        } else {
          setMood("idle");
          setIdx(next);
        }
      };
      audio.speak(`Oui ! ${round.target.letter}. ${round.target.word}.`, { rate: 0.98, onEnd: advance });
      advanceTimer.current = window.setTimeout(advance, 6000);
      return "accept";
    },
    [audio, award, exercise, fire, idx, level, round, session.length]
  );

  return (
    <GameFrame onBack={onBack} done={idx} total={session.length} canvasRef={canvasRef}>
      {done ? (
        <Finished onMenu={onBack} onNext={onNext} count={session.length} earned={earned} />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <Mascot config={profile.config} mood={mood} />
          <div style={{ fontSize: "clamp(80px,28vw,150px)", lineHeight: 1.1, margin: "6px 0" }}>
            {round.target.emoji}
          </div>
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              prompt(round.target.word);
            }}
            aria-label="Répéter le mot"
            className="mb-6 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 {round.target.word}
          </button>
          <div className="flex flex-wrap items-center justify-center gap-4">
            {round.choices.map((letter, i) => (
              <Tile
                key={letter}
                bg={TILE_COLORS[i % TILE_COLORS.length].bg}
                ink={TILE_COLORS[i % TILE_COLORS.length].ink}
                highlight={flash === letter}
                disabled={flash != null}
                onPick={() => pick(letter)}
                onPreview={() => {
                  audio.unlock();
                  audio.speak(letter);
                }}
                previewLabel={`Écouter ${letter}`}
                ariaLabel={`Lettre ${letter}`}
              >
                {letter}
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
        Tu as tout trouvé !
      </h2>
      <EndButtons onMenu={onMenu} onNext={onNext} />
    </div>
  );
}
