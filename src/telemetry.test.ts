import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  consentAnswered,
  flushTelemetry,
  hasConsent,
  reportError,
  setConsent,
  track,
  type TelemetryProps,
} from "./telemetry";

interface SentCall {
  url: string;
  body: Record<string, any>;
}

/** Every fetch body this test file caused, parsed. */
function sent(fetchMock: ReturnType<typeof vi.fn>): SentCall[] {
  return fetchMock.mock.calls.map((call) => ({
    url: String(call[0]),
    body: JSON.parse(String((call[1] as RequestInit).body)) as Record<string, any>,
  }));
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  vi.stubEnv("VITE_TELEMETRY_URL", "https://t.test");
  fetchMock = vi.fn(() => Promise.resolve(new Response("", { status: 204 })));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  setConsent(false); // drains the queue between tests
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("consent", () => {
  it("starts unanswered, and unanswered is not consent", () => {
    expect(consentAnswered()).toBe(false);
    expect(hasConsent()).toBe(false);
  });

  it("records a refusal distinctly from never having asked", () => {
    setConsent(false);
    expect(consentAnswered()).toBe(true);
    expect(hasConsent()).toBe(false);
  });

  it("sends nothing at all without consent", () => {
    track("exercise_started", { exercise: "read-image", level: 1 });
    flushTelemetry();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("drops anything already queued the moment consent is withdrawn", () => {
    setConsent(true);
    track("exercise_started", { exercise: "read-image", level: 1 });
    setConsent(false);
    flushTelemetry();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("what a tracked event actually contains", () => {
  beforeEach(() => setConsent(true));

  it("sends the event name, allowlisted numbers and the app version — nothing else", () => {
    track("session_completed", { exercise: "read-image", level: 2, rounds: 8, perfect: 6, points: 12 });
    flushTelemetry();

    const [call] = sent(fetchMock);
    expect(call.url).toBe("https://t.test/events");
    expect(call.body.events).toEqual([
      {
        event: "session_completed",
        props: { level: 2, rounds: 8, perfect: 6, points: 12, exercise: "read-image" },
      },
    ]);
    expect(typeof call.body.v).toBe("string");
  });

  it("silently drops any property not on the allowlist", () => {
    // The failure this guards against: someone adds `{ child: profile.name }`
    // to a call site and ships a six-year-old's first name to a server.
    const sneaky = {
      exercise: "read-image",
      level: 1,
      childName: "Léa",
      deviceId: "dad-phone",
    } as unknown as TelemetryProps;
    track("exercise_started", sneaky);
    flushTelemetry();

    const [call] = sent(fetchMock);
    expect(call.body.events[0].props).toEqual({ level: 1, exercise: "read-image" });
    expect(JSON.stringify(call.body)).not.toContain("Léa");
    expect(JSON.stringify(call.body)).not.toContain("dad-phone");
  });

  it("drops non-finite numbers rather than sending null", () => {
    track("session_completed", { points: NaN, level: 3 });
    flushTelemetry();
    expect(sent(fetchMock)[0].body.events[0].props).toEqual({ level: 3 });
  });

  it("batches, and a flush with an empty queue is a no-op", () => {
    track("shop_opened");
    track("shop_opened");
    flushTelemetry();
    flushTelemetry();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sent(fetchMock)[0].body.events).toHaveLength(2);
  });

  it("carries no cookies or credentials", () => {
    track("shop_opened");
    flushTelemetry();
    const [, init] = fetchMock.mock.calls[0];
    expect((init as RequestInit).credentials).toBe("omit");
  });
});

describe("error reports", () => {
  it("are sent WITHOUT consent — they carry no identifier, so they are not personal data", () => {
    expect(hasConsent()).toBe(false);
    reportError(new Error("boom"), "test");
    const [call] = sent(fetchMock);
    expect(call.url).toBe("https://t.test/errors");
    expect(call.body.message).toBe("boom");
    expect(call.body.where).toBe("test");
  });

  it("truncate a runaway message instead of shipping it whole", () => {
    reportError(new Error("x".repeat(5000)));
    expect(sent(fetchMock)[0].body.message).toHaveLength(300);
  });

  it("accept a non-Error throw without crashing the game", () => {
    expect(() => reportError("plain string")).not.toThrow();
    expect(sent(fetchMock)[0].body.message).toBe("plain string");
  });
});

describe("with no endpoint configured", () => {
  it("is inert — a dev build posts nowhere", () => {
    vi.stubEnv("VITE_TELEMETRY_URL", "");
    setConsent(true);
    track("shop_opened");
    flushTelemetry();
    reportError(new Error("boom"));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
