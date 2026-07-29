import type { CapacitorConfig } from "@capacitor/cli";

/**
 * Native shell config. The web layer is unchanged — `dist` is loaded verbatim
 * into WKWebView (iOS) / WebView (Android), which is also what makes JS-only
 * live updates legal: interpreted code run by the OS web engine sits inside the
 * DPLA §3.3.1(B) carve-out. Native code, plugins and anything that changes what
 * the app IS still ship through store review. See CLAUDE.md § Native.
 */
const config: CapacitorConfig = {
  appId: "fr.dappit.attrapelettres",
  appName: "Attrape-Lettres",
  webDir: "dist",
  // No dev server baked into a release build: everything is on-device, offline.
  // Kids play in the car, on planes, and in kitchens with bad wifi.
  loggingBehavior: "none",
  backgroundColor: "#FFE7C9",
  ios: {
    // The stage is a full-bleed gradient; let it run under the status bar and
    // handle the inset in CSS (env(safe-area-inset-*)) instead of letterboxing.
    contentInset: "never",
    // No rubber-band scroll. The whole app is a fixed board — bounce just looks
    // broken to a six-year-old dragging a tile.
    scrollEnabled: false,
    backgroundColor: "#FFE7C9",
  },
  android: {
    // Never ship a remotely-inspectable WebView.
    webContentsDebuggingEnabled: false,
    backgroundColor: "#FFE7C9",
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: false, // hidden from main.tsx once the roster is hydrated
      backgroundColor: "#FFE7C9",
      androidScaleType: "CENTER_CROP",
      showSpinner: false,
    },
    CapacitorUpdater: {
      // Self-hosted: no Capgo cloud account. src/updates.ts drives the whole
      // check/download/stage cycle against our own manifest (VITE_UPDATE_URL),
      // so the plugin must not also poll on its own.
      autoUpdate: false,
      // A bundle that never calls notifyAppReady() within this window is
      // treated as broken and rolled back. main.tsx calls it after first paint.
      appReadyTimeout: 10_000,
      responseTimeout: 20,
      // Reset to the store-shipped bundle if an update ever wedges the app.
      resetWhenUpdate: true,
    },
  },
};

export default config;
