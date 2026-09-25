import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { ApiProvider } from "./api/ApiContext";
import "./index.css";
import "./fx/fx.css";

const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("#root element not found");

createRoot(rootElement).render(
  <StrictMode>
    <BrowserRouter>
      <ApiProvider>
        <App />
      </ApiProvider>
    </BrowserRouter>
  </StrictMode>,
);

// PWA app-shell service worker (task item 3) — production builds only: registering it during
// `npm run dev` would fight Vite's own HMR by caching modules it expects to hot-swap. Relative
// path/scope so it works under any deploy sub-path.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("sw.js", { scope: "./" }).catch(() => {
      // Best-effort — a failed registration (e.g. unsupported browser quirk) shouldn't affect
      // the app itself, which works the same with or without the service worker.
    });
  });
}
