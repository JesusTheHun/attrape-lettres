import { useCallback, useEffect, useRef } from "react";

export interface AudioApi {
  unlock: () => void;
  pop: () => void;
  success: () => void;
  nudge: () => void;
  speak: (text: string, opts?: { rate?: number; pitch?: number }) => void;
}

/**
 * Pick the least-robotic French voice available.
 * Priority: known-good named voices → neural/network (localService === false)
 *           → anything not "compact/eSpeak" → whatever is left.
 */
function pickBestFr(voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | null {
  const fr = voices.filter((v) => /fr($|[-_])/i.test(v.lang));
  if (!fr.length) return null;
  const named = fr.find((v) =>
    /google fran|am[ée]lie|thomas|audrey|aur[ée]lie|denise|henri|hortense|marie|c[ée]line/i.test(v.name)
  );
  if (named) return named;
  const neural = fr.find((v) => v.localService === false);
  if (neural) return neural;
  return fr.find((v) => !/compact|espeak/i.test(v.name)) ?? fr[0];
}

export function useAudio(): AudioApi {
  const ctxRef = useRef<AudioContext | null>(null);
  const voiceRef = useRef<SpeechSynthesisVoice | null>(null);

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

  const speak = useCallback((text: string, { rate = 1.02, pitch = 1.4 } = {}) => {
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

  return { unlock, pop, success, nudge, speak };
}
