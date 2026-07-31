import { useCallback, useEffect, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { Finished } from "../components/Finished";
import { Mascot } from "../mascot/Mascot";
import { Tile } from "../components/Tile";
import { useAudio } from "../hooks/useAudio";
import { useConfetti } from "../hooks/useConfetti";
import { useProfile } from "../hooks/useProfile";
import { buildTwinSession, twinPrompt, twinSuccess, type TwinRound, type TwinTile } from "../levels";
import { MISS_COOLDOWN_MS } from "../rewards";
import type { ExerciseId, Mood, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#BA9EE8", ink: "#2C1846" },
  { bg: "#FF8A65", ink: "#4A2317" },
];

/**
 * Hear one sound (« Trouve tous les ko ! »), find EVERY tile that writes it —
 * CO and KO stay, SO goes home. The multi-select capstone of the sound ladder:
 * each correct tap locks its tile into the collection strip and speaks the
 * graphy's own anchor word (« Oui ! coq. »), so the feedback itself teaches
 * which word owns which spelling. Wrong taps follow the house rules: nudge +
 * shake at pointerdown, the round's star greys, nothing is lost.
 */
export function SoundTwinsExercise({
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
  const [session] = useState<TwinRound[]>(() => buildTwinSession(level));
  const [idx, setIdx] = useState(0);
  // Ids of this round's found tiles, in tap order (fills the collection strip).
  const [found, setFound] = useState<number[]>([]);
  const [mood, setMood] = useState<Mood>("idle");
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
  const targets = round ? round.tiles.filter((t) => t.correct) : [];

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
    const t = window.setTimeout(() => void audio.say(twinPrompt(round.family)), 350);
    return () => window.clearTimeout(t);
  }, [idx, done, round, audio]);

  const pick = useCallback(
    (tile: TwinTile): Verdict => {
      if (locked.current) return "reject";
      if (performance.now() < coolUntil.current) return "reject"; // silent while the shake plays
      audio.unlock();
      audio.pop();
      if (!tile.correct) {
        audio.nudge();
        coolUntil.current = performance.now() + MISS_COOLDOWN_MS;
        missRound(idx); // the star greys NOW, same beat as the shake
        return "reject";
      }
      const nextFound = [...found, tile.id];
      setFound(nextFound);
      setMood("happy");
      const complete = targets.every((t) => nextFound.includes(t.id));
      if (!complete) {
        // A twin found, more to go: its anchor word IS the feedback, then the
        // round keeps running — the next tap can land while the line plays.
        void audio.say(twinSuccess(tile), { rate: 0.98 });
        return "accept";
      }
      locked.current = true;
      audio.success();
      fire();
      // Advance only after the last success line has played in full. `ok` is
      // false if it was cut short (child left, watchdog) — then we don't advance.
      const next = idx + 1;
      void (async () => {
        const ok = await audio.say(twinSuccess(tile), { rate: 0.98 });
        if (!ok || !mountedRef.current) return;
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
          setFound([]);
          setIdx(next);
        }
      })();
      return "accept";
    },
    [audio, award, exercise, fire, found, idx, level, missRound, session.length, targets]
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
            Un son peut s'écrire de plusieurs façons — trouve-les toutes !
          </p>
          <Mascot config={profile.config} mood={mood} />
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              void audio.say(twinPrompt(round.family));
            }}
            aria-label="Réécouter le son"
            className="mb-4 mt-2 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>

          {/* The collection strip: one slot per twin to find. A found tile locks
              in with its graphy + anchor picture, so the child sees the family
              assemble — and how many are still hiding. */}
          <div className="mb-6 flex flex-wrap items-center justify-center gap-2">
            {targets.map((_, i) => {
              const tile = round.tiles.find((t) => t.id === found[i]);
              return (
                <div
                  key={i}
                  className="flex items-center justify-center gap-1 font-black"
                  style={{
                    minWidth: "clamp(56px,16vw,84px)",
                    height: "clamp(48px,13vw,64px)",
                    padding: "0 10px",
                    fontSize: "clamp(20px,5.5vw,32px)",
                    borderRadius: 18,
                    color: "#5A3A1E",
                    background: tile ? "#FFFFFF" : "transparent",
                    border: tile ? "none" : "3px dashed #E4A15E",
                    boxShadow: tile ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
                  }}
                >
                  {tile && (
                    <>
                      {tile.text}
                      <span aria-hidden style={{ fontSize: "0.8em" }}>
                        {tile.emoji}
                      </span>
                    </>
                  )}
                </div>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center justify-center gap-3">
            {round.tiles.map((tile, i) => (
              <Tile
                key={tile.id}
                bg={TILE_COLORS[i % TILE_COLORS.length].bg}
                ink={TILE_COLORS[i % TILE_COLORS.length].ink}
                highlight={found.includes(tile.id)}
                // A found tile locks; a COMPLETE round locks the rest too, so
                // nothing shakes while the celebration line plays out.
                disabled={found.includes(tile.id) || targets.every((t) => found.includes(t.id))}
                onPick={() => pick(tile)}
                onPreview={() => {
                  if (locked.current) return; // don't cut the success line mid-celebration
                  audio.unlock();
                  void audio.say(tile.sound);
                }}
                previewLabel={`Écouter ${tile.text}`}
                size="clamp(60px,17vw,92px)"
                fontSize="clamp(24px,6.5vw,44px)"
                ariaLabel={`Syllabe ${tile.text}`}
              >
                {tile.text}
              </Tile>
            ))}
          </div>
        </div>
      )}
    </GameFrame>
  );
}
