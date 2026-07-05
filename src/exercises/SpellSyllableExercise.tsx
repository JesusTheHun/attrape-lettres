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
  buildSpellSyllableRound,
  buildSpellSyllableSession,
  spellSyllableLevel,
  type SpellLetterTile,
  type SpellSyllableRound,
} from "../levels";
import { SCRIPT_FONT, faceLabel } from "../letterForms";
import { useProfile } from "../hooks/useProfile";
import type { ExerciseId, LetterFace, Mood, SpellSyllableMode, Verdict } from "../types";

const TRAY_COLORS = [
  { bg: "#4FC3F7", ink: "#062E3D" },
  { bg: "#AED581", ink: "#213606" },
  { bg: "#FFD54F", ink: "#4A3B00" },
  { bg: "#BA9EE8", ink: "#2C1846" },
  { bg: "#FF8A65", ink: "#4A2317" },
];

interface Props {
  exercise: ExerciseId;
  mode: SpellSyllableMode;
  level: number;
  /** "écritures mêlées" twin: mixed case + script; the child matches the writing. */
  mixed?: boolean;
  onBack: () => void;
  onNext: () => void;
}

const HEADLINE: Record<SpellSyllableMode, string> = {
  "letters-exact": "Complète le mot avec les lettres",
  "letters-extra": "Complète le mot — attention aux intrus",
  "letters-two": "Complète les deux syllabes",
};

/** Mixed twins swap the headline: matching the writing IS the task now. */
const MIXED_HEADLINE: Record<SpellSyllableMode, string> = {
  "letters-exact": "Trouve la bonne écriture",
  "letters-extra": "La bonne lettre… et la bonne écriture",
  "letters-two": "Deux syllabes — la bonne écriture",
};

/** Two faces are the same tile iff they render identically (glyph encodes case). */
const sameFace = (a: LetterFace, b: LetterFace) => a.glyph === b.glyph && a.script === b.script;

/**
 * Part of the word is already written; one (or two) syllable is blanked into
 * per-letter slots the child fills by tapping letters in the right order. Same
 * forgiving loop as SpellSoundExercise (feedback on pointerdown, WAAPI shake,
 * canvas confetti, whole-row judgement), but the prompt is the printed word and
 * the target is its missing letters. Mode only changes the tray / gap count.
 */
export function SpellSyllableExercise({ exercise, mode, level, mixed = false, onBack, onNext }: Props) {
  const cfg = useMemo(() => spellSyllableLevel(level), [level]);
  const audio = useAudio();
  const { canvasRef, fire } = useConfetti();
  const { award, profile } = useProfile();

  const [session] = useState(() => buildSpellSyllableSession(level));
  const [idx, setIdx] = useState(0);
  const [round, setRound] = useState<SpellSyllableRound>(() =>
    buildSpellSyllableRound(session[0], mode, cfg.distractors, mixed)
  );
  // Each slot holds the FACE of the tile dropped in it (or null), so a mixed
  // round can judge the writing, not only the letter, and re-render its glyph.
  const [slots, setSlots] = useState<(LetterFace | null)[]>(() => round.answerFaces.map(() => null));
  // Which tray tile fills each slot, so a filled slot can be tapped to send its
  // tile back to the tray.
  const [slotTile, setSlotTile] = useState<(number | null)[]>(() => round.answer.map(() => null));
  const [used, setUsed] = useState<Set<number>>(new Set());
  const [mood, setMood] = useState<Mood>("idle");
  const [done, setDone] = useState(false);
  const [earned, setEarned] = useState(0);
  const locked = useRef(false);
  const mountedRef = useRef(true);

  const loadRound = useCallback(
    (word: (typeof session)[number]) => {
      const r = buildSpellSyllableRound(word, mode, cfg.distractors, mixed);
      setRound(r);
      setSlots(r.answerFaces.map(() => null));
      setSlotTile(r.answerFaces.map(() => null));
      setUsed(new Set());
      locked.current = false;
      void audio.say(word.word);
    },
    [audio, cfg.distractors, mode, mixed]
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

  // Drop a tapped letter into the next open slot. No per-tap judgement — the
  // child spells the whole gap, and only a *complete* row is checked (below).
  // A wrong letter can be tapped back out (removeAt) before the row fills.
  const pick = useCallback(
    (t: SpellLetterTile): Verdict => {
      if (locked.current) return "reject";
      audio.unlock();
      audio.pop();

      const nextEmpty = slots.findIndex((s) => s === null);
      if (nextEmpty < 0) return "reject";

      const face: LetterFace = { base: t.letter, glyph: t.glyph, script: t.script };
      const filled = slots.slice();
      filled[nextEmpty] = face;
      setSlots(filled);
      setSlotTile((prev) => {
        const n = prev.slice();
        n[nextEmpty] = t.id;
        return n;
      });
      setUsed((prev) => new Set(prev).add(t.id));

      if (!filled.every((s) => s !== null)) return "accept";

      // Row complete — judge the whole spelling at once. In mixed rounds the
      // case + script must match too; sameFace folds all three into one check.
      if (filled.every((s, i) => s != null && sameFace(s, round.answerFaces[i]))) {
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
        // Wrong spelling: "Oh non", then — once it has finished speaking — wipe
        // the letters out to retry. Waiting on the line means the reset (and
        // re-enabling taps) can't cut "Oh non" off.
        locked.current = true;
        audio.oops();
        void (async () => {
          await audio.say("Oh non ! On recommence.");
          if (!mountedRef.current) return;
          setSlots(round.answerFaces.map(() => null));
          setSlotTile(round.answerFaces.map(() => null));
          setUsed(new Set());
          locked.current = false;
        })();
      }
      return "accept";
    },
    [audio, award, exercise, fire, idx, level, loadRound, round, session, slots]
  );

  // Tap a filled slot to send its letter back to the tray — undo a misplacement
  // before the row is complete.
  const removeAt = useCallback(
    (slotIndex: number) => {
      if (locked.current) return;
      const tileId = slotTile[slotIndex];
      if (tileId == null) return;
      audio.pop();
      setSlots((prev) => {
        const n = prev.slice();
        n[slotIndex] = null;
        return n;
      });
      setSlotTile((prev) => {
        const n = prev.slice();
        n[slotIndex] = null;
        return n;
      });
      setUsed((prev) => {
        const n = new Set(prev);
        n.delete(tileId);
        return n;
      });
    },
    [audio, slotTile]
  );

  const word = round.word;

  return (
    <GameFrame onBack={onBack} done={idx} total={session.length} canvasRef={canvasRef}>
      {done ? (
        <Finished onMenu={onBack} onNext={onNext} count={session.length} earned={earned} />
      ) : (
        <div className="relative z-[41] flex w-full flex-1 flex-col items-center px-4 pb-8 pt-2">
          <p className="m-0 mb-1 text-base font-bold text-[#7A5A3A]">
            {(mixed ? MIXED_HEADLINE : HEADLINE)[mode]}
          </p>
          <Mascot config={profile.config} mood={mood} />
          <div style={{ margin: "2px 0" }}>
            <WordIcon emoji={word.emoji} img={word.img} size="clamp(48px,15vw,88px)" />
          </div>
          <button
            onPointerDown={() => {
              if (locked.current) return; // don't cut the success line mid-celebration
              void audio.say(word.word);
            }}
            aria-label="Réécouter le mot"
            className="mb-4 rounded-full bg-white/70 px-5 py-2 text-lg font-bold text-[#5A3A1E] shadow [touch-action:none]"
          >
            🔊 Écouter
          </button>

          {/* The word, letter by letter: written letters + dashed slots for the
              gap. A filled slot is a button that pops its letter back to the tray
              so a misplacement can be fixed mid-row. A small gap before each new
              syllable keeps the word's shape readable. */}
          <div
            aria-label="Mot à compléter"
            className="mb-6 flex flex-wrap items-center justify-center gap-1.5"
          >
            {round.cells.map((c, i) => {
              const style = {
                minWidth: "clamp(40px,11vw,60px)",
                height: "clamp(52px,14vw,72px)",
                padding: "0 6px",
                fontSize: "clamp(24px,7vw,42px)",
                borderRadius: 16,
                marginLeft: c.syllableStart && i > 0 ? "clamp(8px,2.5vw,16px)" : 0,
              } as const;
              if (!c.fill) {
                // Already written — a solid, non-interactive letter in the round's writing.
                return (
                  <div
                    key={i}
                    aria-hidden
                    className="flex items-center justify-center font-black text-[#5A3A1E]"
                    style={{ ...style, background: "#FFF3E0", fontFamily: SCRIPT_FONT[c.script] }}
                  >
                    {c.glyph}
                  </div>
                );
              }
              const s = slots[c.slotIndex];
              const slotStyle = {
                ...style,
                color: "#5A3A1E",
                background: s ? "#FFFFFF" : "transparent",
                border: s ? "none" : "3px dashed #E4A15E",
                boxShadow: s ? "0 6px 14px rgba(0,0,0,0.12)" : "none",
                fontFamily: s ? SCRIPT_FONT[s.script] : undefined,
              } as const;
              return s != null ? (
                <button
                  key={i}
                  onPointerDown={() => removeAt(c.slotIndex)}
                  aria-label={`Retirer ${s.base}`}
                  className="flex items-center justify-center border-none font-black [touch-action:none]"
                  style={{ ...slotStyle, cursor: "pointer" }}
                >
                  {s.glyph}
                </button>
              ) : (
                <div
                  key={i}
                  aria-hidden
                  className="flex items-center justify-center font-black"
                  style={slotStyle}
                >
                  {""}
                </div>
              );
            })}
          </div>

          {/* Letter tray */}
          <div className="flex flex-wrap items-center justify-center gap-3">
            {round.tray.map((t, i) => (
              <Tile
                key={t.id}
                bg={TRAY_COLORS[i % TRAY_COLORS.length].bg}
                ink={TRAY_COLORS[i % TRAY_COLORS.length].ink}
                disabled={used.has(t.id)}
                onPick={() => pick(t)}
                onPreview={() => {
                  if (locked.current) return; // don't cut the success line mid-celebration
                  audio.unlock();
                  void audio.say(t.letter);
                }}
                previewLabel={`Écouter ${t.letter}`}
                size="clamp(60px,17vw,92px)"
                fontSize="clamp(26px,7vw,48px)"
                ariaLabel={faceLabel({ base: t.letter, glyph: t.glyph, script: t.script })}
              >
                <span style={{ fontFamily: SCRIPT_FONT[t.script] }}>{t.glyph}</span>
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
