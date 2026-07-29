import { useCallback, useEffect, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { Finished } from "../components/Finished";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import { useProfile } from "../hooks/useProfile";
import {
  GRID_PROMPT,
  buildSyllableGridSession,
  gridPrompt,
  gridSuccess,
  type GridRound,
} from "../levels";
import { MISS_COOLDOWN_MS } from "../rewards";
import type { ExerciseId, Mood, SyllableGridMode, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#FF8A65", ink: "#4A2317" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#A5D6A7", ink: "#123B18" },
];

/**
 * The combinatoire drill: ONE engine over the syllable grid, two ways round.
 *
 *   hear   — the syllable is spoken, the tiles WRITE it (VA VE VI VO VU VÉ).
 *   vowel  — the syllable is spoken, its consonant is already written, and the
 *            tiles are the vowels: the child places the one that finishes it.
 *
 * Same one-prompt/one-tile loop as first-letter and find-sound, so a child who
 * can play those needs no new pattern — and every tile keeps its own "Écouter",
 * which is the point here: hearing VA next to VI is how the contrast is learnt.
 */
export function SyllableGridExercise({
  exercise,
  mode,
  level,
  onBack,
  onNext,
}: {
  exercise: ExerciseId;
  mode: SyllableGridMode;
  level: number;
  onBack: () => void;
  onNext: () => void;
}) {
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();
  const [session] = useState<GridRound[]>(() => buildSyllableGridSession(level, mode));
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
    // Settle the new round before announcing (matches FirstLetter/FindSound).
    const t = window.setTimeout(() => void audio.say(gridPrompt(round.target)), 350);
    return () => window.clearTimeout(t);
  }, [idx, done, round, audio]);

  const pick = useCallback(
    (text: string): Verdict => {
      if (locked.current) return "reject";
      if (performance.now() < coolUntil.current) return "reject"; // silent while the shake plays
      audio.unlock();
      audio.pop();
      if (text !== round.target.text) {
        audio.nudge();
        coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
        missRound(idx); // the star greys NOW, same beat as the shake
        return "reject";
      }
      locked.current = true;
      setFlash(text);
      setMood("happy");
      audio.success();
      fire();
      // Advance only after the success line has played in full. `ok` is false if
      // it was cut short (child left, watchdog) — then we don't advance.
      const next = idx + 1;
      void (async () => {
        const ok = await audio.say(gridSuccess(round.target), { rate: 0.98 });
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
        <Finished onMenu={onBack} onNext={onNext} stars={stars} earned={earned} title="Tu as tout lu !" />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">{GRID_PROMPT[mode]}</p>
          <Mascot config={profile.config} mood={mood} />

          {/* vowel mode: the syllable half-written — the consonant, then the gap
              the child fills. The picked vowel drops INTO the gap on success, so
              the whole syllable is read once, complete, before moving on. */}
          {mode === "vowel" && (
            <div
              className="mt-2 flex items-center gap-2 font-black text-[#5A3A1E]"
              style={{ fontSize: "clamp(38px,11vw,64px)" }}
              aria-label={`Syllabe à compléter : ${round.target.consonant}`}
            >
              <span>{round.target.consonant}</span>
              <span
                aria-hidden
                className="flex items-center justify-center"
                style={{
                  minWidth: "0.9em",
                  height: "1.1em",
                  padding: "0 0.1em",
                  borderRadius: 16,
                  background: flash ? "#FFFFFF" : "transparent",
                  border: flash ? "none" : "4px dashed #E4A15E",
                  boxShadow: flash ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
                }}
              >
                {flash ? round.target.vowel : ""}
              </span>
            </div>
          )}

          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              void audio.say(gridPrompt(round.target));
            }}
            aria-label="Réécouter la syllabe"
            className="mb-6 mt-3 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>

          <div className="flex flex-wrap items-center justify-center gap-3">
            {round.choices.map((choice, i) => (
              <Tile
                key={choice.text}
                bg={TILE_COLORS[i % TILE_COLORS.length].bg}
                ink={TILE_COLORS[i % TILE_COLORS.length].ink}
                highlight={flash === choice.text}
                disabled={flash != null}
                onPick={() => pick(choice.text)}
                onPreview={() => {
                  audio.unlock();
                  void audio.say(choice.sound);
                }}
                previewLabel={`Écouter ${choice.text}`}
                size="clamp(62px,17vw,96px)"
                fontSize="clamp(26px,7vw,46px)"
                ariaLabel={`Syllabe ${choice.text}`}
              >
                {mode === "vowel" ? choice.vowel : choice.text}
              </Tile>
            ))}
          </div>
        </div>
      )}
    </GameFrame>
  );
}
