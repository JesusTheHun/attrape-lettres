import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Capacitor } from "@capacitor/core";
import { SplashScreen } from "@capacitor/splash-screen";
import App from "./App";
import { ProfileProvider } from "./hooks/useProfile";
import { EntitlementProvider } from "./licensing/useEntitlement";
import { hydrateKv } from "./kv";
import { installErrorReporting } from "./telemetry";
import { installLiveUpdates } from "./updates";
import "./index.css";

function isNative(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}

/**
 * Boot order matters. On native, device storage is async, so the roster has to
 * be in memory BEFORE React mounts — `useState(initialRoster)` cannot await.
 * The splash screen stays up for exactly that window (launchAutoHide: false in
 * capacitor.config.ts), so a child never sees an empty "Qui joue ?" flash while
 * their profiles load.
 */
async function boot(): Promise<void> {
  installErrorReporting();
  await hydrateKv();

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <EntitlementProvider>
        <ProfileProvider>
          <App />
        </ProfileProvider>
      </EntitlementProvider>
    </StrictMode>
  );

  if (isNative()) {
    void SplashScreen.hide().catch(() => {
      /* already hidden */
    });
    // After the first paint: notifies the updater this bundle booted fine (so a
    // bad one rolls back), then checks the self-hosted manifest.
    installLiveUpdates();
  }
}

void boot();

// Offline service worker (built by scripts/gen-sw.mjs) — WEB ONLY.
//
// Never on native: the bundle is already on disk there, and a SW that caches
// code would fight the live-update mechanism for control of which version runs.
// One updater per platform, and on native that is the store plus the OTA
// channel. Prod-only on web too, since `vite dev` serves no /sw.js.
if (import.meta.env.PROD && !isNative() && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    void navigator.serviceWorker
      .register(`${import.meta.env.BASE_URL}sw.js`)
      .catch(() => {
        /* offline unavailable this load; app still runs online */
      });
  });
}
