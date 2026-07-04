import { voKey } from "./utterances";

/* -------------------------------------------------------------------------- */
/* Baked voice-over clips. Populated by `pnpm vo:build` (Gemini TTS → .wav);     */
/* empty until then, so the app just falls back to speechSynthesis. Vite inlines */
/* each clip as a hashed asset URL at build time — fully offline, no network.    */
/* -------------------------------------------------------------------------- */

const modules = import.meta.glob("./clips/*.{m4a,mp3,wav}", {
  eager: true,
  query: "?url",
  import: "default",
}) as Record<string, string>;

// Prefer the compressed formats when a key has more than one on disk.
const RANK: Record<string, number> = { m4a: 3, mp3: 2, wav: 1 };
const byKey = new Map<string, string>();
const bestRank = new Map<string, number>();
for (const [path, url] of Object.entries(modules)) {
  const m = /\/([^/]+)\.(m4a|mp3|wav)$/.exec(path);
  if (!m) continue;
  const [, key, ext] = m;
  const rank = RANK[ext] ?? 0;
  if (rank > (bestRank.get(key) ?? 0)) {
    bestRank.set(key, rank);
    byKey.set(key, url);
  }
}

/** URL of the baked clip for this exact utterance, or undefined if not baked. */
export function clipUrl(text: string): string | undefined {
  return byKey.get(voKey(text));
}

/** Whether any clips are baked at all (lets the UI know VO is device-consistent). */
export const hasBakedVoice = byKey.size > 0;
