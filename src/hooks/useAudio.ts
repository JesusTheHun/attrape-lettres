import { useCallback, useEffect, useMemo, useRef } from "react";
import { clipUrl } from "../vo/clips";

export interface AudioApi {
  unlock: () => void;
  pop: () => void;
  success: () => void;
  nudge: () => void;
  oops: () => void;
  /**
   * Speak one line and resolve when it is DONE. `true` = it played to natural
   * completion; `false` = it was superseded by a later say()/stop(), errored, or
   * the watchdog tripped. Never rejects, never hangs. Callers gate their next
   * step on the boolean, so every line plays fully and exactly once before the
   * game moves on — no timers racing the audio.
   */
  say: (text: string, opts?: { rate?: number; pitch?: number }) => Promise<boolean>;
  stop: () => void;
}

// Watchdogs are the only thing that resolves say() if the engine never fires
// 'ended'/'onend' (backgrounded tab, blocked autoplay, decode error with no
// error event). They're derived from the real clip length — not a magic
// constant — so they only ever fire on a genuine stall, never mid-line.
const WATCHDOG_MARGIN_MS = 800; // grace past a clip's known duration
const PROVISIONAL_WATCHDOG_MS = 8000; // held only until a clip reports its duration
const TTS_MIN_MS = 1200; // floor for the estimate-based TTS watchdog
const TTS_MS_PER_CHAR = 90; // rough French speech pace for the TTS estimate
const TTS_HEARTBEAT_MS = 5000; // kick Chrome's speechSynthesis so it doesn't stall

/**
 * Pick the least-robotic French voice available, by score. Enhanced/premium/
 * neural variants (e.g. "Aurélie (Enhanced)", "Google français", Siri) sound
 * natural; "compact"/eSpeak are the tinny ones we push to the bottom.
 */
function voiceScore(v: SpeechSynthesisVoice): number {
  let s = 0;
  if (/enhanced|premium|neural|siri/i.test(v.name)) s += 5;
  if (/google|am[ée]lie|thomas|audrey|aur[ée]lie|denise|henri|hortense|marie|c[ée]line/i.test(v.name)) s += 3;
  if (v.localService === false) s += 2; // network voices are usually natural
  if (/compact|espeak/i.test(v.name)) s -= 5; // robotic
  if (/^fr-FR/i.test(v.lang)) s += 1; // prefer France French for a 6yo in fr
  return s;
}

function pickBestFr(voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | null {
  const fr = voices.filter((v) => /fr($|[-_])/i.test(v.lang));
  if (!fr.length) return null;
  return fr.slice().sort((a, b) => voiceScore(b) - voiceScore(a))[0];
}

export function useAudio(): AudioApi {
  const ctxRef = useRef<AudioContext | null>(null);
  const voiceRef = useRef<SpeechSynthesisVoice | null>(null);
  const clipRef = useRef<HTMLAudioElement | null>(null);
  const unlockedRef = useRef(false);

  // The single-flight voice channel. `ticketRef` is bumped on every say()/stop();
  // an engine callback only settles the promise it belongs to (its ticket must
  // still be current). `pendingRef` is the live resolver, so a superseding call
  // can force-settle it `false` — that's what guarantees no promise ever hangs.
  const ticketRef = useRef(0);
  const pendingRef = useRef<((ok: boolean) => void) | null>(null);
  const watchdogRef = useRef<number | null>(null);
  const heartbeatRef = useRef<number | null>(null);
  const fadeRef = useRef<number | null>(null);

  useEffect(() => {
    const resolve = () => (voiceRef.current = pickBestFr(speechSynthesis.getVoices()));
    resolve();
    speechSynthesis.addEventListener?.("voiceschanged", resolve);
    return () => speechSynthesis.removeEventListener?.("voiceschanged", resolve);
  }, []);

  const clearTimers = useCallback(() => {
    if (watchdogRef.current !== null) {
      window.clearTimeout(watchdogRef.current);
      watchdogRef.current = null;
    }
    if (heartbeatRef.current !== null) {
      window.clearInterval(heartbeatRef.current);
      heartbeatRef.current = null;
    }
  }, []);

  // Settle whatever line is in flight as NOT-completed, and quiesce the engine.
  // `hard` cuts the clip instantly (a new line is starting); soft leaves it for
  // stop()'s fade. Resolving the pending promise BEFORE cancelling TTS means a
  // cancel-triggered onend can't sneak in a spurious `true`.
  const interruptCurrent = useCallback(
    (hard: boolean) => {
      clearTimers();
      const resolve = pendingRef.current;
      pendingRef.current = null;
      const el = clipRef.current;
      if (el) {
        el.onended = null;
        el.onerror = null;
        el.onloadedmetadata = null;
        if (hard && !el.paused) el.pause();
      }
      try {
        speechSynthesis.cancel();
      } catch {
        /* speech unavailable */
      }
      resolve?.(false);
    },
    [clearTimers]
  );

  const unlock = useCallback(() => {
    if (!ctxRef.current) {
      const Ctor = window.AudioContext ?? (window as any).webkitAudioContext;
      if (Ctor) ctxRef.current = new Ctor();
    }
    void ctxRef.current?.resume();
    if (unlockedRef.current) return; // warm the speech engine once, not on every tap
    unlockedRef.current = true;
    try {
      const warm = new SpeechSynthesisUtterance(" ");
      warm.volume = 0;
      speechSynthesis.speak(warm);
    } catch {
      /* speech unavailable */
    }
  }, []);

  const blip = useCallback(
    (freq: number, dur: number, type: OscillatorType = "sine", gain = 0.16, at = 0) => {
      const ctx = ctxRef.current;
      if (!ctx) return;
      const t0 = ctx.currentTime + at;
      const osc = ctx.createOscillator();
      const g = ctx.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(freq, t0);
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(gain, t0 + 0.008);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      osc.connect(g).connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + dur + 0.02);
    },
    []
  );

  const pop = useCallback(() => blip(660, 0.09, "triangle", 0.16), [blip]);
  const success = useCallback(
    () => [523.25, 659.25, 783.99, 1046.5].forEach((f, i) => blip(f, 0.16, "sine", 0.16, i * 0.075)),
    [blip]
  );
  const nudge = useCallback(() => blip(196, 0.14, "sine", 0.1), [blip]); // soft, non-punishing
  // Two-note falling "wah-wah" for a completed-but-wrong row. Gentle (sine, low
  // gain): it marks the miss without punishing. Pairs with the "Oh non" VO.
  const oops = useCallback(() => {
    blip(392, 0.18, "sine", 0.13, 0);
    blip(311.13, 0.28, "sine", 0.13, 0.16);
  }, [blip]);

  // Prefer the baked Gemini clip (device-consistent, natural); fall back to the
  // OS voice for anything not yet generated. One reused <audio> + one live
  // utterance: a new say() supersedes whatever was in flight (latest intent
  // wins), so voice never overlaps voice.
  const say = useCallback(
    (text: string, { rate = 0.94, pitch = 1.1 }: { rate?: number; pitch?: number } = {}): Promise<boolean> => {
      interruptCurrent(true);
      const ticket = ++ticketRef.current;
      return new Promise<boolean>((resolve) => {
        pendingRef.current = resolve;

        const settle = (ok: boolean) => {
          if (ticket !== ticketRef.current) return; // a later line already took over
          clearTimers();
          pendingRef.current = null;
          resolve(ok);
        };
        const arm = (ms: number) => {
          if (watchdogRef.current !== null) window.clearTimeout(watchdogRef.current);
          watchdogRef.current = window.setTimeout(() => settle(false), ms);
        };

        const speakTts = () => {
          try {
            speechSynthesis.cancel();
            const u = new SpeechSynthesisUtterance(text);
            u.rate = rate;
            u.pitch = pitch;
            u.lang = "fr-FR";
            const v = voiceRef.current ?? pickBestFr(speechSynthesis.getVoices());
            if (v) u.voice = v;
            u.onend = () => settle(true);
            u.onerror = () => settle(false);
            arm(Math.max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate + WATCHDOG_MARGIN_MS);
            // Chrome silently parks a long utterance after ~15s; a pause/resume
            // nudge keeps it going. Cleared with the rest in settle()/interrupt.
            heartbeatRef.current = window.setInterval(() => {
              if (ticket !== ticketRef.current) return;
              if (speechSynthesis.speaking) {
                speechSynthesis.pause();
                speechSynthesis.resume();
              }
            }, TTS_HEARTBEAT_MS);
            speechSynthesis.speak(u);
          } catch {
            settle(false); // no speech engine — don't strand the caller
          }
        };

        const url = clipUrl(text);
        if (url) {
          try {
            const el = clipRef.current ?? (clipRef.current = new Audio());
            if (fadeRef.current !== null) {
              window.clearInterval(fadeRef.current); // a leave-fade was mid-ramp; cancel it
              fadeRef.current = null;
            }
            el.volume = 1;
            el.onended = () => settle(true);
            el.onerror = () => settle(false);
            const armFromDuration = () => {
              if (ticket !== ticketRef.current) return;
              if (Number.isFinite(el.duration) && el.duration > 0)
                arm(el.duration * 1000 + WATCHDOG_MARGIN_MS);
            };
            el.onloadedmetadata = armFromDuration;
            el.src = url;
            el.currentTime = 0;
            arm(PROVISIONAL_WATCHDOG_MS); // until the real duration lands
            armFromDuration(); // already-cached clips report duration immediately
            void el.play().catch(() => {
              // play() rejects when a newer say() interrupted us (AbortError) or
              // autoplay is blocked. Only fall back to TTS if we're still current.
              if (ticket === ticketRef.current) speakTts();
            });
            return;
          } catch {
            /* fall through to on-device speech */
          }
        }
        // No baked clip: stop any in-flight clip so it can't overlap the TTS line.
        clipRef.current?.pause();
        speakTts();
      });
    },
    [clearTimers, interruptCurrent]
  );

  // Leaving an exercise: settle any pending line `false`, then ramp the current
  // clip to silence over 200ms and cut. TTS has no mid-utterance volume, so it's
  // simply cancelled (by interruptCurrent).
  const stop = useCallback(() => {
    interruptCurrent(false);
    const el = clipRef.current;
    if (!el || el.paused) return;
    if (fadeRef.current !== null) window.clearInterval(fadeRef.current);
    const steps = 10;
    const start = el.volume;
    let i = 0;
    fadeRef.current = window.setInterval(() => {
      el.volume = Math.max(0, start * (1 - ++i / steps));
      if (i >= steps) {
        if (fadeRef.current !== null) window.clearInterval(fadeRef.current);
        fadeRef.current = null;
        el.pause();
        el.currentTime = 0;
        el.volume = 1; // restore for the next exercise's first line
      }
    }, 20); // 10 × 20ms = 200ms
  }, [interruptCurrent]);

  // One AudioContext per exercise mount; close it on unmount so a long session
  // (App remounts the exercise per level) can't leak contexts until the browser
  // caps them and the chimes go silent.
  useEffect(
    () => () => {
      clearTimers();
      try {
        void ctxRef.current?.close();
      } catch {
        /* already closed */
      }
      ctxRef.current = null;
    },
    [clearTimers]
  );

  // Stable identity — every fn is useCallback-memoised, so this object never
  // changes. Critical: FirstLetterExercise's announce effect depends on `say`
  // via `prompt`; a fresh object each render would re-fire it on every tap.
  return useMemo(
    () => ({ unlock, pop, success, nudge, oops, say, stop }),
    [unlock, pop, success, nudge, oops, say, stop]
  );
}
