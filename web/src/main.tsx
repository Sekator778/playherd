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
// `npm run dev` would fight Vite's own HMR by caching modules it expects to hot-swap.
// `import.meta.env.BASE_URL` mirrors `vite.config.ts`'s `base` at runtime ("/" for the real
// Docker/web build, "./" for a future local-asset Capacitor build) — a plain "./" here resolved
// against whatever path the CURRENT document happened to be loaded from, so a direct hard load of
// a nested route (e.g. `/round/<id>`) registered the worker at the wrong scope entirely instead
// of the app root (polish item 4, 2026-09-26).
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    const base = import.meta.env.BASE_URL;
    navigator.serviceWorker.register(`${base}sw.js`, { scope: base }).catch(() => {
      // Best-effort — a failed registration (e.g. unsupported browser quirk) shouldn't affect
      // the app itself, which works the same with or without the service worker.
    });
  });
}
