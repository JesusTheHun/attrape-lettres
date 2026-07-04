import { useCallback, useEffect, useMemo, useRef } from "react";
import { clipUrl } from "../vo/clips";

export interface AudioApi {
  unlock: () => void;
  pop: () => void;
  success: () => void;
  nudge: () => void;
  oops: () => void;
  speak: (text: string, opts?: { rate?: number; pitch?: number }) => void;
  stop: () => void;
}

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
  const seqRef = useRef(0);
  const fadeRef = useRef<number | null>(null);

  useEffect(() => {
    const resolve = () => (voiceRef.current = pickBestFr(speechSynthesis.getVoices()));
    resolve();
    speechSynthesis.addEventListener?.("voiceschanged", resolve);
    return () => speechSynthesis.removeEventListener?.("voiceschanged", resolve);
  }, []);

  const unlock = useCallback(() => {
    if (!ctxRef.current) {
      const Ctor = window.AudioContext ?? (window as any).webkitAudioContext;
      if (Ctor) ctxRef.current = new Ctor();
    }
    void ctxRef.current?.resume();
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

  // On-device fallback. Warmer than the old rate 1.02 / pitch 1.4: a touch
  // slower for a 6yo to follow, pitch near-natural so it reads friendly, not
  // chipmunk-screechy. Only used when no baked clip exists for this utterance.
  const speakTts = useCallback((text: string, rate: number, pitch: number) => {
    try {
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.rate = rate;
      u.pitch = pitch;
      u.lang = "fr-FR";
      const v = voiceRef.current ?? pickBestFr(speechSynthesis.getVoices());
      if (v) u.voice = v;
      speechSynthesis.speak(u);
    } catch {
      /* speech unavailable */
    }
  }, []);

  // Prefer the baked Gemini clip (device-consistent, natural); fall back to the
  // OS voice for anything not yet generated. One reused <audio> so a new line
  // interrupts the previous one, same as speechSynthesis.cancel().
  const speak = useCallback(
    (text: string, { rate = 0.94, pitch = 1.1 } = {}) => {
      const seq = ++seqRef.current;
      const url = clipUrl(text);
      if (url) {
        try {
          speechSynthesis.cancel();
          const el = clipRef.current ?? (clipRef.current = new Audio());
          if (fadeRef.current !== null) {
            clearInterval(fadeRef.current); // a leave-fade was mid-ramp; cancel it
            fadeRef.current = null;
          }
          el.volume = 1;
          el.pause();
          el.src = url;
          el.currentTime = 0;
          void el.play().catch(() => {
            // A newer speak() interrupted this clip (by design), which rejects the
            // pending play() with an AbortError. Only fall back to TTS when THIS call
            // is still the latest — otherwise the fallback would play over the newer
            // clip, doubling the audio at exercise start (two announces race there).
            if (seq === seqRef.current) speakTts(text, rate, pitch);
          });
          return;
        } catch {
          /* fall through to on-device speech */
        }
      }
      // No baked clip: stop any in-flight clip so it can't overlap the TTS line.
      clipRef.current?.pause();
      speakTts(text, rate, pitch);
    },
    [speakTts]
  );

  // Leaving an exercise: ramp the current clip to silence over 200ms, then cut.
  // TTS has no mid-utterance volume, so the fallback is simply cancelled.
  const stop = useCallback(() => {
    try {
      speechSynthesis.cancel();
    } catch {
      /* speech unavailable */
    }
    const el = clipRef.current;
    if (!el || el.paused) return;
    if (fadeRef.current !== null) clearInterval(fadeRef.current);
    const steps = 10;
    const start = el.volume;
    let i = 0;
    fadeRef.current = window.setInterval(() => {
      el.volume = Math.max(0, start * (1 - ++i / steps));
      if (i >= steps) {
        if (fadeRef.current !== null) clearInterval(fadeRef.current);
        fadeRef.current = null;
        el.pause();
        el.currentTime = 0;
        el.volume = 1; // restore for the next exercise's first line
      }
    }, 20); // 10 × 20ms = 200ms
  }, []);

  // Stable identity — every fn is useCallback-memoised, so this object never
  // changes. Critical: FirstLetterExercise's announce effect depends on `speak`
  // via `prompt`; a fresh object each render would re-fire it on every tap.
  return useMemo(
    () => ({ unlock, pop, success, nudge, oops, speak, stop }),
    [unlock, pop, success, nudge, oops, speak, stop]
  );
}
