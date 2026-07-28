import { access, mkdir, readFile, unlink, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

/* -------------------------------------------------------------------------- */
/* Bake the finite, authored VO vocabulary to audio with Gemini TTS.            */
/*                                                                            */
/*   GEMINI_API_KEY=xxx pnpm run vo:build                     # everything, batch */
/*   GEMINI_API_KEY=xxx pnpm run vo:build -- --sync            # one call per clip */
/*   GEMINI_API_KEY=xxx pnpm run vo:build -- --group=syllables # only that group  */
/*   pnpm run vo:build -- --list=letters                       # keys, no API call */
/*                                                                            */
/* Default = Gemini Batch Mode: separate quota from the sync API, ~50% cheaper, */
/* async. The job id is stored in src/vo/clips/.vo-batch.json so a stopped run   */
/* resumes polling instead of resubmitting. Use --sync to top up a few clips.   */
/*                                                                            */
/* Groups (--group=): all (default) | phrases (words + sentences) | preview      */
/* (letters + syllables) | letters | syllables. Preview = the "hear the tile      */
/* before you tap it" vocabulary (src/vo/preview.ts); it's a separate group so it */
/* can be baked or deleted on its own. --list=<group> prints each key + filename  */
/* (✓ present / · missing) and exits — the rollback map for `rm`-ing a group.     */
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
/*      GEMINI_TTS_RETRIES, GEMINI_TTS_MAX_WAIT_MS (fail-fast cap), VO_FORMAT     */
/*      (m4a|mp3), VO_BITRATE (e.g. 48k), FFMPEG.                                */
/* -------------------------------------------------------------------------- */

const API_KEY = process.env.GEMINI_API_KEY ?? process.env.GOOGLE_API_KEY;
const MODEL = process.env.GEMINI_TTS_MODEL ?? "gemini-3.1-flash-tts-preview";
const VOICE = process.env.GEMINI_TTS_VOICE ?? "Leda"; // warm, youthful; try Aoede/Callirrhoe
// These are HEADS, not complete instructions: styleFor() appends the IPA clause
// (if any) and the closing « Lis : ». Overriding one via env therefore means
// supplying a head without trailing punctuation.
//
// They are deliberately terse. A five-arm probe pitted this wording against the
// long, elaborate one it replaced (« d'une voix douce, chaleureuse et enjouée »,
// « jamais lettre par lettre », « sans prononcer les barres obliques »…) on the
// same utterances, and a listening pass found the two indistinguishable — while
// the short form was the only arm that baked « o, comme dans jaune » and « au,
// comme dans faucon » cleanly, 4 takes out of 4. The long clauses had been added
// to stop the model reciting its instruction instead of reading; the probe showed
// that blowout is a random roll no wording controls (the SAME payload gave a
// clean « u » and a 30-second « Nid »). The length gate owns that failure now, so
// the prose was pure cost — it had grown to 355 instruction characters against 1
// of content for « u ».
const STYLE =
  process.env.GEMINI_TTS_STYLE ??
  "Lecture pour un enfant de six ans, en français. Voix douce.";
// Per-kind heads for the preview vocabulary (src/vo/preview.ts). A syllable must
// blend into ONE sound, never be spelled out; a lone letter is NAMED. All three
// keep the "enfant de six ans, en français" framing — it isn't spoken, it gives
// the safety classifier language context so short French syllables that collide
// with flagged English words (e.g. "nu", "tu") aren't rejected.
const STYLE_SYLLABLE =
  process.env.GEMINI_TTS_STYLE_SYLLABLE ??
  "Lecture pour un enfant de six ans, en français. Voix douce. Une syllabe, un seul son.";
const STYLE_LETTER =
  process.env.GEMINI_TTS_STYLE_LETTER ??
  "Lecture pour un enfant de six ans, en français. Voix douce. Nomme la lettre.";
const STYLE_BY_KIND = { phrase: STYLE, syllable: STYLE_SYLLABLE, letter: STYLE_LETTER };
const DELAY_MS = Number(process.env.GEMINI_TTS_DELAY_MS ?? 1500); // between API calls
const MAX_RETRIES = Number(process.env.GEMINI_TTS_RETRIES ?? 6);
const MAX_WAIT_MS = Number(process.env.GEMINI_TTS_MAX_WAIT_MS ?? 300000); // fail fast past this (5 min)
const FFMPEG = process.env.FFMPEG ?? "ffmpeg";
const FORMAT = (process.env.VO_FORMAT ?? "m4a").toLowerCase(); // m4a (aac) | mp3
const BITRATE = process.env.VO_BITRATE ?? "48k"; // speech: 48k mono is plenty

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");
const OUT_DIR = resolve(ROOT, "src/vo/clips");

// --list only reads the local catalog + disk, so it doesn't need a key.
const LISTING = process.argv.some((a) => a.startsWith("--list"));
if (!API_KEY && !LISTING) {
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
    // enumerateUtterances + voKey live in utterances.ts; the separate preview
    // catalog (letters/syllables) in preview.ts. The preview module is OPTIONAL
    // by design: deleting src/vo/preview.ts fully rolls the feature back without
    // breaking the phrase bake — we just fall back to no preview vocabulary.
    const utter = await vite.ssrLoadModule("/src/vo/utterances.ts");
    let preview = {};
    try {
      preview = await vite.ssrLoadModule("/src/vo/preview.ts");
    } catch {
      console.warn("No src/vo/preview.ts — baking phrases only (preview vocabulary rolled back).");
    }
    return { ...utter, ...preview };
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

/** Pull the quota name out of a 429 body so the abort message says WHICH limit. */
function quotaHint(body) {
  const m = /"quotaId":\s*"([^"]+)"/.exec(body) || /"quotaMetric":\s*"([^"]+)"/.exec(body);
  return m ? ` (limit: ${m[1]})` : "";
}

/** The GenerateContentRequest body for one utterance — shared by sync + batch.
 *  `style` is the per-kind instruction prefix (phrase / syllable / letter). */
function ttsRequest(text, style = STYLE) {
  return {
    contents: [{ parts: [{ text: `${style} ${text}` }] }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: VOICE } } },
    },
  };
}

/** Dig the audio out of a GenerateContentResponse → WAV buffer (throws if none). */
function responseToWav(json) {
  const part = json?.candidates?.[0]?.content?.parts?.find((p) => p?.inlineData?.data);
  const data = part?.inlineData?.data;
  if (!data) throw new Error(`no audio in response: ${JSON.stringify(json).slice(0, 200)}`);
  return pcmToWav(Buffer.from(data, "base64"), sampleRateFromMime(part.inlineData.mimeType));
}

/* ------------------------------ length gate -------------------------------- */
/* The backend is a prose reader, and when it drifts it READS THE INSTRUCTION —
 * « u » came back a 20-second monologue, « Nid » a 30-second one. That failure is
 * a random roll, not a property of any utterance or prompt shape: a probe of five
 * payload shapes × eight utterances × two draws produced a clean « u » and a
 * catastrophic « Nid » from the SAME shipped payload. So it can't be prompted
 * away — but it is trivially measurable, because narration runs 2.5–20× long.
 *
 * Hence a gate rather than a better instruction. Duration comes from the WAV
 * header, so there's no ffprobe dependency and the check runs before anything
 * touches the disk. Rejected clips are simply not written: the sync path re-rolls
 * on the spot, and the batch path leaves the file absent so the next run
 * resubmits exactly those (the existing missing-clip reconciliation already
 * handles it).
 *
 * Expected duration is a per-shape line fitted to the 845-clip catalog, measured
 * cohort by cohort (same shape, same letter count). A flat cap can't work here:
 * HIPPOPOTAME at 2.6s is five syllables read normally, while « Nid » at 3.84s is
 * one syllable and therefore narration. */
const GATE_RATIO = Number(process.env.VO_GATE_RATIO ?? 2.2);
const GATE_RETRIES = Number(process.env.VO_GATE_RETRIES ?? 2);
const GATE_OFF = process.argv.includes("--no-gate");

/* onset = what every clip pays regardless of length (breath, attack, trailing
 * silence); rate = seconds per letter beyond that. Fitted to the cohort medians,
 * rounded generous so a long legitimate word never trips the gate. */
const GATE_MODEL = {
  token: { onset: 1.15, rate: 0.125 }, // 1 letter → 1.28s, 11 → 2.53s
  "comme dans": { onset: 2.0, rate: 0.085 }, // 16 letters → 3.36s
  réussite: { onset: 1.9, rate: 0.13 }, // 10 letters → 3.2s
  phrase: { onset: 2.6, rate: 0.06 }, // 30 letters → 4.4s
};

/** Which cohort an utterance belongs to — by SHAPE, not by catalog kind. `kind`
 *  records where a row came from (preview tile vs enumerateUtterances), which is
 *  why "u" and "ar" are both kind:"phrase" and must not be judged as sentences. */
const gateShape = (text) =>
  /^[^\s]+$/.test(text)
    ? "token"
    : /, comme dans /.test(text)
      ? "comme dans"
      : /^Oui ! /.test(text)
        ? "réussite"
        : "phrase";

/** Seconds of audio in a canonical 44-byte-header PCM WAV, or null if unreadable. */
function wavSeconds(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 44) return null;
  const byteRate = buf.readUInt32LE(28);
  const dataSize = buf.readUInt32LE(40);
  return byteRate > 0 ? dataSize / byteRate : null;
}

/** null when the clip passes, else an Error tagged `.gate` explaining the reject. */
function gateReject(text, wavBuffer) {
  if (GATE_OFF) return null;
  const seconds = wavSeconds(wavBuffer);
  if (seconds == null) return null; // can't measure ⇒ don't block the bake
  const shape = gateShape(text);
  const { onset, rate } = GATE_MODEL[shape];
  const expected = onset + rate * (text.match(/\p{L}/gu)?.length ?? 1);
  const ratio = seconds / expected;
  if (ratio < GATE_RATIO) return null;
  const err = new Error(
    `${seconds.toFixed(1)}s pour ${expected.toFixed(1)}s attendues (${ratio.toFixed(1)}× — ${shape}) : le modèle récite probablement l'instruction`,
  );
  err.gate = true;
  return err;
}

/* Rejected takes are kept here, never in OUT_DIR itself — clips.ts globs
 * `./clips/*.{m4a,mp3,wav}`, which doesn't descend into subdirectories, so these
 * are invisible to the build. Keeping them is the difference between a
 * diagnosable failure and a blind one: a clip can bust the gate by RECITING the
 * instruction or by REPEATING the text, and those want opposite fixes. Duration
 * alone can't tell them apart — only listening can. */
const REJECT_DIR = join(OUT_DIR, ".rejects");

/** Write WAV → encode to final clip (or keep WAV if no encoder). */
async function bakeClip(item, wavBuffer, encoder) {
  // Gate BEFORE any write, so a rejected roll leaves nothing behind to be reused.
  const rejected = gateReject(item.text, wavBuffer);
  if (rejected) {
    await mkdir(REJECT_DIR, { recursive: true });
    // Stamp the attempt so successive re-rolls accumulate instead of overwriting:
    // three bad takes of one utterance is itself the finding.
    let n = 0;
    while (await fileExists(join(REJECT_DIR, `${item.key}__${n}.wav`))) n++;
    await writeFile(join(REJECT_DIR, `${item.key}__${n}.wav`), wavBuffer);
    rejected.message += ` [prise gardée : .rejects/${item.key}__${n}.wav]`;
    throw rejected;
  }
  const wav = join(OUT_DIR, `${item.key}.wav`);
  await writeFile(wav, wavBuffer);
  if (encoder) {
    await encode(wav, item.out, encoder); // item.out already carries the final ext
    await unlink(wav);
  }
}

/** Call Gemini TTS synchronously → WAV buffer, with 429/5xx backoff. */
async function synthesize(text, style = STYLE) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`;
  const payload = JSON.stringify(ttsRequest(text, style));

  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": API_KEY },
      body: payload,
    });
    if (res.ok) {
      return responseToWav(await res.json());
    }
    const body = await res.text();
    if ((res.status === 429 || res.status >= 500) && attempt < MAX_RETRIES) {
      const wait = retryDelayMs(res, body, attempt);
      if (wait > MAX_WAIT_MS) {
        const err = new Error(
          `${res.status}: API wants a ${Math.round(wait / 1000)}s wait (> ${Math.round(MAX_WAIT_MS / 1000)}s cap) — quota exhausted${quotaHint(body)}`,
        );
        err.fatal = true; // stop the whole run; retrying other items just burns more 429s
        throw err;
      }
      console.log(`    … ${res.status}; waiting ${Math.round(wait / 1000)}s (retry ${attempt + 1}/${MAX_RETRIES})`);
      await sleep(wait);
      continue;
    }
    throw new Error(`${res.status} ${res.statusText}: ${body.slice(0, 200)}`);
  }
}

/* --------------------------------- batch ---------------------------------- */
/* Default path. Gemini Batch Mode has its OWN quota (separate from the sync    */
/* API) and costs ~50% less — the right tool for baking hundreds of clips. It's */
/* async (minutes → up to 24h), so we persist the job id to .vo-batch.json and  */
/* resume polling on re-run instead of resubmitting if the script is stopped.   */
const API = "https://generativelanguage.googleapis.com";
const JOB_FILE = join(OUT_DIR, ".vo-batch.json");

async function readJob() {
  try {
    return JSON.parse(await readFile(JOB_FILE, "utf8"));
  } catch {
    return null;
  }
}
const writeJob = (job) => writeFile(JOB_FILE, JSON.stringify(job, null, 2));
const clearJob = () => unlink(JOB_FILE).catch(() => {});

/** Upload the request JSONL via the resumable File API → returns "files/…". */
async function uploadJsonl(jsonl) {
  const bytes = Buffer.from(jsonl, "utf8");
  const start = await fetch(`${API}/upload/v1beta/files`, {
    method: "POST",
    headers: {
      "x-goog-api-key": API_KEY,
      "X-Goog-Upload-Protocol": "resumable",
      "X-Goog-Upload-Command": "start",
      "X-Goog-Upload-Header-Content-Length": String(bytes.length),
      "X-Goog-Upload-Header-Content-Type": "application/jsonl",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ file: { display_name: "attrape-lettres-vo-batch" } }),
  });
  if (!start.ok) throw new Error(`upload start ${start.status}: ${(await start.text()).slice(0, 200)}`);
  const uploadUrl = start.headers.get("x-goog-upload-url");
  if (!uploadUrl) throw new Error("File API returned no upload URL");

  const up = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      "x-goog-api-key": API_KEY,
      "X-Goog-Upload-Command": "upload, finalize",
      "X-Goog-Upload-Offset": "0",
      "Content-Length": String(bytes.length),
    },
    body: bytes,
  });
  if (!up.ok) throw new Error(`upload finalize ${up.status}: ${(await up.text()).slice(0, 200)}`);
  const name = (await up.json())?.file?.name;
  if (!name) throw new Error("upload response had no file name");
  return name;
}

/** Kick off a batch over the uploaded file → returns "batches/…". */
async function createBatch(fileName, count) {
  const res = await fetch(`${API}/v1beta/models/${MODEL}:batchGenerateContent`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": API_KEY },
    body: JSON.stringify({
      batch: { display_name: `attrape-lettres-vo ${count}`, input_config: { file_name: fileName } },
    }),
  });
  if (!res.ok) {
    // 400 here most likely means this preview TTS model isn't batch-eligible.
    throw new Error(`create batch ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  const name = (await res.json())?.name;
  if (!name) throw new Error("batch response had no name");
  return name;
}

/** Poll until the job reaches a terminal state; returns the final job object. */
async function pollBatch(name) {
  let wait = 8000;
  for (;;) {
    const res = await fetch(`${API}/v1beta/${name}`, { headers: { "x-goog-api-key": API_KEY } });
    if (!res.ok) throw new Error(`poll ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const job = await res.json();
    const state = job?.state ?? job?.metadata?.state ?? "?";
    const stats = job?.batchStats ?? job?.metadata?.batchStats;
    const progress = stats ? ` ${stats.completedRequestCount ?? stats.successfulRequestCount ?? "?"}/${stats.requestCount ?? "?"}` : "";
    console.log(`  … ${state}${progress}`);
    // The API returns BATCH_STATE_* (docs say JOB_STATE_*); match the suffix so
    // either prefix terminates the loop instead of polling a finished job forever.
    const terminal = state.replace(/^(JOB|BATCH)_STATE_/, "");
    if (terminal === "SUCCEEDED") return job;
    if (["FAILED", "CANCELLED", "EXPIRED"].includes(terminal)) {
      throw new Error(`batch ${state}: ${JSON.stringify(job?.error ?? job).slice(0, 300)}`);
    }
    await sleep(wait);
    wait = Math.min(60000, Math.round(wait * 1.4)); // ramp 8s → 60s
  }
}

/** Turn a finished job into result JSONL text (download file, or inline). */
async function fetchResults(job, todo) {
  const outFile = job?.dest?.fileName ?? job?.metadata?.dest?.fileName ?? job?.response?.responsesFile;
  if (outFile) {
    console.log(`\nDownloading results (${outFile})…`);
    const res = await fetch(`${API}/download/v1beta/${outFile}:download?alt=media`, {
      headers: { "x-goog-api-key": API_KEY },
    });
    if (!res.ok) throw new Error(`download ${res.status}: ${(await res.text()).slice(0, 200)}`);
    return await res.text();
  }
  const inlined = job?.dest?.inlinedResponses?.inlinedResponses ?? job?.response?.inlinedResponses;
  if (inlined) {
    // Normalise inline responses into the same {key,response} line shape as file output.
    return inlined.map((r, i) => JSON.stringify({ key: r.key ?? todo[i]?.key, response: r.response ?? r })).join("\n");
  }
  // If neither path matched, dump the whole job so we can see the real field name.
  throw new Error(`finished job exposed no results — full job:\n${JSON.stringify(job, null, 2)}`);
}

/** Parse result JSONL and bake each clip. Matches by `key`, falls back to order.
 *  Returns the bake count + a Map of key → failure reason for items that errored. */
async function bakeResults(jsonl, todo, encoder, ext) {
  const byKey = new Map(todo.map((t) => [t.key, t]));
  const lines = jsonl.split("\n").filter((l) => l.trim());
  let made = 0;
  let gated = 0; // rolls the length gate threw out — retryable, not broken
  const reasons = new Map(); // key → why it didn't bake
  for (let i = 0; i < lines.length; i++) {
    let obj;
    try {
      obj = JSON.parse(lines[i]);
    } catch {
      continue; // unparseable line; the disk reconciliation below still flags the gap
    }
    // Match by key. A key absent from `todo` means that clip was already baked on
    // an earlier (interrupted) run — skip it. Never fall back to position when a
    // key exists, or we'd write this audio under a different word's filename.
    const item = obj.key ? byKey.get(obj.key) : todo[i];
    if (!item) continue; // already present, or an unkeyed line we can't place

    const resp = obj.response ?? obj;
    const errored = obj.error ?? resp?.error;
    if (errored) {
      reasons.set(item.key, JSON.stringify(errored).slice(0, 150));
      console.error(`  ✗ ${item.key} — ${reasons.get(item.key)}`);
      continue;
    }
    try {
      await bakeClip(item, responseToWav(resp), encoder);
      made++;
      console.log(`  ✓ [${made}/${todo.length}] ${item.key}.${ext}  "${item.text}"`);
    } catch (e) {
      // A gate reject is not a failure to fix — it's a bad roll to redo. Nothing
      // was written, so the missing-clip reconciliation resubmits it on re-run.
      if (e.gate) gated++;
      reasons.set(item.key, `${e.gate ? "durée rejetée — " : ""}${e.message}`);
      console.error(`  ${e.gate ? "↻" : "✗"} ${item.key} — ${e.message}`);
    }
  }
  return { made, reasons, gated };
}

async function runBatch(todo, encoder, ext) {
  let job = await readJob();
  if (!job) {
    if (todo.length === 0) {
      console.log("All clips present — nothing to submit.");
      return;
    }
    try {
      console.log(`Submitting batch job for ${todo.length} clips…`);
      const fileName = await uploadJsonl(todo.map((t) => JSON.stringify({ key: t.key, request: ttsRequest(t.prompt ?? t.text, t.style) })).join("\n") + "\n");
      const name = await createBatch(fileName, todo.length);
      job = { name, count: todo.length };
      await writeJob(job);
      console.log(`  submitted → ${name}`);
      console.log(`  job saved to .vo-batch.json — safe to Ctrl-C; re-run to resume polling.\n`);
    } catch (e) {
      console.error(`\nCould not submit batch: ${e.message}`);
      console.error("If this preview TTS model rejects batch, bake sync instead: pnpm run vo:build -- --sync");
      return;
    }
  } else {
    console.log(`Resuming batch job ${job.name} (re-run of a submitted batch)…\n`);
  }

  let finished;
  try {
    finished = await pollBatch(job.name);
  } catch (e) {
    console.error(`\nBatch did not complete: ${e.message}`);
    await clearJob();
    console.error("Cleared saved job. Re-run to submit a fresh batch, or use --sync for a few clips.");
    return;
  }

  console.log("\nBaking clips…\n");
  let made, reasons, gated;
  try {
    ({ made, reasons, gated } = await bakeResults(await fetchResults(finished, todo), todo, encoder, ext));
  } catch (e) {
    // Batch is done server-side; keep the job file so a re-run re-downloads
    // instead of resubmitting. Only a completed download + conversion clears it.
    console.error(`\nBatch finished but download/conversion failed: ${e.message}`);
    console.error("Job kept — re-run to retry the download (no resubmit).");
    return;
  }
  await clearJob(); // download + conversion done → drop the saved job

  // Authoritative check: any requested clip still absent on disk didn't complete —
  // covers errored responses AND result lines the batch omitted entirely.
  const missing = [];
  for (const t of todo) if (!(await fileExists(t.out))) missing.push(t);

  console.log(
    `\nDone. ${made} baked${gated ? `, ${gated} rejetés par le contrôle de durée` : ""}${missing.length ? `, ${missing.length} still missing` : ""}.`,
  );
  if (missing.length) {
    console.error(`\n${missing.length} item(s) produced no clip (they'll use the robot-voice fallback):`);
    for (const t of missing) console.error(`  ✗ "${t.text}"${reasons.has(t.key) ? ` — ${reasons.get(t.key)}` : ""}`);
    console.error(`\nRe-run to retry only these (missing clips are resubmitted; the rest are skipped).`);
    if (gated) {
      console.error(
        `${gated} d'entre eux sont de mauvais tirages, pas des erreurs : le batch ne peut pas relancer\n` +
          `en cours de route, donc relancer la commande suffit à les retirer au sort.`,
      );
    }
  }
}

/* ---------------------------------- sync ---------------------------------- */
/* One request per clip. Slower + smaller per-minute quota, but immediate —     */
/* use --sync when topping up a handful of new words.                          */
async function runSync(todo, encoder, ext) {
  let made = 0;
  const failures = []; // { text, reason }
  for (let i = 0; i < todo.length; i++) {
    const item = todo[i];
    const n = `[${i + 1}/${todo.length}]`;
    const wav = join(OUT_DIR, `${item.key}.wav`);
    let calledApi = false;
    try {
      // Re-roll on the spot when the gate rejects: sync is one call per clip, so
      // a fresh take costs one request and usually lands (the blowout is random).
      for (let attempt = 0; ; attempt++) {
        if (!(await fileExists(wav))) {
          await writeFile(wav, await synthesize(item.prompt ?? item.text, item.style)); // API → WAV (reused on re-runs)
          calledApi = true;
        }
        try {
          await bakeClip(item, await readFile(wav), encoder);
          break;
        } catch (e) {
          if (!e.gate) throw e;
          // A rejected take must never be reused — otherwise the fileExists check
          // above would replay it forever instead of calling the API again.
          await unlink(wav).catch(() => {});
          if (attempt >= GATE_RETRIES) throw e;
          console.log(`    … ${e.message}; nouveau tirage (${attempt + 1}/${GATE_RETRIES})`);
          if (DELAY_MS > 0) await sleep(DELAY_MS);
        }
      }
      made++;
      console.log(`  ✓ ${n} ${item.key}.${ext}  "${item.text}"`);
    } catch (e) {
      failures.push({ text: item.text, reason: e.message });
      console.error(`  ✗ ${n} "${item.text}" — ${e.message}`);
      if (e.fatal) {
        console.error(`\nAborting: quota reached after ${made} baked. Re-run later to resume where it stopped.`);
        break;
      }
    }
    if (calledApi && DELAY_MS > 0) await sleep(DELAY_MS);
  }

  console.log(`\nDone. ${made} baked${failures.length ? `, ${failures.length} failed` : ""}.`);
  if (failures.length) {
    console.error(`\n${failures.length} item(s) produced no clip (they'll use the robot-voice fallback):`);
    for (const f of failures) console.error(`  ✗ "${f.text}" — ${f.reason}`);
    console.error(`\nRe-run to retry only these.`);
  }
}

const GROUPS = ["all", "phrases", "preview", "letters", "syllables"];

/** Does an item of this kind belong to the requested group? */
function inGroup(kind, group) {
  switch (group) {
    case "all": return true;
    case "phrases": return kind === "phrase";
    case "preview": return kind !== "phrase";
    case "letters": return kind === "letter";
    case "syllables": return kind === "syllable";
    default: return true;
  }
}

/** Value after `--flag=` in argv, or null. */
function flagValue(name) {
  const hit = process.argv.find((a) => a.startsWith(`${name}=`));
  return hit ? hit.slice(name.length + 1) : null;
}

/** First baked extension found on disk for this key, or null. */
async function clipExt(key) {
  for (const e of ["m4a", "mp3", "wav"]) {
    if (await fileExists(join(OUT_DIR, `${key}.${e}`))) return e;
  }
  return null;
}

/* Authored per-token pronunciation overrides — the ROBUST control here, because
 * gemini-flash-tts is a prose reader with NO phoneme/SSML control: a natural-
 * language "don't spell it" instruction competes with the model's own text
 * front-end and can lose (STYLE_SYLLABLE already says « jamais lettre par lettre »
 * and "CO" was still spelled « cé-o »). So instead of arguing with the model we
 * hand it a spoken form it can't misread. Key = the UPPERCASE tile text (what the
 * exercise passes to speak(), and what voKey() hashes for the clip name); value =
 * the exact string fed to the TTS. This WINS over the default transform below.
 *
 * The default already lowercases syllables (uppercase is the acronym trigger),
 * which covers most tokens — so only add a row when lowercase is wrong or STILL
 * misreads by ear. Escalation for a stubborn one: pin the vowel with its accent
 * ("co" → "cô") or respell the sound so it reads as one blend. This backend is
 * NON-deterministic: a code change is a hypothesis until you bake and LISTEN.
 * Workflow per straggler → add/adjust its row, delete its clip (name = voKey of
 * the UPPERCASE text), re-run `pnpm vo:build -- --group=syllables --sync`. */
const SAY_AS = {
  // "CO" reads as the acronym « cé-o »; "ko" spells the /ko/ blend most directly
  // (k is /k/ unambiguously in fr). Listen once: guard against the « K.O. »
  // knockout reading — if it trips, fall back to "cô" (as in « côté »).
  CO: "ko",
  // Bare "Jus" babbled twice in a row (a 32s « jus chute chi chut… » medley);
  // lowercase to steer the reader off whatever the capitalized token triggers.
  Jus: "jus",
  // "AN" the ANNIVERSAIRE tile is /an/ (the double N denasalizes) — NOT the
  // nasal /ɑ̃/ that the lowercase "an" SOUND_SAY row (level-4 sound) encodes.
  // Exact uppercase match so the tile and the sound stay distinct.
  AN: "âne",
  // Three "comme dans" rows kept garbling the WORD (manteau → « moteau »,
  // sapin → « sa peinture », pin → « pan ») across rolls: an article pins the
  // noun reading without changing what the child learns.
  "an, comme dans manteau.": "en, comme dans un manteau.",
  "in, comme dans sapin.": "hein, comme dans un sapin.",
  "in, comme dans pin.": "hein, comme dans un pin.",
  // « feu » read as the letter F four rolls in a row; « du feu » forces the noun.
  "eu, comme dans feu.": "eux, comme dans du feu.",
};

/* Same idea, applied at the SOUND-TOKEN level: the spell-sound vocabulary speaks
 * bare sound tokens ("ri", "on") inside three authored shapes — the bare prompt,
 * "<sound>, comme dans <word>." and "Oui ! <sound>." — and the TTS misreads many
 * of them in isolation (é → English "ee", "in" → English, "ri" → a giggle, "on"
 * → /ɑ̃/…). The cure is the same authored-spoken-form trick, but keyed by TOKEN
 * so one row fixes every utterance shape that speaks it. Values are real French
 * words (or accent-pinned respellings) that are EXACT homophones of the target
 * sound — a prose reader can't misread « riz » or « faux ». Keys are lowercase;
 * uppercase syllable tiles are matched after their lowercase transform. Letters
 * (kind "letter") are exempt: « O » must be NAMED, never read « eau ».
 * Same contract as SAY_AS: clip keys never change (voKey still hashes the
 * utterance text), only the audio fed to the TTS — and every row is a HYPOTHESIS
 * until you bake and LISTEN. */
const SOUND_SAY = {
  // /a/ row + friends — plain CV syllables the reader turned into interjections.
  ba: "bas", pa: "pas", ra: "rat",
  // /i/ column: real-word homophones.
  ri: "riz", li: "lit", ni: "nid", pi: "pie", vi: "vie", di: "dis", ji: "j'y",
  // /o/ column: the reader said « bon », « pool », « wow »…
  bo: "beau", po: "pot", mo: "mot", to: "tôt", lo: "l'eau", vo: "vos",
  no: "nos", so: "seau", ro: "rôt", fo: "faux", zo: "zô",
  // /y/ column. "fu" resisted « fût » AND « fus » — vowel-pin like dû/zô
  // (a « fût » completion would still be /fy/, t silent, so it can't lose).
  du: "dû", ju: "jus", mu: "mue", ru: "rue", fu: "fû", su: "sue",
  // é column — read as English "ee" (fé → « fi »): the -es determiners are
  // bullet-proof /e/ homophones; fée/nez/chez are real words. ré resisted
  // "rez" (« heureux ») AND "rhé" (« ri ») — "raie" trades a hair of vowel
  // height (/ʁɛ/) for a reading the model can't miss; "vé" has no clean
  // homophone ("vais" reads /ve/); "jo" said « jou » as the name Jo, so it
  // gets the zô-style circumflex pin.
  fé: "fée", sé: "ses", lé: "les", mé: "mes", té: "tes", né: "nez",
  ré: "raie", ché: "chez", é: "et", vé: "vais", jo: "jô",
  // Vowel teams & nasals — the level-4/5 sounds ("on" read /ɑ̃/, "in" read as
  // English "in", "eu" read /e/, "o" read /u/). "oie" still split into
  // « ou-à » on 3 of 4 rolls, so oi goes phonetic; "euh" came back clipped, so
  // eu uses the pronoun « eux » instead.
  on: "ont", an: "en", in: "hein", eu: "eux", o: "eau", oi: "wa",
  // Preview syllable tiles that misread (TOR was spelled letter-by-letter).
  tor: "tore",
  // Preview tiles spoken as they sound IN THEIR WORD — a tile is always heard
  // inside one authored word, so context wins: intervocalic S reads /z/
  // (dino-SAURE, télévi-SION, fu-SÉE), PIN is the /pɛ̃/ of LAPIN, NAS keeps
  // the usually-sounded final s of ANANAS, NE is the schwa of BANANE.
  pin: "pain", teau: "tôt", tue: "tu", dau: "dos", mate: "matte",
  ne: "nœud", saure: "zore", sion: "zion", sée: "zée", py: "pie",
  kan: "camp", nas: "nasse", tal: "talle",
  // Find-the-sound / sound-twins tokens: bare prompts, tile auditions and the
  // « Trouve tous les … ! » consigne all speak these. Same hypothesis contract
  // as every row above — bake and LISTEN. Real-word homophones where French
  // has one (cas, qui, quai, haie, ce, are, ire); vowel-pinned respellings
  // where it doesn't (kô like zô/jô; oure/oire/hure read as one rime).
  ka: "cas", ko: "kô", ki: "qui", ké: "quai",
  è: "haie", se: "ce",
  ar: "are", ir: "ire", ur: "hure", our: "oure", oir: "oire",
};

/* ---------------------------------------------------------------------------
 * PHONETIC TARGETS — the specification that supersedes the two tables above.
 *
 * The tables above argue with the model in French orthography ("zô", "fû",
 * "hein"): every row is a guess about how a prose reader will misread, and
 * unreviewable by anyone who wasn't in the listening session. These rows are a
 * SPEC. « saure → /zɔʁ/ » is either right or wrong about French, and you can
 * tell which without hearing it.
 *
 * The lever is the INSTRUCTION channel, not the text: the model still receives
 * ordinary French, so prosody, warmth and the anchor word survive intact — only
 * the disambiguation moves out of band. That's what the homophone hacks could
 * never do without rewriting what the child hears (« manteau » → « un manteau »).
 *
 * Keys are lowercase tokens, matched in the SAME four utterance shapes as
 * SOUND_SAY, so one row covers the bare prompt, the tile, « … comme dans … »
 * and « Oui ! … ». A row here SUPPRESSES the homophone substitution for that
 * token — text stays real. Add to IPA_BOTH to keep both levers.
 *
 * Values follow the loi de position: an isolated open syllable takes the closed
 * vowel (« ro » is /ʁo/, not the /ʁɔ/ of robot), because the tile is heard alone.
 * Still a hypothesis until you bake and LISTEN — but a falsifiable one. */
const IPA = {
  // Bare vowels, teams and nasals (BASIC_SOUNDS / SOUND_TARGETS).
  a: "a", i: "i", o: "o", u: "y", é: "e", è: "ɛ",
  ou: "u", oi: "wa", au: "o", eu: "ø", an: "ɑ̃", in: "ɛ̃", on: "ɔ̃",
  // Closed R rimes — nothing after the /ʁ/, so no parasitic schwa.
  or: "ɔʁ", ar: "aʁ", ir: "iʁ", ur: "yʁ", our: "uʁ", oir: "waʁ",
  // Syllable grid, E column: all schwa. The column exists to contrast with É,
  // so /ə/ drifting to /e/ or /ø/ collapses the exercise, not just the clip.
  le: "lə", me: "mə", re: "ʁə", ve: "və", pe: "pə", te: "tə", be: "bə",
  de: "də", fe: "fə", se: "sə", ne: "nə", je: "ʒə", ze: "zə", che: "ʃə",
  // …É column: the contrast partner.
  lé: "le", mé: "me", ré: "ʁe", vé: "ve", pé: "pe", té: "te", bé: "be",
  dé: "de", fé: "fe", sé: "se", né: "ne", jé: "ʒe", zé: "ze", ché: "ʃe",
  // …A/I/O/U columns.
  ba: "ba", pa: "pa", ra: "ʁa", ta: "ta", da: "da", ma: "ma", na: "na",
  ja: "ʒa", la: "la", sa: "sa", fa: "fa", va: "va", za: "za", cha: "ʃa",
  bi: "bi", pi: "pi", ri: "ʁi", ti: "ti", di: "di", mi: "mi", ni: "ni",
  ji: "ʒi", li: "li", si: "si", fi: "fi", vi: "vi", zi: "zi", chi: "ʃi",
  bo: "bo", po: "po", ro: "ʁo", to: "to", do: "do", mo: "mo", no: "no",
  jo: "ʒo", lo: "lo", so: "so", fo: "fo", vo: "vo", zo: "zo", cho: "ʃo",
  bu: "by", pu: "py", ru: "ʁy", tu: "ty", du: "dy", mu: "my", nu: "ny",
  ju: "ʒy", lu: "ly", su: "sy", fu: "fy", vu: "vy", zu: "zy", chu: "ʃy",
  // Assemble tiles — word fragments with no meaning of their own, which is
  // exactly why a prose reader mangles them. Each is a slice of ONE authored
  // word, so the target is how it sounds IN that word.
  teau: "to", tor: "tɔʁ", ton: "tɔ̃", son: "sɔ̃", ron: "ʁɔ̃", lon: "lɔ̃",
  chon: "ʃɔ̃", tron: "tʁɔ̃", pan: "pɑ̃", kan: "kɑ̃", pin: "pɛ̃", phin: "fɛ̃",
  phant: "fɑ̃", co: "ko", ko: "ko", ca: "ka", ci: "si", cop: "kɔp", cro: "kʁo",
  bot: "bo", lat: "la", mar: "maʁ", nard: "naʁ", per: "pɛʁ", pois: "pwa",
  ris: "ʁi", rotte: "ʁɔt", rou: "ʁu", sou: "su", mou: "mu", saire: "sɛʁ",
  tame: "tam", teur: "tœʁ", tive: "tiv", ture: "tyʁ", tère: "tɛʁ", ver: "vɛʁ",
  voi: "vwa", dile: "dil", leil: "lɛj", um: "ɔm", hip: "ip", gâ: "ɡɑ",
  gou: "ɡu", hô: "o", hé: "e", phone: "fɔn", pluie: "plɥi", nas: "nɑs",
  tal: "tal", mate: "mat", dau: "do", py: "pi", tue: "ty", quet: "kɛ",
  // Intervocalic S says /z/ — a real French rule, and the tile is only ever met
  // inside its word (dino-SAURE, télévi-SION, fu-SÉE). QUA is the /kwa/ of
  // AQUARIUM, not the /ka/ of the twin-family graphy (graphies are never spoken).
  saure: "zɔʁ", sion: "zjɔ̃", sée: "ze", qua: "kwa",
  // Sound-twins family tokens: the consigne, the bare prompt and every tile
  // audition speak these. The GRAPHIES are shown, never uttered.
  ka: "ka", ki: "ki", ké: "ke",
  // Consonant blends. Spoken ONLY inside « <blend>, comme dans <mot> », never
  // bare — so they have no tile to check them against, and a reader that slips
  // a schwa in (/kəʁa/ for « cra ») breaks the very thing the drill teaches:
  // that two consonants can share one attack. « plu » is /ply/, NOT the /plɥ/ it
  // becomes inside PLUIE: the token is uttered on its own and needs a vowel.
  cra: "kʁa", tra: "tʁa", dra: "dʁa", fra: "fʁa", fro: "fʁo",
  bra: "bʁa", bri: "bʁi", pri: "pʁi", pla: "pla", plu: "ply", gla: "ɡla",
  // /u/ column of the same drill (loup, poule, bouche).
  lou: "lu", pou: "pu", bou: "bu",
};

/* Utterance-level targets, for the cases a token row can't express. */
const IPA_EXACT = {
  // The ANNIVERSAIRE tile is /an/ — the double N denasalizes — NOT the /ɑ̃/ that
  // the lowercase "an" row encodes for the level-4 sound. Exact match keeps the
  // tile and the sound distinct, same as the SAY_AS row it replaces.
  AN: "an",
};

/* Tokens that keep BOTH levers: an authored spoken form AND the phonetic target.
 * The escalation for a straggler that ignores the instruction on its own — TEAU
 * read « TE » with /to/ alone, so it also gets fed « tôt ». Belt and braces for a
 * stubborn handful, never as the default: doubling up re-introduces exactly the
 * guesswork the spec exists to remove. */
const IPA_BOTH = new Set(["teau"]);

/** The phonetic target for an item, or null. Mirrors promptText's shape matching
 *  so one token row covers every utterance that speaks it. `whole` says whether
 *  the target covers the entire text or just one token inside prose — « an,
 *  comme dans manteau. » must phonemise "an" and leave the anchor word alone. */
const ipaTarget = (it) => {
  if (it.kind === "letter") return null; // a letter is NAMED, never sounded out
  const exact = IPA_EXACT[it.text];
  if (exact) return { token: it.text.toLowerCase(), ipa: exact, whole: true };
  const text = it.kind === "syllable" ? it.text.toLowerCase() : it.text;
  const hit = (tok, whole) => {
    const ipa = IPA[tok.toLowerCase()];
    return ipa ? { token: tok.toLowerCase(), ipa, whole } : null;
  };
  const prompt = /^(.+?), comme dans (.+)\.$/.exec(text);
  const success = /^Oui ! (.+)\.$/.exec(text);
  const twins = /^Trouve tous les (.+) !$/.exec(text);
  return (
    hit(text, true) ??
    (prompt && hit(prompt[1], false)) ??
    (success && hit(success[1], false)) ??
    (twins && hit(twins[1], false)) ??
    null
  );
};

/** Per-item instruction: head, then the phonetic target, then « Lis : ».
 *
 *  The trailing colon is load-bearing — a "read what follows" cue, because the
 *  batch endpoint has no separate prompt field and PREPENDS this to the text.
 *  Drop it and the model reads the instruction aloud (observed: a clip that said
 *  « sa prononciation exacte en alphabet phonétique international est O »). So
 *  the instruction ALWAYS ends here, and only here.
 *
 *  The clause is a bare equation rather than a sentence. Spelling out « se
 *  prononce exactement … en Alphabet Phonétique International — respecte-le
 *  strictement » steered no better by ear and cost ~120 characters a call. */
const styleFor = (it) => {
  const base = STYLE_BY_KIND[it.kind] ?? STYLE;
  const t = ipaTarget(it);
  if (!t) return `${base} Lis :`;
  const clause = t.whole ? `Prononce /${t.ipa}/.` : `« ${t.token} » = /${t.ipa}/.`;
  return `${base} ${clause} Lis :`;
};

/** What we actually FEED the TTS for an item — which can differ from the clip's
 *  identity text. An explicit SAY_AS override wins; then the SOUND_SAY token map
 *  is applied inside the three sound-utterance shapes; otherwise a syllable is
 *  spoken lowercase (uppercase reads as an acronym) and everything else verbatim.
 *  The key is still derived from the original `text`, so the runtime lookup in
 *  clips.ts is unchanged; only the audio content improves. Letters stay uppercase
 *  AND unmapped (STYLE_LETTER wants the letter NAMED — « O », never « eau »). */
const promptText = (it) => {
  // A phonetic target supersedes the homophone tables: the whole point is that
  // the model receives REAL French and gets the pronunciation out of band, so
  // the anchor word and the prosody survive. IPA_BOTH opts a straggler back in.
  const target = ipaTarget(it);
  if (target && !IPA_BOTH.has(target.token)) {
    return it.kind === "syllable" ? it.text.toLowerCase() : it.text;
  }
  const exact = SAY_AS[it.text];
  if (exact) return exact;
  const text = it.kind === "syllable" ? it.text.toLowerCase() : it.text;
  if (it.kind === "letter") return text;
  const say = (tok) => SOUND_SAY[tok.toLowerCase()];
  if (say(text)) return say(text); // bare sound prompt / syllable tile
  const prompt = /^(.+?), comme dans (.+)\.$/.exec(text); // spell-sound prompt
  if (prompt && say(prompt[1])) return `${say(prompt[1])}, comme dans ${prompt[2]}.`;
  const success = /^Oui ! (.+)\.$/.exec(text); // spell-sound success line
  if (success && say(success[1])) return `Oui ! ${say(success[1])}.`;
  const twins = /^Trouve tous les (.+) !$/.exec(text); // sound-twins consigne
  if (twins && say(twins[1])) return `Trouve tous les ${say(twins[1])} !`;
  return text;
};

/** Combined, deduped catalog: phrases first (they win a key collision), then the
 *  separate preview vocabulary, each item tagged with kind + its per-kind style. */
function buildCatalog(enumerateUtterances, enumeratePreviewUtterances) {
  const phrases = enumerateUtterances().map((text) => ({ text, kind: "phrase" }));
  const preview = (enumeratePreviewUtterances?.() ?? []).map((it) => ({ text: it.text, kind: it.kind }));
  const seen = new Set();
  const out = [];
  for (const it of [...phrases, ...preview]) {
    if (seen.has(it.text)) continue;
    seen.add(it.text);
    out.push({ ...it, style: styleFor(it), prompt: promptText(it) });
  }
  return out;
}

async function main() {
  const sync = process.argv.includes("--sync");
  const group = flagValue("--group") ?? "all";
  const listGroup = LISTING ? flagValue("--list") ?? "all" : null;
  if (!GROUPS.includes(group)) {
    console.error(`Unknown --group=${group}. Use one of: ${GROUPS.join(", ")}.`);
    process.exit(1);
  }

  const { enumerateUtterances, voKey, enumeratePreviewUtterances } = await loadVocab();
  await mkdir(OUT_DIR, { recursive: true });

  const encoder = await detectEncoder();
  const ext = encoder ? FORMAT : "wav";

  const catalog = buildCatalog(enumerateUtterances, enumeratePreviewUtterances);

  // A SAY_AS key that matches no utterance is a typo that silently does nothing —
  // surface it so an override can't quietly rot when a syllable is renamed/removed.
  const known = new Set(catalog.map((it) => it.text));
  for (const k of Object.keys(SAY_AS)) {
    if (!known.has(k)) console.warn(`  ! SAY_AS override "${k}" matches no utterance — typo or stale? (no effect)`);
  }
  // Same rot-guard for SOUND_SAY: collect every sound token the catalog speaks
  // (bare prompts, syllable tiles, "comme dans" prompts, "Oui !" successes).
  const spokenTokens = new Set();
  for (const it of catalog) {
    if (it.kind === "letter") continue;
    const text = it.kind === "syllable" ? it.text.toLowerCase() : it.text;
    const m =
      /^(.+?), comme dans .+\.$/.exec(text) ??
      /^Oui ! (.+)\.$/.exec(text) ??
      /^Trouve tous les (.+) !$/.exec(text);
    spokenTokens.add((m ? m[1] : text).toLowerCase());
  }
  for (const k of Object.keys(SOUND_SAY)) {
    if (!spokenTokens.has(k)) console.warn(`  ! SOUND_SAY override "${k}" matches no sound token — typo or stale? (no effect)`);
  }
  // Same guard for the phonetic spec. This one earns its keep: an earlier probe
  // hand-authored 60 tokens and 44 turned out never to be uttered (twin graphies
  // are SHOWN, not spoken) — a full listening pass spent on audio no child hears.
  for (const k of Object.keys(IPA)) {
    if (!spokenTokens.has(k)) console.warn(`  ! IPA target "${k}" matches no sound token — typo or stale? (no effect)`);
  }
  for (const k of Object.keys(IPA_EXACT)) {
    if (!known.has(k)) console.warn(`  ! IPA_EXACT target "${k}" matches no utterance — typo or stale? (no effect)`);
  }
  for (const k of IPA_BOTH) {
    if (!IPA[k]) console.warn(`  ! IPA_BOTH "${k}" has no IPA row — it only re-enables the homophone hack`);
  }

  // --list: print the rollback map (key → filename, presence) for a group, no API.
  if (listGroup) {
    if (!GROUPS.includes(listGroup)) {
      console.error(`Unknown --list=${listGroup}. Use one of: ${GROUPS.join(", ")}.`);
      process.exit(1);
    }
    const rows = catalog.filter((it) => inGroup(it.kind, listGroup));
    let have = 0;
    for (const it of rows) {
      const key = voKey(it.text);
      const found = await clipExt(key);
      if (found) have++;
      console.log(`${found ? "✓" : "·"} ${it.kind.padEnd(8)} clips/${key}.${found ?? ext}  "${it.text}"`);
    }
    console.log(`\n${have}/${rows.length} present in group "${listGroup}".`);
    return;
  }

  if (!encoder) {
    console.warn("No ffmpeg/afconvert found — keeping uncompressed .wav. Install ffmpeg for smaller clips.\n");
  }

  const items = catalog.filter((it) => inGroup(it.kind, group));
  console.log(`  mode=${sync ? "sync" : "batch"} group=${group} model=${MODEL} voice=${VOICE} format=${ext}${encoder ? ` @${BITRATE} (${encoder})` : ""}`);

  // Pre-pass: split into already-present vs to-do so we can report a total up front.
  const todo = [];
  let present = 0;
  for (const it of items) {
    const key = voKey(it.text);
    const out = join(OUT_DIR, `${key}.${ext}`);
    if (await fileExists(out)) present++;
    else todo.push({ text: it.text, key, out, kind: it.kind, style: it.style, prompt: it.prompt });
  }
  console.log(`${present}/${items.length} audio samples exist, creating the ${todo.length} missing…\n`);

  if (sync) await runSync(todo, encoder, ext);
  else await runBatch(todo, encoder, ext);
}

await main();
