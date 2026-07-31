import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { ProfileProvider } from "./hooks/useProfile";
import { EntitlementProvider } from "./licensing/useEntitlement";
import { installErrorReporting } from "./telemetry";
import "./index.css";

installErrorReporting();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <EntitlementProvider>
      <ProfileProvider>
        <App />
      </ProfileProvider>
    </EntitlementProvider>
  </StrictMode>
);

// Offline service worker (built by scripts/gen-sw.mjs).
//
// Prod-only: `vite dev` serves no /sw.js. This is the whole update story for
// the web build — the phones are native apps now and update through their
// stores, so nothing here has to coordinate with an OTA channel.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    void navigator.serviceWorker
      .register(`${import.meta.env.BASE_URL}sw.js`)
      .catch(() => {
        /* offline unavailable this load; app still runs online */
      });
  });
}
