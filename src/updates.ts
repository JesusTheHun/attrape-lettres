import { CapacitorUpdater } from "@capgo/capacitor-updater";
import { Capacitor } from "@capacitor/core";
import { App as CapApp } from "@capacitor/app";
import { reportError } from "./telemetry";

/* -------------------------------------------------------------------------- */
/* Live updates — self-hosted.                                                  */
/*                                                                             */
/* WHAT THIS IS ALLOWED TO DO, and it is not a loophole: a Capacitor app runs   */
/* its entire web layer inside WKWebView, and the DPLA (§3.3.1(B), the old      */
/* §3.3.2) permits downloaded INTERPRETED code executed by Apple's own WebKit   */
/* or JavaScriptCore. Shipping a new JS/CSS/asset bundle over the air is        */
/* squarely inside that carve-out, which is why live-update tooling has run in  */
/* the open for a decade.                                                       */
/*                                                                             */
/* WHAT IT MUST NEVER DO. Guideline 2.3.1 bans "hidden, dormant, or             */
/* undocumented features", and 2.5.2 bans downloading code that "introduces or  */
/* changes features or functionality". The line App Review actually enforces is */
/* about the app becoming something review never saw. Concretely, never OTA:    */
/*   • the paywall logic, the price, or anything in licensing/                  */
/*   • a feature that was hidden at submission and switched on afterwards       */
/*   • anything requiring a native capability the installed binary lacks        */
/* Bug fixes, copy, levels, content, VO, layout — those are the point.          */
/*                                                                             */
/* Play's Device and Network Abuse policy draws the same line: interpreted code */
/* in a WebView is fine, dex/native payloads are not.                           */
/*                                                                             */
/* SELF-HOSTED. No Capgo cloud account; the plugin points at our own manifest.  */
/* The server contract is one GET returning either 204 (nothing new) or:        */
/*   { "version": "0.2.0", "url": "https://…/0.2.0.zip",                        */
/*     "checksum": "<sha256>", "minNative": "1.3.0" }                           */
/* Serve it from the same origin as the bundle, over HTTPS, immutable per       */
/* version. `minNative` is the guard below.                                     */
/* -------------------------------------------------------------------------- */

declare const __APP_VERSION__: string;

interface UpdateManifest {
  version: string;
  url: string;
  checksum: string;
  /** Lowest native shell that can run this bundle. See the guard below. */
  minNative?: string;
}

function manifestUrl(): string | undefined {
  return import.meta.env.VITE_UPDATE_URL as string | undefined;
}

/** Semver-ish compare, enough for `a.b.c` release tags. */
export function versionAtLeast(have: string, need: string): boolean {
  const h = have.split(".").map(Number);
  const n = need.split(".").map(Number);
  for (let i = 0; i < Math.max(h.length, n.length); i++) {
    const a = h[i] ?? 0;
    const b = n[i] ?? 0;
    if (a !== b) return a > b;
  }
  return true;
}

/**
 * Refuse a bundle that needs a newer shell.
 *
 * This is the guard that stops the one genuinely dangerous failure mode: a JS
 * bundle calling a plugin the installed binary does not have, which is a white
 * screen on a child's tablet with no way back. When it trips, the fix is a
 * store release, not an OTA.
 */
function compatible(m: UpdateManifest, nativeVersion: string): boolean {
  return !m.minNative || versionAtLeast(nativeVersion, m.minNative);
}

async function checkOnce(): Promise<void> {
  const url = manifestUrl();
  if (!url) return;

  const res = await fetch(url, { credentials: "omit" });
  if (res.status === 204 || !res.ok) return;
  const manifest = (await res.json()) as UpdateManifest;

  const { bundle } = await CapacitorUpdater.current();
  if (bundle.version === manifest.version) return;

  const nativeVersion = (await CapApp.getInfo().catch(() => null))?.version ?? "0.0.0";
  if (!compatible(manifest, nativeVersion)) return;

  const next = await CapacitorUpdater.download({
    url: manifest.url,
    version: manifest.version,
    checksum: manifest.checksum,
  });
  // Land it on the NEXT launch, never mid-session: swapping the bundle under a
  // child who is halfway through a round is the worst possible moment.
  await CapacitorUpdater.next({ id: next.id });
}

/**
 * Start the update loop. Checks on boot and on each resume; every failure is
 * swallowed, because a failed update check must be indistinguishable from a
 * normal launch to the family using the app.
 */
export function installLiveUpdates(): void {
  try {
    if (!Capacitor.isNativePlatform() || !manifestUrl()) return;
  } catch {
    return;
  }

  // Tell the plugin the current bundle booted successfully. Without this it
  // rolls back to the last known-good one — which is exactly what we want if a
  // bad bundle ever crashes before this line runs.
  void CapacitorUpdater.notifyAppReady().catch(() => {});

  const run = () => {
    void checkOnce().catch((e) => reportError(e, "live-update"));
  };
  run();
  void CapApp.addListener("appStateChange", ({ isActive }) => {
    if (isActive) run();
  }).catch(() => {});
}

/** The web bundle version this build was cut from. */
export const bundleVersion = __APP_VERSION__;
