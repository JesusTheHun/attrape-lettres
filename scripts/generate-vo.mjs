import { access, mkdir, readFile, unlink, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

/* -------------------------------------------------------------------------- */
/* Bake the finite, authored VO vocabulary to audio with Gemini TTS.            */
/*                                                                            */
/*   GEMINI_API_KEY=xxx pnpm run vo:build            # batch (default)           */
/*   GEMINI_API_KEY=xxx pnpm run vo:build -- --sync   # one call per clip         */
/*                                                                            */
/* Default = Gemini Batch Mode: separate quota from the sync API, ~50% cheaper, */
/* async. The job id is stored in src/vo/clips/.vo-batch.json so a stopped run   */
/* resumes polling instead of resubmitting. Use --sync to top up a few clips.   */
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
// The French "apprendre à lire" framing isn't spoken (it's a style instruction);
// it gives the safety classifier language context so short French syllables that
// collide with flagged English words (e.g. "nu", "tu") aren't rejected.
const STYLE =
  process.env.GEMINI_TTS_STYLE ??
  "Tu aides un enfant de six ans à apprendre à lire en français. Lis ce texte français à voix haute, d'une voix douce, chaleureuse et enjouée :";
const DELAY_MS = Number(process.env.GEMINI_TTS_DELAY_MS ?? 1500); // between API calls
const MAX_RETRIES = Number(process.env.GEMINI_TTS_RETRIES ?? 6);
const MAX_WAIT_MS = Number(process.env.GEMINI_TTS_MAX_WAIT_MS ?? 300000); // fail fast past this (5 min)
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

/** Pull the quota name out of a 429 body so the abort message says WHICH limit. */
function quotaHint(body) {
  const m = /"quotaId":\s*"([^"]+)"/.exec(body) || /"quotaMetric":\s*"([^"]+)"/.exec(body);
  return m ? ` (limit: ${m[1]})` : "";
}

/** The GenerateContentRequest body for one utterance — shared by sync + batch. */
function ttsRequest(text) {
  return {
    contents: [{ parts: [{ text: `${STYLE} ${text}` }] }],
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

/** Write WAV → encode to final clip (or keep WAV if no encoder). */
async function bakeClip(item, wavBuffer, encoder) {
  const wav = join(OUT_DIR, `${item.key}.wav`);
  await writeFile(wav, wavBuffer);
  if (encoder) {
    await encode(wav, item.out, encoder); // item.out already carries the final ext
    await unlink(wav);
  }
}

/** Call Gemini TTS synchronously → WAV buffer, with 429/5xx backoff. */
async function synthesize(text) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`;
  const payload = JSON.stringify(ttsRequest(text));

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
      reasons.set(item.key, e.message);
      console.error(`  ✗ ${item.key} — ${e.message}`);
    }
  }
  return { made, reasons };
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
      const fileName = await uploadJsonl(todo.map((t) => JSON.stringify({ key: t.key, request: ttsRequest(t.text) })).join("\n") + "\n");
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
  let made, reasons;
  try {
    ({ made, reasons } = await bakeResults(await fetchResults(finished, todo), todo, encoder, ext));
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

  console.log(`\nDone. ${made} baked${missing.length ? `, ${missing.length} still missing` : ""}.`);
  if (missing.length) {
    console.error(`\n${missing.length} item(s) produced no clip (they'll use the robot-voice fallback):`);
    for (const t of missing) console.error(`  ✗ "${t.text}"${reasons.has(t.key) ? ` — ${reasons.get(t.key)}` : ""}`);
    console.error(`\nRe-run to retry only these (missing clips are resubmitted; the rest are skipped).`);
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
      if (!(await fileExists(wav))) {
        await writeFile(wav, await synthesize(item.text)); // API → WAV (reused on re-runs)
        calledApi = true;
      }
      await bakeClip(item, await readFile(wav), encoder);
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

async function main() {
  const sync = process.argv.includes("--sync");
  const { enumerateUtterances, voKey } = await loadVocab();
  await mkdir(OUT_DIR, { recursive: true });

  const encoder = await detectEncoder();
  const ext = encoder ? FORMAT : "wav";
  if (!encoder) {
    console.warn("No ffmpeg/afconvert found — keeping uncompressed .wav. Install ffmpeg for smaller clips.\n");
  }

  const items = enumerateUtterances();
  console.log(`  mode=${sync ? "sync" : "batch"} model=${MODEL} voice=${VOICE} format=${ext}${encoder ? ` @${BITRATE} (${encoder})` : ""}`);

  // Pre-pass: split into already-present vs to-do so we can report a total up front.
  const todo = [];
  let present = 0;
  for (const text of items) {
    const key = voKey(text);
    const out = join(OUT_DIR, `${key}.${ext}`);
    if (await fileExists(out)) present++;
    else todo.push({ text, key, out });
  }
  console.log(`${present}/${items.length} audio samples exist, creating the ${todo.length} missing…\n`);

  if (sync) await runSync(todo, encoder, ext);
  else await runBatch(todo, encoder, ext);
}

await main();
