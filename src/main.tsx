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
