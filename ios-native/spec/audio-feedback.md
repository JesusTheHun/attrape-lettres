# Audio & immediate feedback — port specification

Scope: `src/vo/**`, `scripts/generate-vo.mjs`, `src/hooks/useAudio.ts`,
`src/hooks/useConfetti.ts`, and every synchronous-feedback call site that touches
them. Target layout per **D1** (`ALCore` / `ALArt` / `ALUI` / `App`).

**Behaviour is frozen.** Every number, envelope, string and ordering below is
copied from the PWA. Where I recommend something the PWA does not specify (audio
session category, haptics), it is called out as a *decision*, not a change.

---

## 1. Inventory

### 1.1 In scope — TypeScript

| File | Lines | What it actually does |
|---|---|---|
| `src/hooks/useAudio.ts` | 295 | The whole audio API. Web Audio oscillator SFX (`pop`/`success`/`nudge`/`oops`), the single-flight voice channel (`say`/`stop`/`unlock`), baked-clip playback via one reused `HTMLAudioElement`, `speechSynthesis` fallback with voice scoring, three watchdogs, a 200 ms leave-fade. |
| `src/hooks/useConfetti.ts` | 94 | Canvas + `requestAnimationFrame` particle burst. 90 particles, no React state touched, `prefers-reduced-motion` suppresses `fire()`. |
| `src/vo/utterances.ts` | 144 | `enumerateUtterances()` — the finite authored set of *phrases* the app speaks (723 strings). `voKey()` — FNV-1a/32 over NFC-normalised text, base36. Shop copy constants + `shopCostLine()`. |
| `src/vo/preview.ts` | 56 | `enumeratePreviewUtterances()` — the "hear it before you tap it" tile vocabulary (122 items: 25 letters + 97 syllables), each tagged `VoKind`. Deliberately a separate module so it can be rolled back independently. |
| `src/vo/clips.ts` | 36 | `import.meta.glob` over `./clips/*.{m4a,mp3,wav}` → `Map<voKey, url>`, preferring `m4a > mp3 > wav`. `clipUrl(text)`, `hasBakedVoice`. |
| `src/vo/preview.test.ts` | 32 | Pins the preview catalog against the real content banks and asserts tile case is verbatim UPPERCASE. |
| `src/hooks/useAudio.test.ts` | 162 | Six assertions on the voice channel: resolves true on end, false on supersede / media error / `stop()` / watchdog, and the TTS fallback path. |
| `src/vo/clips/*.m4a` | 845 files | The baked bank. **Not code — data.** See §2. |
| `src/dev/VoGallery.tsx` | 237 | Dev-only audition bench at `#vo`. Out of the kid flow. |
| `scripts/generate-vo.mjs` | 1039 | The bake pipeline (Gemini TTS → WAV → AAC), the IPA tables, the length gate. **Does not get ported** — see §2.4. |

### 1.2 In scope — call sites (feedback consumers)

`audio.*` is consumed by 9 exercises, the hub and the shop. Counted from the
working tree:

- `audio.pop()` — 12 sites, all inside a pointer-down handler.
- `audio.nudge()` — 6 sites (`FirstLetter`, `FindSound`, `ReadImage`, `SoundTwins`, `SyllableGrid`, `LetterMatch`), always immediately after `pop()` on a wrong tap.
- `audio.oops()` — 3 sites (`Assemble`, `SpellSound`, `SpellSyllable`), the "row complete but wrong order" verdict.
- `audio.success()` — 11 sites.
- `audio.say()` — 53 sites (list in §5.4).
- `audio.unlock()` — 26 sites.
- `audio.stop()` — 9 sites, all `useEffect(() => () => audio.stop(), [audio])`.
- `fire()` (confetti) — 9 sites, one per exercise, always immediately after `audio.success()`.

`src/components/Tile.tsx` (131 lines) owns the WAAPI press/shake animation that
must fire on the *same* synchronous beat; `src/components/GameFrame.tsx` (74)
owns the canvas overlay slot. Both belong to the ui-components agent — I own the
contract they must satisfy (§5.1, §7.1).

### 1.3 Facts about the clip bank (measured, not inferred)

```
845 .m4a files, 0 .wav, 0 .mp3      (the glob's mp3/wav arms are dead in practice)
14 355 020 bytes total              = 13.69 MiB
mean 16 988 B, max 45 532 B         (1y6yasc.m4a, 7.40 s)
AAC-LC, 1 ch, 24 000 Hz, ~47 kbps   (afinfo on a sample; generator default VO_BITRATE=48k)
≈ 2 434 s of audio total            (40 min 34 s), mean clip 2.88 s
catalog coverage: 845/845 present   (`node scripts/generate-vo.mjs --list=all`)
  phrases   723/723
  preview   122/122   (letters 25/25, syllables 97/97)
zero orphans: files on disk == catalog size exactly
```

`src/vo/clips/.rejects/` is gitignored diagnostic material and must **not** ship.

---

## 2. The clip bank in Swift

### 2.1 Packaging decision: a plain SwiftPM resource directory in **ALCore**

```
Sources/ALCore/Resources/Clips/<voKey>.m4a          845 files, 13.7 MiB
```

declared in `Package.swift` as:

```swift
.target(name: "ALCore", resources: [.copy("Resources/Clips")])
```

`.copy` (not `.process`) — the files must land verbatim under
`ALCore_ALCore.bundle/Clips/`, with their hash filenames untouched. `.process`
would be free to rewrite/flatten names, and the filename *is* the lookup key.

**Why ALCore and not ALUI.** The key→file mapping is logic, not presentation:
it is `voKey()` plus a directory listing, and the single most valuable test in
this whole scope ("every one of the 845 authored utterances resolves to a real
file") must run on the host under `swift test` with no SwiftUI import. Resources
in ALCore make that a three-line test. The AVFoundation player that *consumes*
the URLs still lives in ALUI; it takes a `URL` from `ALCore.ClipBank`.

**Why not an asset catalog.** `NSDataAsset` gives you `Data`, not a `URL`, so
`AVAudioFile` can't open it without a temp-file round trip; asset catalogs in a
SwiftPM target are awkward to keep verbatim-named; and catalog compilation buys
nothing for already-compressed AAC. Zero upside.

**Why not On-Demand Resources.** ODR is the one option that can actually break
the product. The VO *is* the gameplay — a child who cannot hear « Trouve la
première lettre de Ballon » cannot play. ODR needs the App Store's CDN on first
use, which fails on a plane, in a car, on a hotel Wi-Fi captive portal. That is
a fail-closed path in a product whose CLAUDE.md ships an explicit "money never
fails closed" invariant; gameplay deserves at least the same. And the thing ODR
buys — download size — is 13.7 MiB, roughly the size of two app icons' worth of
PNGs on a modern binary. Not a trade worth making.

**Launch-time cost: zero, by construction.** Nothing is read at launch. See §2.3.

### 2.2 The key function — `voKey`, ported exactly

```ts
export function voKey(text: string): string {
  const norm = text.normalize("NFC").replace(/\s+/g, " ").trim();
  let h = 0x811c9dc5;
  for (let i = 0; i < norm.length; i++) {
    h ^= norm.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}
```

Four traps, all of which silently produce a *different filename* (→ TTS fallback
in production, which sounds like a regression and is nearly invisible in review):

1. **`charCodeAt` is a UTF-16 code unit, not a Unicode scalar and not a byte.**
   Iterate `norm.utf16`, xor `UInt32(unit)`. Every string in the catalog is BMP,
   so no surrogate pairs occur, but write it against UTF-16 anyway.
2. **`Math.imul` is a wrapping 32-bit multiply.** Swift: `h = h &* 0x0100_0193`
   on `UInt32`. Never `*`.
3. **`normalize("NFC")`** → `text.precomposedStringWithCanonicalMapping`. This
   matters: `preview.test.ts` explicitly pins « É » as *composed*.
4. **`.replace(/\s+/g, " ").trim()`** — JS `\s` is
   `[\t\n\v\f\r    -     　﻿]`.
   I grepped the whole catalog: **no NBSP, no narrow NBSP, no exotic space** —
   every separator is U+0020. So a `CharacterSet` of the JS set is equivalent to
   `.whitespacesAndNewlines` here, but spell the JS set out so a future French
   typographic space (« Bravo&nbsp;! ») can't silently fork the hash.

Base36 uses JS `Number.prototype.toString(36)` digits `0-9a-z`, lowercase, no
padding. Swift `String(h, radix: 36)` matches (Swift's `String(_:radix:)`
defaults to lowercase).

### 2.3 `ClipBank` — the runtime mapping

Mirrors `clips.ts` including its extension preference, minus the Vite glob:

```
resolve(text) -> URL?
  key = voKey(text)
  memo hit? return it
  for ext in ["m4a", "mp3", "wav"]:                 // RANK order, highest first
     Bundle.module.url(forResource: key, withExtension: ext, subdirectory: "Clips")
  memoise (including the negative result) and return
```

- **Lazy + memoised, not eager.** `Bundle.url(forResource:…)` is a cached
  directory lookup; the first call per key costs microseconds, and the app never
  needs more than ~15 distinct keys per round. An eager `contentsOfDirectory`
  index over 845 entries would cost ~1–3 ms at launch for no benefit. (Provide
  `ClipBank.indexAll()` for the *test* target only.)
- Negative results are memoised too, so a missing clip doesn't re-hit the
  filesystem on every repeat of the same failed line.
- `hasBakedVoice` → `ClipBank.hasBakedVoice: Bool`. Currently referenced only by
  the dev gallery and the test mock; port it (one line) so the surface matches.
- **The bank is `Sendable` and immutable after init** except the memo, which is
  guarded — make `ClipBank` an `actor` or a `final class` with an `os_unfair_lock`
  / `NSLock`. It is read from the main actor (say) and from the decode queue
  (preload). Language mode is `.v5` per D1, so this is discipline, not the
  compiler's problem.

### 2.4 What does **not** get ported

`scripts/generate-vo.mjs` is a build-time tool: the Gemini batch client, the IPA
override tables (`IPA`, `IPA_EXACT`, `IPA_BOTH`, `SAY_AS`), the style heads, and
the **length gate** (`GATE_RATIO 2.2`, `GATE_RETRIES 2`, `GATE_MODEL`,
`gateShape`, `.rejects/`). The clips are already baked and shipped; the Swift app
never synthesises a clip. Do not port any of it, and do not reimplement the gate
in Swift.

What *is* load-bearing and must survive is the **contract** the generator and the
runtime share: the same `enumerateUtterances()` + `enumeratePreviewUtterances()`
strings, hashed by the same `voKey()`. That is why §6 makes catalog coverage a
host test — it is the Swift-side half of `--list=all`.

The generator keeps working against the TypeScript sources: the PWA and the
native app share one clip bank. **Re-baking stays a `pnpm vo:build` job in the
web repo**, and the Swift package's `Resources/Clips` is a copy (or, better, a
symlink resolved at package-prepare time — see Risks §7.9).

---

## 3. Swift module plan

Dependency direction is strictly `App → ALUI → ALCore` and `App → ALArt → ALCore`.
Nothing in ALCore imports SwiftUI, UIKit, or AVFoundation.

### `Sources/ALCore/Audio/`

| File | Owns |
|---|---|
| `VoKey.swift` | `func voKey(_ text: String) -> String` + the normalisation helper. Pure, ~20 lines, golden-tested. |
| `Utterances.swift` | `enumerateUtterances() -> [String]` (insertion-ordered, deduped — port the `Set` + spread as an ordered set), `SHOP_BOUGHT/GREW/NEED_MORE`, `shopCostLine(_:)`. French copy byte-for-byte. |
| `PreviewVocabulary.swift` | `enum VoKind: String { case letter, syllable }`, `struct VoItem`, `enumeratePreviewUtterances() -> [VoItem]`. |
| `ClipBank.swift` | key→URL resolution over `Bundle.module`, memo, `hasBakedVoice`. |
| `AudioFeedback.swift` | `protocol AudioFeedback` — the port of `AudioApi`. Plus `enum Sfx { case pop, success, nudge, oops }`. |
| `VoiceChannel.swift` | The **single-flight state machine**, engine-agnostic: tickets, supersede, watchdogs, settle-once. Talks to `ClipPlayback` and `SpeechPlayback` protocols. This is the file that makes §5.2 host-testable. |
| `SfxSynth.swift` | Pure DSP: `func render(_ sfx: Sfx, sampleRate: Double) -> [Float]`. No AVFoundation. |
| `AudioSessionPort.swift` | `protocol AudioSessionPort { func activate(); func deactivate(notifyOthers: Bool) }` + `enum AudioInterruption { case began, ended(shouldResume: Bool) }` + `enum RouteChange { case oldDeviceUnavailable, other }` and a delegate/stream for them. iOS impl in `App/`. |
| `Haptics.swift` | `protocol HapticsPort { func impact(_:) ; func notify(_:) }` + `struct NoopHaptics: HapticsPort` (**the default — see §5.5**). |
| `MonotonicClock.swift` | `protocol MonotonicClock { var now: TimeInterval { get } }` — the `performance.now()` used by `MISS_COOLDOWN_MS`. *Shared surface: if the levels/exercises agent already declares a clock, use theirs and delete this.* |

### `Sources/ALCore/Confetti/`

| File | Owns |
|---|---|
| `ConfettiSystem.swift` | `struct Particle`, `final class ConfettiSystem` with `fire(width:height:)`, `step()`, `particles`, `isEmpty`, injected `RandomNumberGenerator`. Frame-locked at 60 Hz (§5.3). No SwiftUI. |
| `ConfettiPalette.swift` | The six colours as `struct RGB8`, in source order. |

### `Sources/ALCore/Resources/Clips/`

845 `.m4a`, copied verbatim.

### `Sources/ALUI/Audio/`

| File | Owns |
|---|---|
| `GameAudioEngine.swift` | The `AVAudioEngine` graph: 6-node SFX player pool + 1 voice `AVAudioPlayerNode` + a voice gain mixer. Pre-rendered SFX buffers. Start/stop, interruption recovery. |
| `ClipStore.swift` | `AVAudioFile` → `AVAudioPCMBuffer` decode, LRU cache (§4.4), `preload([String])` off the main thread. Implements `ClipPlayback`. |
| `SpeechFallback.swift` | `AVSpeechSynthesizer` implementation of `SpeechPlayback`, incl. the French voice scoring port (§5.6). |
| `LiveAudioFeedback.swift` | Composes `VoiceChannel` + `GameAudioEngine` + `ClipStore` + `SpeechFallback` into `AudioFeedback`. `@MainActor final class`. |
| `AudioEnvironment.swift` | `EnvironmentKey` + `View.audioFeedback(_:)`, so exercises get one instance for the app lifetime instead of one per mount (§4.2). |

### `Sources/ALUI/Confetti/`

| File | Owns |
|---|---|
| `ConfettiController.swift` | Reference box around `ConfettiSystem` + a single `@Published`-free `running` flag exposed as `@State` to the view. **Publishes twice per burst, never per frame.** |
| `ConfettiOverlay.swift` | `TimelineView(.animation(minimumInterval: 1.0/60.0, paused: !running))` + `Canvas`. `.allowsHitTesting(false)`. Reads `\.accessibilityReduceMotion`. |

### `Sources/ALUI/Interaction/`

| File | Owns |
|---|---|
| `PressDown.swift` | `View.onPressDown(perform:)` — the SwiftUI equivalent of `onPointerDown`. **Invariant 1 lives or dies here** (§5.1). Cross-cutting: agreed with the ui-components agent, implemented once. |

### `App/`

| File | Owns |
|---|---|
| `AudioSessionLive.swift` | `AVAudioSession` category/options/activation, interruption + route-change observers, foreground/background handling. The only file in this scope that imports `AVAudioSession` (iOS-only). |
| `HapticsLive.swift` | Present but **not wired**: constructs `NoopHaptics` until a product decision says otherwise (§5.5). |

---

## 4. Latency (invariant 1)

### 4.1 What must be synchronous, precisely

From `FirstLetterExercise.pick` — this is the beat that matters:

```ts
if (locked.current) return "reject";
if (performance.now() < coolUntil.current) return "reject";   // silent, but Tile still shakes
audio.unlock();
audio.pop();
if (letter !== round.target.letter) {
  audio.nudge();
  coolUntil.current = performance.now() + MISS_COOLDOWN_MS;   // 800
  missRound(idx);                                              // star greys NOW
  return "reject";
}
locked.current = true; setFlash(letter); setMood("happy");
audio.success();
fire();
```

Everything down to `fire()` runs **inside the pointer-down handler, before any
state commit**. `say()` is called synchronously too but is inherently async —
it is the *SFX* whose onset must be tap-tight. Note the two-oscillator overlap:
a wrong tap plays `pop()` **and** `nudge()` at the same instant.

### 4.2 The API decision: `AVAudioEngine` with pre-scheduled buffers. No `AVAudioPlayer` anywhere.

`AVAudioPlayer.play()` without a prior `prepareToPlay()` performs file open,
container parse, decoder instantiation and first-buffer decode **on the calling
thread**. On the pointer-down path that is a variable multi-millisecond stall
plus a main-thread hazard. `prepareToPlay()` moves the cost earlier but you then
own a player per sound per concurrent voice, and a re-`play()` after completion
re-arms lazily. It is the wrong tool. **`AVAudioPlayer` is not used in this port.**

The graph, built once at app launch:

```
6 × AVAudioPlayerNode (SFX pool)  ─┐
                                   ├─→ mainMixerNode → outputNode
1 × AVAudioPlayerNode (voice) ─→ voiceMixer (AVAudioMixerNode, for the 200 ms fade) ─┘
```

- All 7 player nodes are **attached, connected, and `play()`-ed at start-up**
  and are left running forever. A running `AVAudioPlayerNode` with nothing
  scheduled renders silence; `scheduleBuffer` on a running node is an enqueue,
  not a start.
- SFX pool is round-robin; 6 slots is comfortably more than the worst observed
  overlap (`pop`+`nudge` = 2, plus a tail from the previous tap).
- Voice node runs at **24 kHz mono float32** — the clips' native rate — and the
  engine's mixer resamples to the hardware rate. No per-clip format conversion.
- SFX buffers are rendered at `engine.outputNode.outputFormat(forBus: 0).sampleRate`
  so they need no SRC at all.

### 4.3 What is preloaded, when

| Asset | When | Cost |
|---|---|---|
| 4 SFX buffers | Once, at engine start, synthesised by `SfxSynth` | ≈ 1.14 s of audio → **~219 KB** at 48 kHz float32 mono. Synthesis is ~55 k samples of `sin()`; sub-millisecond. |
| The current round's clips | On round load, during the **350 ms announce delay the PWA already has** | prompt + success + one preview per tile ≈ 8–14 clips ≈ 30 s ≈ **2.9 MB** decoded |
| Everything else | Never | — |

**Preloading the whole bank is not an option and must not be attempted**: 2 434 s
at 24 kHz float32 mono is **234 MB** resident (117 MB as Int16). The round-level
working set is the right granularity, and the 350 ms settle timer before the
announce (`window.setTimeout(() => void audio.say(...), 350)`, present in six
exercises) is exactly the window to fill it.

`preload(_ texts: [String])` is an addition to the Swift protocol with no
behavioural effect: it decodes into the LRU on a `.userInitiated` queue and is
a no-op for already-cached or unbaked texts. Callers may skip it entirely and
nothing changes except first-play latency.

### 4.4 Decode cache

LRU keyed by `voKey`, capped by **total decoded frames ≈ 120 s** (24 kHz × 4 B ≈
**11.5 MB**). At a mean clip of 2.88 s that is ~41 clips — three rounds' worth.
Evict least-recently-*played*. Never evict a buffer that is currently scheduled
(hold a strong ref in the in-flight state).

### 4.5 The numbers to expect, and how to check them

Target, all on device (a simulator's audio path is not representative):

| Segment | Expectation |
|---|---|
| gesture callback → `scheduleBuffer` returns | **< 0.5 ms** of main-thread work (buffer is already in memory; this is an enqueue) |
| enqueue → first sample rendered | ≤ 1 render quantum. With `setPreferredIOBufferDuration(0.005)` that is **~5 ms** (accept 5–11 ms; the system may not grant 5 ms) |
| render → acoustic onset | `AVAudioSession.outputLatency`, **~7–12 ms** built-in speaker, higher on Bluetooth |
| **total tap→sound** | **≈ 15–20 ms wired/speaker.** Bluetooth adds 100–300 ms and is outside our control. |

For comparison the PWA path is `ctx.createOscillator(); osc.start(ctx.currentTime)`
— also sub-quantum scheduling, so this is parity, not a regression.

How to verify (this is *not* a unit test): sign a debug build with a signpost
(`OSSignposter`) at the gesture callback and an `AVAudioPlayerNode`
`scheduleBuffer` completion-handler at `.dataRendered`; read the interval in
Instruments' Audio System / Points of Interest track. Anything above ~2 ms of
main-thread work in the first segment means something is decoding on the tap
(the bug this design exists to prevent).

Dependency-free, as required: `AVFoundation` only. No SPM audio packages.

---

## 5. Behaviour notes

### 5.1 `onPointerDown` → SwiftUI (the invariant-1 trap)

SwiftUI's `.onTapGesture` fires on touch-**up**. `Button`'s action fires on
touch-up-inside. Using either would move every sound in this app tens to
hundreds of milliseconds later and would break invariant 1 in a way that no test
catches and every child feels.

`Sources/ALUI/Interaction/PressDown.swift` must provide:

```
.onPressDown { ... }   // fires exactly once per touch, at touch-down
```

implemented with `DragGesture(minimumDistance: 0)`: `onChanged` fires on the
initial touch-down; latch a `@GestureState`/`@State` `fired` flag so drag
movement doesn't re-fire; clear on `onEnded`. (`.onLongPressGesture(minimumDuration: 0, pressing:)`
is an acceptable alternative; `simultaneousGesture` is needed if the tile is
also inside a scroll view.) The press/shake WAAPI animation and the SFX must be
triggered from the *same* closure, before any `@State` mutation, mirroring
`Tile.handle`.

`[touch-action:none]` on the web tiles has no analogue and needs none.

### 5.2 The voice channel — `say()` / `stop()`, ported exactly

Swift shape:

```swift
protocol AudioFeedback: AnyObject {
    func unlock()
    func pop(); func success(); func nudge(); func oops()
    @discardableResult
    func say(_ text: String, rate: Double, pitch: Double) async -> Bool   // defaults 0.94 / 1.1
    func stop()
}
```

`Promise<boolean>` → `async -> Bool`. **It never throws and never hangs** — that
is the whole contract, and callers gate the next step on the `Bool`
(`if (!ok || !mountedRef.current) return;`).

State machine, identical to `useAudio.ts`:

1. `say()` first calls `interruptCurrent(hard: true)` — clears timers, detaches
   handlers, hard-stops the current clip, cancels TTS, and **resolves the pending
   continuation `false` before cancelling TTS** (so a cancel-triggered `didFinish`
   cannot sneak in a spurious `true`).
2. Bump the ticket. Every engine callback checks `ticket == current` before
   settling; a stale callback is dropped.
3. Baked clip path (`ClipBank.resolve(text) != nil`): schedule the buffer,
   completion `.dataPlayedBack` → settle `true`; decode/schedule failure → settle
   `false`.
4. No baked clip → `speakTts()`.
5. Watchdogs — the only thing that resolves if the engine never reports:
   - `PROVISIONAL_WATCHDOG_MS = 8000` armed immediately, replaced the moment the
     real duration is known (in Swift the duration is known *synchronously* from
     `AVAudioFile.length`, so the provisional window is nearly vacuous — keep it
     anyway for the decode-in-flight case).
   - duration-derived: `duration * 1000 + WATCHDOG_MARGIN_MS (800)`.
   - TTS: `max(TTS_MIN_MS 1200, text.count * TTS_MS_PER_CHAR 90) / rate + 800`.
     `text.length` in JS is UTF-16 units; use `text.utf16.count`.
   - `TTS_HEARTBEAT_MS = 5000` is a **Chrome-only workaround** (pause/resume to
     stop Chrome parking a long utterance). `AVSpeechSynthesizer` has no such
     bug. **Do not port the heartbeat.** It is the one piece of this file that is
     browser-specific rather than behavioural.
6. `stop()`: `interruptCurrent(hard: false)` — settle pending `false` — then ramp
   the voice mixer from its current volume to 0 in **10 steps of 20 ms** (200 ms
   total), then stop the node, reset, and restore volume to 1 for the next
   exercise. Reproduce the step count and interval literally; a smooth
   `AVAudioMixerNode` ramp would be *nicer* and is therefore out of scope.
   TTS has no mid-utterance volume on either platform: it is simply cancelled
   (`.immediate`).
7. A pending fade must be cancelled when a new `say()` starts (`fadeRef` clear +
   `el.volume = 1`), or the next line plays under a decaying gain.

**`rate` and `pitch` apply to the TTS path only.** The web never sets
`HTMLAudioElement.playbackRate`; `say(x, { rate: 0.98 })` on a baked clip plays
the clip at 1.0. 41 of the 53 call sites pass `rate: 0.98`, and every one of them
hits a baked clip — so in practice `rate` is dead except for the balance
read-out. Applying rate to clips would change the pitch of every success line in
the game. Do not.

### 5.3 SFX — the exact synthesis

`blip(freq, dur, type, gain, at)` from `useAudio.ts`:

```ts
const t0 = ctx.currentTime + at;
osc.type = type; osc.frequency.setValueAtTime(freq, t0);
g.gain.setValueAtTime(0.0001, t0);
g.gain.exponentialRampToValueAtTime(gain, t0 + 0.008);
g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
osc.start(t0); osc.stop(t0 + dur + 0.02);
```

Web Audio's exponential ramp is `v(t) = v0 · (v1/v0)^((t−t0)/(t1−t0))`. Render
exactly that:

```
env(t) = 0.0001 · (gain/0.0001)^(t/0.008)                for 0 ≤ t < 0.008
       = gain   · (0.0001/gain)^((t−0.008)/(dur−0.008))  for 0.008 ≤ t < dur
       = 0.0001                                          for dur ≤ t < dur+0.02
sample(t) = wave(2π·freq·t) · env(t)
```

The four kinds, mixed into one buffer each (sum the component blips; each blip's
oscillator starts at phase 0 at its own offset):

| Kind | Blips `(freq, dur, wave, gain, at)` | Buffer length |
|---|---|---|
| `pop` | `(660, 0.09, triangle, 0.16, 0)` | 0.110 s |
| `success` | `(523.25, 0.16, sine, 0.16, 0.000)`, `(659.25, …, 0.075)`, `(783.99, …, 0.150)`, `(1046.5, …, 0.225)` | 0.405 s |
| `nudge` | `(196, 0.14, sine, 0.10, 0)` | 0.160 s |
| `oops` | `(392, 0.18, sine, 0.13, 0)`, `(311.13, 0.28, sine, 0.13, 0.16)` | 0.460 s |

Peak sum for `success` ≈ 0.5 — no clipping, no limiter needed. `triangle` is the
only non-sine: Web Audio's built-in triangle is band-limited; a naïve triangle at
660 Hz has odd harmonics falling as 1/n², all inaudibly small above Nyquist, so a
naïve triangle is acceptable — **but generate it as `2/π · asin(sin(θ))`, not a
ramp-fold**, and if the pop ever sounds "buzzier" than the web, that is why.

Semantic notes worth keeping in the Swift doc comments:
- `nudge()` is **not a haptic** despite the name (§5.5). It is a soft low sine —
  "soft, non-punishing" per the source. It is the entire wrong-answer penalty
  (invariant 3).
- `oops()` is the two-note falling "wah-wah" that pairs with the « Oh non ! On
  recommence. » line for a completed-but-wrongly-ordered row.
- **During `MISS_COOLDOWN_MS` (800 ms) picks are swallowed *before* `pop()`** —
  the tap is visually acknowledged (`Tile` still runs press + shake because the
  verdict is `reject`) but **silent**. This asymmetry is deliberate anti-farming
  (invariant 8) and must survive the port.

### 5.4 `say()` call-site inventory, and what is actually baked

53 sites. Grouped by what they pass:

| Shape | Baked? |
|---|---|
| `"Bravo ! Tu as tout réussi !"` / `"…trouvé !"` / `"Oh non ! On recommence."` | ✅ |
| `` `Trouve la première lettre de ${word}.` `` | ✅ (per word) |
| `` `Oui ! ${letter}. ${word}.` ``, `` `Oui ! ${word}.` ``, `letterMatchSuccess`, `soundSuccess`, `findSoundSuccess`, `gridSuccess`, `twinSuccess` | ✅ |
| `READ_IMAGE_PROMPT`, `LETTER_MATCH_PROMPTS[*]`, `soundPrompt`, `findSoundPrompt`, `gridPrompt`, `twinPrompt` | ✅ |
| bare `word.word`, `t.syllable`, `letter`, `face.base`, `choice.sound`, `tile.sound` (tile previews) | ✅ (preview group) |
| `shopCostLine(cost)`, `` `${shopCostLine(cost)} ${SHOP_NEED_MORE}` ``, `SHOP_BOUGHT`, `SHOP_GREW` | ✅ |
| **`String(profile.balance)`** — `App.tsx:44`, `{ rate: 0.85 }` | ❌ **the only un-baked line in the app** |

So `AVSpeechSynthesizer` is reachable through exactly one product path: tapping
the score to hear it read out (`« quarante-deux »`, deliberate big-number reading
practice — see the comment at `App.tsx:35`). Plus its real job: the safety net if
a clip file is ever missing or corrupt, which is what keeps invariant 3 true.

### 5.5 Haptics: there are none today

I grepped `src/` and `scripts/` for `vibrate`, `Haptic`, `haptic`, and the
Capacitor haptics plugin. **Zero hits.** The PWA ships no haptic feedback of any
kind; `nudge()` is an audio blip whose name suggests otherwise.

Therefore, for a frozen-behaviour port: **wire `NoopHaptics` and ship no
haptics.** `HapticsPort` exists so the decision can be made later without
touching the exercises.

If and when it *is* made, the mapping I'd propose (and which must not be
implemented on this pass):

| Event | API |
|---|---|
| `pop()` (any accepted tap) | `UIImpactFeedbackGenerator(style: .light)`, `.prepare()` on round load |
| `nudge()` (wrong tap) | `UIImpactFeedbackGenerator(style: .soft)` — **not** `.error`: invariant 3 says a wrong tap is not a failure |
| `oops()` (wrong row) | `UINotificationFeedbackGenerator().notificationOccurred(.warning)` |
| `success()` | `.notificationOccurred(.success)` |

`UIFeedbackGenerator`, not CoreHaptics: CoreHaptics buys custom envelopes we have
no design for, needs `CHHapticEngine` lifecycle management alongside the audio
engine, and is unavailable on devices without a Taptic Engine (all iPads). The
`UIFeedbackGenerator` family degrades to nothing on unsupported hardware, which
is exactly right here.

### 5.6 `speechSynthesis` → `AVSpeechSynthesizer`

Like-for-like, still reachable (§5.4), so it must be ported.

| Web | Swift |
|---|---|
| `new SpeechSynthesisUtterance(text)` | `AVSpeechUtterance(string: text)` |
| `u.lang = "fr-FR"` | `utterance.voice = AVSpeechSynthesisVoice(language: "fr-FR")` (or the scored pick below) |
| `u.rate = rate` (1.0 = normal) | `utterance.rate = AVSpeechUtteranceDefaultSpeechRate * Float(rate)`, clamped to `[AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate]`. **`AVSpeechUtterance.rate` is not a 1.0-normal scale** — the default constant is 0.5. Assigning `0.94` directly would produce near-maximum gabble. |
| `u.pitch = pitch` | `utterance.pitchMultiplier = Float(pitch)` (both 1.0 = normal; AVSpeech range 0.5…2.0, web 0…2 — clamp) |
| `u.onend` / `u.onerror` | `AVSpeechSynthesizerDelegate.didFinish` / `didCancel` |
| `speechSynthesis.cancel()` | `synth.stopSpeaking(at: .immediate)` |
| warm-up utterance in `unlock()` (`" "`, volume 0) | Not needed; iOS has no autoplay gesture requirement. Drop it. |
| `voiceschanged` listener | Not needed; `AVSpeechSynthesisVoice.speechVoices()` is stable per launch. Re-query on `AVSpeechSynthesisVoice.currentLanguageCodeChanged`? No — the web does not, and language is fixed `fr`. |

Voice scoring — port the *intent* of `voiceScore`, expressed in iOS terms:

```
candidates = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("fr") }
score:  +5  if quality == .premium
        +4  if quality == .enhanced          // web: /enhanced|premium|neural|siri/ → +5
        +1  if language == "fr-FR"           // web: /^fr-FR/ → +1
pick highest; nil if the list is empty (then fall back to AVSpeechSynthesisVoice(language: "fr-FR"))
```

The web's `+3` name list (Amélie, Thomas, Aurélie…) and `−5` compact/eSpeak
penalty are both proxies for "is this the enhanced download?" — on iOS `quality`
answers that directly, so the name heuristics disappear and the ranking is
preserved. `localService === false` (+2 for network voices) has **no iOS
equivalent and must not get one**: any server-side TTS would send a child's
progress number to a third party. See §6/invariant 10.

**Do not touch Personal Voice** (`AVSpeechSynthesizer.requestPersonalVoiceAuthorization`).
It prompts, it is a privacy surface, and it has no analogue in the PWA.

### 5.7 Confetti

`useConfetti.ts` verbatim, with two deliberate transformations.

Model (`ALCore/Confetti/ConfettiSystem.swift`), constants unchanged:

```
fire():  90 particles
  a  = rand()·π − π                       // uniform in [−π, 0] → upward hemisphere
  sp = 4 + rand()·7
  x  = width/2 + (rand()−0.5)·120
  y  = height·0.42
  vx = cos(a)·sp
  vy = sin(a)·sp − 3
  g  = 0.22
  r  = 5 + rand()·6
  rot= rand()·6.28
  vr = (rand()−0.5)·0.4
  life = 1
  color = COLORS[Int(rand()·6)]           // #FF8A65 #FFD54F #4FC3F7 #AED581 #BA9EE8 #F06292

step():  per particle, in order
  vy += g; x += vx; y += vy; rot += vr; life -= 0.008
  remove if life <= 0 || y > height + 40
draw():  alpha = max(0, life); translate(x,y); rotate(rot); fill rect(−r/2, −r/2, r, r·0.6)
```

**Transformation 1 — drop every `dpr` factor.** The web multiplies positions,
speeds, gravity and sizes by `devicePixelRatio` because it draws into the
backing store in device pixels while `canvas.width = offsetWidth * dpr`. Divide
the whole system by `dpr` and every factor cancels: the model is already in CSS
pixels ≡ SwiftUI points. Translating the `* dpr` terms literally would make
confetti 2–3× too fast and too large on every real device. `width`/`height`
passed to `fire()`/`step()` are the **point** size of the overlay.

**Transformation 2 — lock the step to 60 Hz.** The rAF loop is frame-rate
dependent: `life -= 0.008` per *frame*, so a burst lives 125 frames ≈ 2.08 s at
60 Hz. `TimelineView(.animation)` ticks at the display's rate, which is 120 Hz on
ProMotion — the confetti would fall and fade twice as fast on an iPhone Pro or
iPad Pro. Drive the model from an accumulator:

```
elapsed = date − lastDate ; accumulator += elapsed
while accumulator >= 1.0/60.0 { system.step(); accumulator -= 1.0/60.0 }
```

Clamp `elapsed` to ≤ 0.25 s so returning from background doesn't run 600 steps in
one frame.

View (`ALUI/Confetti/ConfettiOverlay.swift`):

```
TimelineView(.animation(minimumInterval: 1.0/60.0, paused: !running)) { ctx in
    Canvas { gc, size in  ...draw system.particles... }
}
.allowsHitTesting(false)
```

`paused: !running` reproduces `rafRef.current = parts.length ? requestAnimationFrame(loop) : 0`
— the loop genuinely stops when the last particle dies, costing nothing between
celebrations. `running` flips **twice per burst** (true on `fire()`, false when
the system drains), never per frame → invariant 2 holds.

Reduced motion: `@Environment(\.accessibilityReduceMotion)`; when true, `fire()`
returns immediately without emitting, exactly like
`if (!canvas || reduced.current) return;`. (Difference: the web caches the media
query at mount, SwiftUI re-reads it live. Strictly better, behaviourally
invisible.)

Placement: the web canvas sits at `zIndex: 40` with the exercise content at
`z-[41]`, i.e. **behind** the content, full-bleed inside the rounded `GameFrame`.
Port as a `ZStack` layer under the content, `.clipShape(RoundedRectangle(…))`.

### 5.8 `unlock()`

A web autoplay-policy artifact: create/resume the `AudioContext` and warm the
speech engine on the first user gesture. It is called on **26** sites including
every `pick`.

Native mapping: **idempotent "make sure we can make noise now"** — activate the
`AVAudioSession` if it is inactive and start the `AVAudioEngine` if it is not
running. After the first call it is a `Bool` check and returns. It must stay
allocation-free and syscall-free in the steady state, because it sits on the
pointer-down path 26 times over.

Keep the call sites. They are the natural recovery point after an interruption
(§6.3) — a child who taps a tile after taking a phone call gets sound back.

### 5.9 Lifecycle: one engine, not one per exercise

`useAudio` creates an `AudioContext` per exercise mount and closes it on unmount,
because "a long session can't leak contexts until the browser caps them and the
chimes go silent" — a browser resource limit, not a behaviour.

Native: **one `LiveAudioFeedback` for the app lifetime**, injected through the
environment. Starting an `AVAudioEngine` costs 10–30 ms; doing it on exercise
entry would risk a silent first tap. What *must* be preserved per-exercise is the
teardown semantics of `useEffect(() => () => audio.stop(), [audio])`: on exercise
disappear, call `stop()` (settle-false + 200 ms fade). That is 9 call sites →
`.onDisappear { audio.stop() }`.

The corollary the ui-components agent needs: the `audio` object identity must be
**stable** (the web goes out of its way — `useMemo` over seven `useCallback`s —
because "FirstLetterExercise's announce effect depends on `say`; a fresh object
each render would re-fire it on every tap"). In SwiftUI the equivalent hazard is
a `.task(id:)` whose id includes the audio object. Key announce tasks on `idx`
only.

### 5.10 The 350 ms announce delay

Six exercises do:

```ts
const t = window.setTimeout(() => void audio.say(prompt), 350);
return () => window.clearTimeout(t);
```

SwiftUI: `.task(id: idx) { try? await Task.sleep(for: .milliseconds(350)); guard !Task.isCancelled else { return }; await audio.say(prompt) }`.
Task cancellation on `id` change is the `clearTimeout`. (The web comment notes
this also collapses StrictMode's double-mount to one announce; SwiftUI has no
StrictMode, but `.task(id:)` re-entry has the same shape.)

---

## 6. Invariant ownership

| # | Invariant | Where it is enforced in this design | What breaks it |
|---|---|---|---|
| **1** | Feedback fires on pointerdown, before React commits | `ALUI/Interaction/PressDown.swift` (touch-down gesture) + `GameAudioEngine`'s always-running player nodes with **pre-rendered** SFX buffers. No decode, no file I/O, no `prepareToPlay` on the tap path. | Using `.onTapGesture` / `Button` (touch-up). Lazily creating a player node. Decoding a clip synchronously in `say()`. Putting `unlock()` behind an `await`. |
| **2** | Animation stays off the render path | Confetti = `TimelineView` + `Canvas` over a plain reference-type model; `ConfettiController` publishes exactly twice per burst. Press/shake is the ui-components agent's, but must not be `withAnimation` on shared state. | Making `ConfettiSystem` `@Observable`, or storing particles in `@State`. |
| **3** | No fail state | `say()` returns `Bool` and **cannot throw or hang**: three watchdogs, settle-once tickets, TTS fallback, and a `false` result on any engine error. A missing/corrupt clip degrades to the OS voice; a dead OS voice degrades to silence + `false`, never to a stuck round or an error UI. `nudge()` stays the soft 196 Hz sine. | Making `say` `throws`. Surfacing a decode error to the UI. Replacing `nudge` with an error haptic/sound. |
| **6** | Accessibility floor — reduced motion | `ConfettiOverlay` reads `\.accessibilityReduceMotion` and `fire()` no-ops when set. | Firing confetti unconditionally; animating the overlay's opacity outside the check. |
| **8** | Farming never pays | The audio layer's share: the `MISS_COOLDOWN_MS = 800` gate sits **before** `pop()`, so spam taps during the shake window are silent. The sequence engines pace retries by `await`-ing the « Oh non ! On recommence. » line to completion before re-enabling taps. | Moving `pop()` above the cooldown check ("so the tap feels responsive"). Not awaiting the oops line before resetting the tray. |
| **10** | Nothing identifying leaves the device | **No audio path may make a network request.** The bank is in-bundle (that is the ODR argument in §2.1); `AVSpeechSynthesizer` synthesises on device; the web's `localService === false` (+2 for *network* voices) is explicitly **not** ported. The only text ever spoken that derives from profile data is `String(profile.balance)`. | Adding a cloud TTS "for better French". Adopting ODR. Logging spoken text to telemetry (the allowlist has no string escape hatch — keep it that way). |

Invariants 4, 5, 7, 9, 11 are out of this scope.

---

## 7. Test plan

### 7.1 Host (`swift test`, no simulator) — ALCoreTests

**`VoKeyTests`**
- Golden vector: a checked-in JSON fixture of all 845 `(text, key)` pairs, produced
  once from the JS implementation (`node -e` over `enumerateUtterances()` +
  `enumeratePreviewUtterances()`), asserted element-wise. This is the single test
  that makes the whole bank valid; without it a hash divergence is invisible until
  a child hears a robot.
- Normalisation: decomposed `"E\u{0301}"` and composed `"É"` produce the same key.
- Whitespace: `" Oui !  A. "` == `"Oui ! A."`.
- Wrap-around: a fixed adversarial string whose intermediate hash exceeds 2³²
  (guards against `*` instead of `&*`).
- Radix: keys match `^[0-9a-z]+$`.

**`ClipBankTests`**
- **Coverage:** every text in `enumerateUtterances() ∪ enumeratePreviewUtterances()`
  resolves to an existing file. Expected `845/845` — the Swift-side `--list=all`.
- **No collisions:** 845 distinct texts → 845 distinct keys.
- **No orphans:** files under `Clips/` == catalog size (currently exactly equal;
  assert as a warning-free equality so a stale clip is caught at port time).
- Extension preference: with `x.m4a` and `x.wav` both present, `m4a` wins.
- Negative: an unbaked string (`"1234"`) resolves to `nil` — this is the
  TTS-fallback contract.

**`UtterancesTests`**
- Byte-for-byte French copy: assert the three fixed celebration lines and the
  shop lines as literals, including the space before `!`.
- `shopCostLine(1) == "Ça coûte 1 étoile."` and `shopCostLine(3) == "Ça coûte 3 étoiles."` (singular/plural).
- Port of `preview.test.ts` wholesale: every `SYLLABLE_BANK` entry present; every
  first letter / spelling grapheme / intruder present; **every preview text is
  `== text.uppercased()`**.

**`VoiceChannelTests`** — with stub `ClipPlayback` / `SpeechPlayback` and a fake
clock; this is `useAudio.test.ts` ported one-for-one:
- resolves `true` when the clip reports completion;
- a second `say()` resolves the first `false` (no overlap);
- a playback error resolves `false`;
- `stop()` resolves in-flight `false` and is safe when idle;
- duration-derived watchdog fires at `duration + 800 ms` → `false`;
- provisional watchdog fires at 8 000 ms when duration never arrives;
- unbaked text takes the speech path and resolves `true` on `didFinish`;
- **`rate`/`pitch` are not applied to the clip path** (assert the stub player is
  never asked to change rate);
- a stale engine callback after supersede does not settle the new ticket.

**`SfxSynthTests`**
- Frame counts at 48 kHz: pop 5 280, success 19 440, nudge 7 680, oops 22 080
  (= duration × 48 000).
- Peak |sample| ≤ 0.16 (pop/success components), ≤ 0.10 (nudge), ≤ 0.13 (oops);
  `success` summed peak < 1.0 (no clipping).
- Envelope: value at t=0 ≈ 0.0001·wave, at t=0.008 ≈ gain, monotone rising then
  monotone falling.
- Determinism: two renders are bit-identical.

**`ConfettiSystemTests`** — with a seeded RNG:
- `fire()` emits exactly 90 particles; all within the documented ranges; colours
  drawn only from the 6-entry palette.
- `life` reaches 0 after 125 steps; the system is empty at ≤ 126 steps.
- A particle past `height + 40` is removed even with `life > 0`.
- **Frame-rate independence:** driving the accumulator with 120 Hz timestamps and
  with 60 Hz timestamps yields identical particle state at t = 1.0 s. This is the
  test that catches the ProMotion bug.
- Reduced motion `true` → `fire()` emits 0 particles.
- `fire()` during an active burst appends (count 180), does not reset.

### 7.2 Simulator (XCUITest / manual) — cheap but not `swift test`

- A tile tap plays a sound and shows the press animation (smoke).
- Leaving an exercise mid-line fades out over ~200 ms.
- Confetti draws and drains.
- Reduce Motion on → no confetti.

### 7.3 Device only — cannot be automated, must be on the checklist

- Tap→sound latency (Instruments signposts, §4.5).
- Silent/ring switch behaviour (the simulator has no switch).
- Music playing in another app → duck/resume.
- Incoming call → interruption began/ended.
- Headphones unplugged / AirPods removed → route change.
- Bluetooth latency sanity check (expect it to be bad; confirm it is not *broken*).
- The audition pass CLAUDE.md insists on: **listen** to the clips in the native
  build. A port cannot introduce a mispronunciation, but it can introduce a
  resample artifact (24 kHz → 48 kHz through the mixer).

---

## 8. Audio session (a decision, not a port)

The PWA inherits WKWebView's defaults under Capacitor, which is effectively
`AVAudioSessionCategorySoloAmbient`: **muted by the ring/silent switch**, and it
stops other apps' audio. Nobody chose that; it is what a WebView does. The native
port has to choose.

**Recommendation:**

```swift
try session.setCategory(.playback, mode: .default, options: [.duckOthers])
try session.setPreferredIOBufferDuration(0.005)
try session.setActive(true)
```

and on backgrounding:

```swift
try session.setActive(false, options: .notifyOthersOnDeactivation)
```

with reactivation on foreground (plus `unlock()` as the belt-and-braces on the
next tap).

**Why `.playback`.** The voice-over *is* the exercise: « Trouve la première
lettre de Ballon » is the question. A six-year-old cannot diagnose "the ring
switch on the side of Mum's phone is flipped" — from their seat the game is
simply broken, and there is no fail state to explain it to them. Apple's own
guidance is to use `.playback` when audio is essential to the app's function,
which is unambiguously the case. Volume is still under the physical volume
buttons, where a parent expects it.

**Why `.duckOthers` and not `.mixWithOthers`.** With `.mixWithOthers` the child
would be trying to distinguish `/ba/` from `/da/` under someone's podcast.
With plain `.playback` (no options) the other app is *stopped* — harsher than the
PWA. Ducking is the middle: music drops while the line plays and comes back. This
is the only place where I recommend behaviour that differs from the current build,
and it differs in the direction of "less destructive to the rest of the phone".

### What the child experiences

| Situation | With `.playback` + `.duckOthers` |
|---|---|
| **Ring switch on silent** | The game is fully audible. (Changed from today, deliberately.) |
| **Music playing in another app** | Music ducks for the ~2 s line, then returns to full. Nothing is stopped. |
| **Phone call arrives** | `interruptionNotification .began`: the system stops our engine. We settle the in-flight `say()` `false` (identical to `stop()`), leave the round exactly where it was, award nothing, advance nothing. On `.ended` with `.shouldResume` we reactivate the session and restart the engine; **we do not auto-replay the prompt** — the child taps the on-screen 🔊 « Écouter » button, which every exercise already has. |
| **Headphones/AirPods removed** | `routeChangeNotification` `.oldDeviceUnavailable`. **Recommendation: keep playing on the speaker; do not pause.** The iOS convention (pause on unplug) exists for media apps where continuing is embarrassing; here pausing would settle the success line `false` mid-celebration and can soft-lock the round (§9.1). A two-second French word on a speaker is not a privacy event; a stuck exercise is a broken toy. |
| **Another app takes the session (Siri, alarm)** | Same path as the call. |
| **App backgrounded mid-line** | `setActive(false, .notifyOthersOnDeactivation)` — the line is cut, `say()` settles `false`, other apps' music resumes. No background audio mode is declared; the game must not play while backgrounded. |

`AudioSessionPort` (ALCore) declares `activate/deactivate` plus an
`AsyncStream<AudioEvent>`; `App/AudioSessionLive.swift` is the only file that
touches `AVAudioSession`. `LiveAudioFeedback` consumes the stream: `.began` →
`voiceChannel.interrupt()`; `.ended(shouldResume: true)` → `activate()` +
`engine.start()`; `.routeChange(.oldDeviceUnavailable)` → **nothing**.

---

## 9. Risks

### 9.1 A cut success line soft-locks the round — this already exists, port it as-is

In every exercise the celebration is:

```ts
locked.current = true;
audio.success(); fire();
void (async () => {
  const ok = await audio.say(`Oui ! …`, { rate: 0.98 });
  if (!ok || !mountedRef.current) return;   // ← locked.current stays TRUE forever
  setFlash(null); locked.current = false; …
})();
```

If the line is superseded, errors, or a watchdog trips, the round never advances,
tiles stay `disabled={flash != null}`, and the only exit is « ← Menu ». On the web
this is rare (the clip is local, `ended` is reliable). Natively, **every
interruption source in §8 lands here**: a call, an alarm, backgrounding, a route
change if we paused on it. That is why §8 recommends *not* pausing on route
change, and why the interruption handler must not fabricate a `false` settle when
the line has in fact finished.

Behaviour is frozen, so: **port the logic unchanged, and choose interruption
policies that don't walk into it.** If the product later wants a fix, the minimal
one is `defer { locked.current = false }` — but that is a behaviour change and
belongs in a decision, not in this port. It is the single oddest thing I found.

### 9.2 Frame-rate dependence (ProMotion)

Covered in §5.7. Called out again because it is the one place a faithful literal
translation produces visibly wrong output on the most expensive hardware the
customer owns. The 60 Hz accumulator is not an optimisation; it is the port.

### 9.3 `AVSpeechSynthesizer` and `AVAudioEngine` sharing a session

The synthesizer manages its own audio unit and can transiently affect the session
configuration. In practice they coexist, but the sequencing in `interruptCurrent`
(resolve the continuation *before* `stopSpeaking`) must be preserved or a
`didCancel` will race a `didFinish` and settle the wrong ticket — exactly the bug
the web comment warns about. Test with the stub (§7.1) and once on device.

### 9.4 React semantics with no clean SwiftUI equivalent

- **Stable callback identity.** `useAudio` returns a `useMemo`'d object precisely
  so an announce effect doesn't re-fire on every tap. SwiftUI has no dependency
  array; the analogue is `.task(id:)` keyed on `idx` alone. If someone keys on
  the audio object or on `round`, prompts will re-announce mid-round. Documented
  in §5.9 and worth a review comment in every exercise.
- **`useEffect` cleanup ⇒ `onDisappear` is not exactly the same moment.** SwiftUI
  may keep a view alive across a navigation animation; the 200 ms fade could
  start later than the web's unmount. Acceptable; note it if the fade ever
  sounds late.
- **StrictMode double-mount** has no analogue — the `clearTimeout` guards written
  for it are still correct, just less load-bearing.
- **`performance.now()`** → a `MonotonicClock` protocol (`CACurrentMediaTime()`),
  injectable so the 800 ms cooldown is host-testable.
- **`Promise<boolean>` that never rejects** → `async -> Bool` that never throws.
  Swift's instinct is `async throws`; resist it. Every call site is
  `if !ok { return }`, and a thrown error would need a `try?` at 53 sites.

### 9.5 Resample artifacts

Clips are 24 kHz; hardware is 44.1/48 kHz. The engine's mixer resamples. Quality
should be fine, but 24 kHz AAC speech through an SRC is exactly the kind of thing
that gains a slight sibilance. Listen once, and if it matters, pre-convert the
voice node's format rather than reaching for a library (dependency-free, per D0).

### 9.6 The dev VO gallery (`#vo`)

237 lines of dev tooling reachable by URL hash, including `fetch(url, {method:"HEAD"})`
for bake times and a `localStorage` reject list. There is no URL hash in a native
app. **Recommendation: do not port it in this phase.** The bake-and-listen loop
stays in the PWA, where the generator lives. If a native audition bench is wanted
later it is a debug-menu screen, and the `Last-Modified` HEAD trick simply
becomes file mtime.

### 9.7 Clip bank duplication between the two repos

The PWA and the Swift package would each hold 13.7 MiB of identical `.m4a`. Git
would carry both. Options: (a) copy, dumb and safe; (b) a symlinked directory —
SwiftPM does not follow symlinked resource directories reliably; (c) a
`Package.swift` prebuild plugin that copies from `../../../src/vo/clips`, which
breaks a standalone checkout. **Recommend (a) plus a `make sync-clips` script and
a CI check that the Swift bundle's key set equals `--list=all`.** The coverage
test in §7.1 catches drift on the Swift side; the script prevents it.

### 9.8 App size

+13.7 MiB uncompressed AAC in the binary. AAC does not compress further in the
IPA, so expect ~+13.7 MiB download. Under every App Store cellular threshold and
irrelevant next to the alternative (ODR) failing offline. No action.

---

## 10. Decisions that belong above this scope

1. **`AVAudioSession` category.** §8 recommends `.playback` + `.duckOthers`,
   which changes silent-switch behaviour versus today's Capacitor build. It
   affects the whole app (shop, hub, onboarding), touches Kids-category
   expectations about a parent's control of the device, and should be recorded as
   a `D`-entry in `DECISIONS.md`, not decided inside the audio layer.
2. **Haptics: ship none, or add them?** The PWA has zero. Adding them is a
   product change with a real upside for a 6-year-old's tap confidence. §5.5 has
   the mapping ready; the seam exists either way. Needs an explicit yes/no.
3. **The soft-lock in §9.1.** Frozen behaviour says port it. Native interruptions
   make it far more reachable than on the web. Somebody senior should look at it
   and either accept it or open it as a post-port fix — it should not be silently
   "improved" during the port, and it should not be silently shipped either.
4. **Where the clip bank physically lives** (§9.7) — copy vs. shared source of
   truth — is a repo-topology call shared with whoever owns the build.
5. **`.onPressDown` ownership** (§5.1). Invariant 1 depends on one shared gesture
   modifier used by every interactive surface in the app. It is specified here
   but must be implemented once, in `ALUI/Interaction/`, and adopted by the
   ui-components agent's `Tile`, the shop, and the hub — not reinvented per view.
6. **`RGB8` / colour representation in ALCore.** The confetti palette needs a
   SwiftUI-free colour type; so will the mascot and tile palettes. One shared
   `ALCore` colour value type, defined once.
7. **`MonotonicClock`** is needed here (cooldown) and by the exercise engines.
   One protocol, one owner.
