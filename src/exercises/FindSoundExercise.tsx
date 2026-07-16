import { useCallback, useEffect, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { Finished } from "../components/Finished";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { WordIcon } from "../components/WordIcon";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import { useProfile } from "../hooks/useProfile";
import {
  buildFindSoundSession,
  findSoundPrompt,
  findSoundSuccess,
  type FindSoundRound,
} from "../levels";
import { MISS_COOLDOWN_MS } from "../rewards";
import type { ExerciseId, Mood, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#FF8A65", ink: "#4A2317" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#4FC3F7", ink: "#062E3D" },
];

/**
 * Hear a sound with its anchor (« ou, comme dans hibou »), tap the tile that
 * WRITES it. The youngest rung of the sound ladder — same one-prompt/one-tile
 * loop as first-letter, so a pre-reader needs no new pattern: the audio names
 * the target, the tiles are graphies, and "Écouter" under each tile lets the
 * child compare sounds by ear before committing. Recognition here; the
 * spell-sound exercise is the production twin.
 */
export function FindSoundExercise({
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
  const [session] = useState<FindSoundRound[]>(() => buildFindSoundSession(level));
  const [idx, setIdx] = useState(0);
  const [mood, setMood] = useState<Mood>("idle");
  const [flash, setFlash] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [earned, setEarned] = useState(0);
  // One winnable star per round; a wrong tap greys it out on the spot. The ref
  // mirrors the state so the award closure reads a never-stale count.
  const [stars, setStars] = useState<boolean[]>(() => session.map(() => true));
  const starsRef = useRef(stars);
  // Picks are swallowed for a beat after a miss — spam can't machine-gun through.
  const coolUntil = useRef(0);
  const locked = useRef(false);
  const mountedRef = useRef(true);

  const missRound = useCallback((i: number) => {
    if (!starsRef.current[i]) return;
    starsRef.current = starsRef.current.map((s, j) => (j === i ? false : s));
    setStars(starsRef.current);
  }, []);

  const round = session[idx];

  useEffect(() => {
    audio.unlock();
  }, [audio]);

  // Leaving the exercise fades the current line out over 200ms, then cuts.
  useEffect(() => () => audio.stop(), [audio]);

  // The async celebrate step below bails if the exercise unmounted mid-line.
  // Set true on mount too, so StrictMode's dev remount doesn't leave it false.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (done || !round) return;
    // Settle the new round before announcing (matches FirstLetter/SpellSound).
    const t = window.setTimeout(() => void audio.say(findSoundPrompt(round.target)), 350);
    return () => window.clearTimeout(t);
  }, [idx, done, round, audio]);

  const pick = useCallback(
    (graphy: string): Verdict => {
      if (locked.current) return "reject";
      if (performance.now() < coolUntil.current) return "reject"; // silent while the shake plays
      audio.unlock();
      audio.pop();
      if (graphy !== round.target.graphy) {
        audio.nudge();
        coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
        missRound(idx); // the star greys NOW, same beat as the shake
        return "reject";
      }
      locked.current = true;
      setFlash(graphy);
      setMood("happy");
      audio.success();
      fire();
      // Advance only after the success line has played in full. `ok` is false if
      // it was cut short (child left, watchdog) — then we don't advance.
      const next = idx + 1;
      void (async () => {
        const ok = await audio.say(findSoundSuccess(round.target), { rate: 0.98 });
        if (!ok || !mountedRef.current) return;
        setFlash(null);
        locked.current = false;
        if (next >= session.length) {
          setMood("cheer");
          setEarned(
            award(exercise, level, starsRef.current.filter(Boolean).length, session.length)
          );
          setDone(true);
          void audio.say("Bravo ! Tu as tout trouvé !");
        } else {
          setMood("idle");
          setIdx(next);
        }
      })();
      return "accept";
    },
    [audio, award, exercise, fire, idx, level, missRound, round, session.length]
  );

  return (
    <GameFrame
      onBack={onBack}
      done={done ? session.length : idx}
      total={session.length}
      stars={stars}
      canvasRef={canvasRef}
    >
      {done ? (
        <Finished onMenu={onBack} onNext={onNext} stars={stars} earned={earned} title="Tu as tout trouvé !" />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">
            Écoute le son et trouve comment il s'écrit
          </p>
          <Mascot config={profile.config} mood={mood} />
          <div style={{ margin: "6px 0" }}>
            <WordIcon emoji={round.target.emoji} size="clamp(80px,28vw,150px)" />
          </div>
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              void audio.say(findSoundPrompt(round.target));
            }}
            aria-label="Réécouter le son"
            className="mb-6 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>
          <div className="flex flex-wrap items-center justify-center gap-4">
            {round.choices.map((choice, i) => (
              <Tile
                key={choice.graphy}
                bg={TILE_COLORS[i % TILE_COLORS.length].bg}
                ink={TILE_COLORS[i % TILE_COLORS.length].ink}
                highlight={flash === choice.graphy}
                disabled={flash != null}
                onPick={() => pick(choice.graphy)}
                onPreview={() => {
                  audio.unlock();
                  void audio.say(choice.sound);
                }}
                previewLabel={`Écouter ${choice.graphy}`}
                ariaLabel={`Son ${choice.graphy}`}
              >
                {choice.graphy}
              </Tile>
            ))}
          </div>
        </div>
      )}
    </GameFrame>
  );
}
