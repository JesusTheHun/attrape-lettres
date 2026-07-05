import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GameFrame } from "../components/GameFrame";
import { EarnBadge } from "../components/EarnBadge";
import { EndButtons } from "../components/EndButtons";
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
import type { ExerciseId, LetterFace, LetterMatchKind, Mood, Verdict } from "../types";

const TILE_COLORS = [
  { bg: "#FF8A65", ink: "#4A2317" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
];

// The two letter faces. `print` is the app's rounded sans; `cursive` leans on the
// OS handwriting font (Snell/Segoe Script/…) — zero-dependency, so the joined
// "attaché" shape a French 6yo learns is close enough without shipping a webfont.
// (A proper école-cursive font file is a nice follow-up; the runtime needs none.)
const ROUNDED = "ui-rounded,'SF Pro Rounded',system-ui,sans-serif";
const SCRIPT_FONT: Record<LetterFace["script"], string> = {
  print: ROUNDED,
  cursive: "'Snell Roundhand','Apple Chancery','Segoe Script','Bradley Hand',cursive",
};

/** Screen-reader label: names the letter, its case, and (cursive only) its form. */
function faceLabel(face: LetterFace): string {
  const caseWord = face.glyph === face.glyph.toUpperCase() ? "majuscule" : "minuscule";
  const scriptWord = face.script === "cursive" ? " attachée" : "";
  return `Lettre ${face.base} ${caseWord}${scriptWord}`;
}

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
  const locked = useRef(false);
  const mountedRef = useRef(true);

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
      audio.unlock();
      audio.pop();
      if (face.base !== round.prompt.base) {
        audio.nudge();
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
          setEarned(award(exercise, level));
          setDone(true);
          void audio.say("Bravo ! Tu as tout trouvé !");
        } else {
          setMood("idle");
          setIdx(next);
        }
      })();
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
