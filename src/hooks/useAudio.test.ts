import { describe, it, expect, vi, beforeAll, afterAll, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

// Anything not prefixed "tts:" has a baked clip → say() takes the <audio> path;
// "tts:*" has none → it falls back to speechSynthesis. Lets one suite cover both.
vi.mock("../vo/clips", () => ({
  clipUrl: (t: string) => (t.startsWith("tts:") ? undefined : "blob:clip"),
  hasBakedVoice: true,
}));

import { useAudio } from "./useAudio";

// happy-dom won't fire <audio> 'ended' or resolve play() like a browser, so we
// drive completion/error by hand. The hook reuses one element (clipRef), so
// `audios[0]` is the single clip across a test.
let audios: FakeAudio[] = [];
class FakeAudio {
  volume = 1;
  currentTime = 0;
  paused = true;
  duration = NaN;
  src = "";
  onended: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onloadedmetadata: (() => void) | null = null;
  play = vi.fn(() => {
    this.paused = false;
    return Promise.resolve();
  });
  pause = vi.fn(() => {
    this.paused = true;
  });
  constructor() {
    audios.push(this);
  }
  end() {
    this.onended?.();
  }
}

const speech = {
  cancel: vi.fn(),
  speak: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
  speaking: false,
  getVoices: () => [] as SpeechSynthesisVoice[],
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
};

class FakeUtterance {
  onend: (() => void) | null = null;
  onerror: (() => void) | null = null;
  rate = 1;
  pitch = 1;
  lang = "";
  voice: unknown = null;
  constructor(public text: string) {}
}

// Stub once, unstub after ALL tests: testing-library's afterEach cleanup()
// unmounts the hook (whose voiceschanged cleanup touches speechSynthesis), and
// that must still see the stubs — so per-test unstubbing would race it.
beforeAll(() => {
  vi.stubGlobal("Audio", FakeAudio);
  vi.stubGlobal("speechSynthesis", speech);
  vi.stubGlobal("SpeechSynthesisUtterance", FakeUtterance);
  vi.stubGlobal(
    "AudioContext",
    class {
      currentTime = 0;
      resume() {}
      close() {}
    }
  );
});

afterAll(() => vi.unstubAllGlobals());

beforeEach(() => {
  audios = [];
  vi.clearAllMocks();
});

afterEach(() => vi.useRealTimers());

describe("useAudio — single-flight voice channel", () => {
  it("say() resolves true when the clip plays to its end", async () => {
    const { result } = renderHook(() => useAudio());
    let p!: Promise<boolean>;
    act(() => {
      p = result.current.say("chat");
    });
    act(() => audios[0].end());
    await expect(p).resolves.toBe(true);
  });

  it("a new say() supersedes the in-flight line, resolving it false (no overlap)", async () => {
    const { result } = renderHook(() => useAudio());
    let first!: Promise<boolean>;
    act(() => {
      first = result.current.say("chat");
    });
    act(() => {
      void result.current.say("chien"); // latest intent wins
    });
    await expect(first).resolves.toBe(false);
  });

  it("resolves false on a media error, so a decode failure never hangs the round", async () => {
    const { result } = renderHook(() => useAudio());
    let p!: Promise<boolean>;
    act(() => {
      p = result.current.say("chat");
    });
    act(() => audios[0].onerror?.());
    await expect(p).resolves.toBe(false);
  });

  it("stop() resolves the in-flight line false and is safe to call when idle", async () => {
    const { result } = renderHook(() => useAudio());
    let p!: Promise<boolean>;
    act(() => {
      p = result.current.say("chat");
    });
    act(() => result.current.stop());
    await expect(p).resolves.toBe(false);
    expect(() => act(() => result.current.stop())).not.toThrow();
  });

  it("the watchdog resolves false if the clip stalls (duration known, 'ended' never fires)", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useAudio());
    let p!: Promise<boolean>;
    act(() => {
      p = result.current.say("chat");
    });
    // Report a 2s clip; then let its duration-derived watchdog elapse silently.
    act(() => {
      audios[0].duration = 2;
      audios[0].onloadedmetadata?.();
    });
    act(() => {
      vi.advanceTimersByTime(2000 + 1000);
    });
    await expect(p).resolves.toBe(false);
  });

  it("falls back to speechSynthesis when no clip is baked, and still resolves on end", async () => {
    const { result } = renderHook(() => useAudio());
    let p!: Promise<boolean>;
    act(() => {
      p = result.current.say("tts:bonjour");
    });
    expect(speech.speak).toHaveBeenCalledTimes(1);
    const calls = speech.speak.mock.calls;
    const u = calls[calls.length - 1][0] as FakeUtterance;
    act(() => u.onend?.());
    await expect(p).resolves.toBe(true);
  });
});
