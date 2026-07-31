import { getItem, setItem } from "./kv";
import type { ExerciseId } from "./types";

/* -------------------------------------------------------------------------- */
/* First-party telemetry. Our server, no SDK, no vendor.                        */
/*                                                                             */
/* Kids Category guideline 1.3 bans sending personally identifiable information */
/* OR DEVICE INFORMATION to THIRD PARTIES. Both halves matter: "third party" is */
/* why this posts to our own endpoint instead of PostHog or Firebase, and       */
/* "device information" is why there is no SDK — every drop-in analytics client */
/* ships device model, OS, locale, screen size and an install id by default.    */
/*                                                                             */
/* What leaves the device, ever:                                                */
/*   • an event name from a closed list                                         */
/*   • numbers, and exercise ids from a closed list                             */
/*   • the app version                                                          */
/* That is all. No device id (see device.ts), no child id, no name, no free     */
/* text, no timestamps of the child's day. The payload is genuinely anonymous   */
/* rather than merely pseudonymous, which is what lets the privacy policy say   */
/* so plainly and the Data Safety form stay nearly empty.                       */
/* -------------------------------------------------------------------------- */

declare const __APP_VERSION__: string;

const CONSENT_KEY = "attrape-lettres:consent:v1";
/** Read lazily, not at module load, so tests and builds can vary it. */
function endpoint(): string | undefined {
  return import.meta.env.VITE_TELEMETRY_URL as string | undefined;
}

/** Events we are willing to record. Anything else is dropped, loudly in dev. */
const EVENTS = [
  "exercise_started",
  "session_completed",
  "shop_opened",
  "item_bought",
  "mascot_grown",
  "trial_started",
  "trial_expired",
  "paywall_shown",
  "purchase_completed",
  "purchase_failed",
  "purchase_restored",
] as const;
export type TelemetryEvent = (typeof EVENTS)[number];

/**
 * The only property names that may be sent, all numeric except `exercise`,
 * which is a closed enum. There is deliberately no `string` escape hatch: a
 * free-text field is how a child's first name ends up on a server.
 */
export interface TelemetryProps {
  exercise?: ExerciseId;
  level?: number;
  rounds?: number;
  perfect?: number;
  points?: number;
  cost?: number;
  stage?: number;
  daysLeft?: number;
}

const NUMERIC_KEYS: (keyof TelemetryProps)[] = [
  "level",
  "rounds",
  "perfect",
  "points",
  "cost",
  "stage",
  "daysLeft",
];

/* -- consent ----------------------------------------------------------------*/

/**
 * Analytics consent, given by the PARENT. France set the GDPR Art. 8 digital
 * consent age at 15, so a six-year-old tapping "Oui" is not consent — which is
 * why the toggle only ever appears on parent-facing screens (Onboarding, and
 * the parental-gated settings so withdrawal is as easy as giving).
 *
 * Device-scoped, never synced: consent belongs to the adult holding this phone,
 * not to the household.
 */
export function hasConsent(): boolean {
  return getItem(CONSENT_KEY) === "1";
}

export function setConsent(on: boolean): void {
  setItem(CONSENT_KEY, on ? "1" : "0");
  if (!on) queue.length = 0;
}

/** Has the parent been asked at all yet? (unset ≠ refused) */
export function consentAnswered(): boolean {
  return getItem(CONSENT_KEY) !== null;
}

/* -- transport --------------------------------------------------------------*/

interface Payload {
  event: TelemetryEvent;
  props: Record<string, number | string>;
}

const queue: Payload[] = [];
let flushing = false;

function sanitize(props: TelemetryProps): Record<string, number | string> {
  const out: Record<string, number | string> = {};
  for (const key of NUMERIC_KEYS) {
    const v = props[key];
    if (typeof v === "number" && Number.isFinite(v)) out[key] = v;
  }
  if (typeof props.exercise === "string") out.exercise = props.exercise;
  return out;
}

function post(path: string, body: unknown): void {
  if (!endpoint()) return;
  const json = JSON.stringify(body);
  const url = `${endpoint()}${path}`;
  try {
    // keepalive so a flush during backgrounding still lands.
    void fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: json,
      keepalive: true,
      // No cookies, no credentials — there is no session to carry.
      credentials: "omit",
    }).catch(() => {
      /* telemetry must never surface to a child */
    });
  } catch {
    /* ignore */
  }
}

/** Send whatever is queued. Called on backgrounding, and before a hard exit. */
export function flushTelemetry(): void {
  if (flushing || queue.length === 0 || !endpoint()) return;
  flushing = true;
  const batch = queue.splice(0, queue.length);
  post("/events", { v: __APP_VERSION__, events: batch });
  flushing = false;
}

/**
 * Record an event, if the parent opted in. Fire-and-forget: never awaited,
 * never blocks a tap, never throws into the game loop.
 */
export function track(event: TelemetryEvent, props: TelemetryProps = {}): void {
  if (!hasConsent() || !endpoint()) return;
  if (!EVENTS.includes(event)) {
    if (import.meta.env.DEV) console.warn(`telemetry: unknown event "${event}" dropped`);
    return;
  }
  queue.push({ event, props: sanitize(props) });
  if (queue.length >= 20) flushTelemetry();
}

/**
 * Crash/error reporting — deliberately NOT consent-gated.
 *
 * The payload carries no identifier of any kind, so it is not personal data and
 * needs no consent. That split is the point: we still hear about the bug that
 * breaks the game for the ~60% of parents who decline analytics.
 *
 * The message and stack are the only free-form values in this file. They are
 * truncated, and NOTHING from app state is ever attached — the roster holds
 * children's first names, and one careless context dump would ship them here.
 */
export function reportError(err: unknown, where = "unknown"): void {
  if (!endpoint()) return;
  const e = err instanceof Error ? err : new Error(String(err));
  post("/errors", {
    v: __APP_VERSION__,
    where: where.slice(0, 60),
    message: (e.message || "").slice(0, 300),
    stack: (e.stack || "").slice(0, 2000),
  });
}

/**
 * Catch what a browser otherwise swallows. There is no crash reporter behind a
 * self-distributed PWA — an uncaught exception just leaves a child looking at a
 * frozen screen — so without this we are blind to our own bugs.
 */
export function installErrorReporting(): void {
  if (typeof window === "undefined") return;
  window.addEventListener("error", (e) => reportError(e.error ?? e.message, "window.onerror"));
  window.addEventListener("unhandledrejection", (e) =>
    reportError(e.reason, "unhandledrejection")
  );
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flushTelemetry();
  });
}
