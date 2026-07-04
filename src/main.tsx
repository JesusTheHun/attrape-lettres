import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { ProfileProvider } from "./hooks/useProfile";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ProfileProvider>
      <App />
    </ProfileProvider>
  </StrictMode>
);

// Register the offline service worker (built by scripts/gen-sw.mjs). Prod-only:
// there is no /sw.js in `vite dev`, so guarding on PROD avoids a 404 register.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    void navigator.serviceWorker
      .register(`${import.meta.env.BASE_URL}sw.js`)
      .catch(() => {
        /* offline unavailable this load; app still runs online */
      });
  });
}
