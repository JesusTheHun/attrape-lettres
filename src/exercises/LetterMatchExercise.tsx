import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { Finished } from "../components/Finished";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import { useProfile } from "../hooks/useProfile";
import {
  buildLetterMatchSession,
  letterMatchPrompt,
  letterMatchSuccess,
} from "../levels";
import { MISS_COOLDOWN_MS } from "../rewards";
import { SCRIPT_FONT, faceLabel } from "../letterForms";
import type { ExerciseId, LetterFace, LetterMatchKind, Mood, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#FF8A65", ink: "#4A2317" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
];

/**
 * Match a letter to its counterpart FORM. `case` pairs majuscule ⇄ minuscule;
 * `script` pairs printed ⇄ cursive at the same case. Same forgiving single-pick
 * loop as FirstLetter (feedback on pointerdown, WAAPI shake, canvas confetti) —
 * only the target is a rendered glyph, not an emoji, and the tiles show the
 * counterpart form. The prompt never names the target, so the child must read
 * the shape; the name is spoken only on success as reinforcement.
 */
export function LetterMatchExercise({
  exercise,
  kind,
  level,
  onBack,
  onNext,
}: {
  exercise: ExerciseId;
  kind: LetterMatchKind;
  level: number;
  onBack: () => void;
  onNext: () => void;
}) {
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();
  const [session] = useState(() => buildLetterMatchSession(kind, level));
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
  // Both directions live in one run; the line to speak is read off this round.
  const line = useMemo(
    () => (round ? letterMatchPrompt(round.prompt, round.choices[0]) : ""),
    [round]
  );

  const prompt = useCallback(() => void audio.say(line), [audio, line]);

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
    // Settle the new round before announcing (matches FirstLetter/SpellSound). The
    // clearTimeout cleanup also collapses StrictMode's double-mount to one announce.
    const t = window.setTimeout(prompt, 350);
    return () => window.clearTimeout(t);
  }, [idx, done, round, prompt]);

  const pick = useCallback(
    (face: LetterFace): Verdict => {
      if (locked.current) return "reject";
      if (performance.now() < coolUntil.current) return "reject"; // silent while the shake plays
      audio.unlock();
      audio.pop();
      if (face.base !== round.prompt.base) {
        audio.nudge();
        coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
        missRound(idx); // the star greys NOW, same beat as the shake
        return "reject";
      }
      locked.current = true;
      setFlash(face.base);
      setMood("happy");
      audio.success();
      fire();
      // Advance only after the success line has played in full. `ok` is false if
      // it was cut short (child left, watchdog) — then we don't advance. The next
      // prompt is announced by the idx-change effect above.
      const next = idx + 1;
      void (async () => {
        const ok = await audio.say(letterMatchSuccess(round.prompt.base), { rate: 0.98 });
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
          <Mascot config={profile.config} mood={mood} />
          <div
            aria-label={faceLabel(round.prompt)}
            className="font-black text-[#5A3A1E]"
            style={{
              fontFamily: SCRIPT_FONT[round.prompt.script],
              fontSize: "clamp(80px,28vw,150px)",
              lineHeight: 1.1,
              margin: "6px 0",
            }}
          >
            {round.prompt.glyph}
          </div>
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              prompt();
            }}
            aria-label="Répéter la consigne"
            className="mb-6 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 {line}
          </button>
          <div className="flex flex-wrap items-center justify-center gap-4">
            {round.choices.map((face, i) => (
              <Tile
                key={face.base}
                bg={TILE_COLORS[i % TILE_COLORS.length].bg}
                ink={TILE_COLORS[i % TILE_COLORS.length].ink}
                highlight={flash === face.base}
                disabled={flash != null}
                onPick={() => pick(face)}
                onPreview={() => {
                  audio.unlock();
                  void audio.say(face.base);
                }}
                previewLabel={`Écouter ${face.base}`}
                ariaLabel={faceLabel(face)}
              >
                <span style={{ fontFamily: SCRIPT_FONT[face.script] }}>{face.glyph}</span>
              </Tile>
            ))}
          </div>
        </div>
      )}
    </GameFrame>
  );
}
