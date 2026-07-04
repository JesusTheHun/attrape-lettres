import { access, mkdir, unlink, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

/* -------------------------------------------------------------------------- */
/* Bake the finite, authored VO vocabulary to audio with Gemini TTS.            */
/*                                                                            */
/*   GEMINI_API_KEY=xxx pnpm run vo:build                                       */
/*                                                                            */
/* Plain .mjs so it runs on ANY Node (no native-TS reliance). The authored      */
/* vocabulary lives in TypeScript (src/vo/utterances.ts → src/content.ts); we   */
/* load it through Vite's ssrLoadModule so there's a single source of truth and */
/* zero extra deps. Idempotent: existing clips are skipped, so re-run after      */
/* adding words to fill only the new ones.                                     */
/*                                                                            */
/* Gemini returns raw PCM (audio/L16, mono, 24 kHz). We wrap it as WAV, then     */
/* compress to AAC/.m4a (playable on iOS + Android WebViews, ~7× smaller than    */
/* WAV) via ffmpeg — or macOS `afconvert` if ffmpeg is absent. Output →          */
/* src/vo/clips/<voKey>.m4a, which Vite bundles as offline assets. If neither    */
/* encoder exists we keep the .wav and warn (the runtime globs both).           */
/*                                                                            */
/* Env: GEMINI_TTS_MODEL, GEMINI_TTS_VOICE, GEMINI_TTS_STYLE, GEMINI_TTS_DELAY_MS,*/
/*      GEMINI_TTS_RETRIES, VO_FORMAT (m4a|mp3), VO_BITRATE (e.g. 48k), FFMPEG.   */
/* -------------------------------------------------------------------------- */

const API_KEY = process.env.GEMINI_API_KEY ?? process.env.GOOGLE_API_KEY;
const MODEL = process.env.GEMINI_TTS_MODEL ?? "gemini-3.1-flash-tts-preview";
const VOICE = process.env.GEMINI_TTS_VOICE ?? "Leda"; // warm, youthful; try Aoede/Callirrhoe
const STYLE =
  process.env.GEMINI_TTS_STYLE ??
  "Dis d'une voix douce, chaleureuse et enjouée, comme pour un enfant de six ans :";
const DELAY_MS = Number(process.env.GEMINI_TTS_DELAY_MS ?? 1500); // between API calls
const MAX_RETRIES = Number(process.env.GEMINI_TTS_RETRIES ?? 6);
const FFMPEG = process.env.FFMPEG ?? "ffmpeg";
const FORMAT = (process.env.VO_FORMAT ?? "m4a").toLowerCase(); // m4a (aac) | mp3
const BITRATE = process.env.VO_BITRATE ?? "48k"; // speech: 48k mono is plenty

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");
const OUT_DIR = resolve(ROOT, "src/vo/clips");

if (!API_KEY) {
  console.error("Missing GEMINI_API_KEY (or GOOGLE_API_KEY) in the environment.");
  process.exit(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fileExists(p) {
  try {
    await access(p);
    return true;
  } catch {
    return false;
  }
}

/** Run a command to completion; resolve stderr + code, reject only if it can't spawn. */
function run(cmd, args, stdin) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(cmd, args, { stdio: [stdin ? "pipe" : "ignore", "ignore", "pipe"] });
    let err = "";
    child.on("error", reject); // ENOENT etc.
    child.stderr?.on("data", (d) => (err += d));
    child.on("close", (code) => resolvePromise({ code, err }));
    if (stdin) {
      child.stdin.end(stdin);
    }
  });
}

/** Pick an available WAV→AAC encoder once, or null to keep WAVs. */
async function detectEncoder() {
  try {
    await run(FFMPEG, ["-version"]);
    return "ffmpeg";
  } catch {
    /* not installed */
  }
  if (FORMAT === "m4a") {
    try {
      await run("afconvert", ["-h"]); // macOS built-in
      return "afconvert";
    } catch {
      /* not macOS */
    }
  }
  return null;
}

/** Compress a WAV file to the target format. Throws on encoder failure. */
async function encode(wavPath, outPath, encoder) {
  if (encoder === "ffmpeg") {
    const codec = FORMAT === "mp3" ? ["-c:a", "libmp3lame"] : ["-c:a", "aac"];
    const { code, err } = await run(FFMPEG, [
      "-hide_banner", "-loglevel", "error", "-y",
      "-i", wavPath, "-ac", "1", ...codec, "-b:a", BITRATE, outPath,
    ]);
    if (code !== 0) throw new Error(`ffmpeg exit ${code}: ${err.slice(0, 200)}`);
  } else {
    // afconvert (macOS): AAC/.m4a only.
    const bps = String(Math.round(parseFloat(BITRATE) * (/k/i.test(BITRATE) ? 1000 : 1)));
    const { code, err } = await run("afconvert", ["-f", "m4af", "-d", "aac", "-b", bps, wavPath, outPath]);
    if (code !== 0) throw new Error(`afconvert exit ${code}: ${err.slice(0, 200)}`);
  }
}

async function loadVocab() {
  const vite = await createServer({
    root: ROOT,
    appType: "custom",
    server: { middlewareMode: true },
    logLevel: "error",
  });
  try {
    return await vite.ssrLoadModule("/src/vo/utterances.ts");
  } finally {
    await vite.close();
  }
}

/** Wrap signed-16-bit little-endian mono PCM in a minimal WAV container. */
function pcmToWav(pcm, sampleRate = 24000, channels = 1, bits = 16) {
  const blockAlign = (channels * bits) / 8;
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * blockAlign, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(bits, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

function sampleRateFromMime(mime) {
  const m = mime ? /rate=(\d+)/.exec(mime) : null;
  return m ? Number(m[1]) : 24000;
}

function retryDelayMs(res, body, attempt) {
  const header = Number(res.headers.get("retry-after"));
  if (Number.isFinite(header) && header > 0) return header * 1000;
  const m = /"retryDelay":\s*"(\d+(?:\.\d+)?)s"/.exec(body);
  if (m) return Math.ceil(Number(m[1]) * 1000) + 500;
  return Math.min(60000, 2 ** attempt * 2000) + Math.floor(Math.random() * 1000);
}

/** Call Gemini TTS → WAV buffer, with 429/5xx backoff. */
async function synthesize(text) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`;
  const payload = JSON.stringify({
    contents: [{ parts: [{ text: `${STYLE} ${text}` }] }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: VOICE } } },
    },
  });

  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": API_KEY },
      body: payload,
    });
    if (res.ok) {
      const json = await res.json();
      const part = json?.candidates?.[0]?.content?.parts?.find((p) => p?.inlineData?.data);
      const data = part?.inlineData?.data;
      if (!data) throw new Error(`no audio in response: ${JSON.stringify(json).slice(0, 300)}`);
      return pcmToWav(Buffer.from(data, "base64"), sampleRateFromMime(part.inlineData.mimeType));
    }
    const body = await res.text();
    if ((res.status === 429 || res.status >= 500) && attempt < MAX_RETRIES) {
      const wait = retryDelayMs(res, body, attempt);
      console.log(`    … ${res.status}; waiting ${Math.round(wait / 1000)}s (retry ${attempt + 1}/${MAX_RETRIES})`);
      await sleep(wait);
      continue;
    }
    throw new Error(`${res.status} ${res.statusText}: ${body.slice(0, 200)}`);
  }
}

async function main() {
  const { enumerateUtterances, voKey } = await loadVocab();
  await mkdir(OUT_DIR, { recursive: true });

  const encoder = await detectEncoder();
  const ext = encoder ? FORMAT : "wav";
  if (!encoder) {
    console.warn("No ffmpeg/afconvert found — keeping uncompressed .wav. Install ffmpeg for smaller clips.\n");
  }

  const items = enumerateUtterances();
  console.log(`${items.length} utterances → ${OUT_DIR}`);
  console.log(`  model=${MODEL} voice=${VOICE} format=${ext}${encoder ? ` @${BITRATE} (${encoder})` : ""}\n`);

  let made = 0;
  let skipped = 0;
  for (const text of items) {
    const key = voKey(text);
    const out = join(OUT_DIR, `${key}.${ext}`);
    if (await fileExists(out)) {
      skipped++;
      continue;
    }

    const wav = join(OUT_DIR, `${key}.wav`);
    let calledApi = false;
    try {
      if (!(await fileExists(wav))) {
        await writeFile(wav, await synthesize(text)); // API → WAV (reused on re-runs)
        calledApi = true;
      }
      if (encoder) {
        await encode(wav, out, encoder);
        await unlink(wav); // drop the uncompressed original
      }
      made++;
      console.log(`  ✓ ${key}.${ext}  "${text}"`);
    } catch (e) {
      console.error(`  ✗ "${text}" — ${e.message}`);
    }
    if (calledApi && DELAY_MS > 0) await sleep(DELAY_MS);
  }
  console.log(`\nDone. ${made} baked, ${skipped} already present.`);
}

await main();
