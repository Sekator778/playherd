// Herd service worker (task item 3 — PWA). Caches ONLY the app shell (this build's index.html,
// manifest, icons, and whichever hashed JS/CSS under `assets/` the browser actually requests) so
// the app still opens offline/on a flaky connection. Deliberately never touches `/api/*` — every
// API response must always be live, never served from a cache — the explicit requirement behind
// this file even existing rather than just precaching everything Workbox-style.
const CACHE_NAME = "herd-shell-v1";
const SHELL_PATHS = ["./", "index.html", "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_PATHS))
      .catch(() => {
        // Best-effort precache — a single missing/opaque response shouldn't fail install and
        // leave the app with no service worker at all.
      }),
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  );
});

function isApiRequest(url) {
  // Matches both `/api/...` (Docker/nginx build, task item 5's proxy) and a dev-time absolute
  // API origin some future setup might use — the path segment check is what actually matters.
  return url.pathname.includes("/api/");
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return; // never intercept writes (bets, sign-in, …)

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // never cache cross-origin requests
  if (isApiRequest(url)) return; // never cache /api/* — always live, no exceptions

  if (request.mode === "navigate") {
    // Always try the network first for a page load — only fall back to the cached shell when
    // genuinely offline, so a signed-in player never gets a stale logged-out shell by accident.
    event.respondWith(
      fetch(request).catch(() => caches.match("index.html").then((res) => res || Response.error())),
    );
    return;
  }

  // Stale-while-revalidate for the shell's static assets (hashed JS/CSS/icons): serve the cached
  // copy immediately if there is one, refresh it in the background either way.
  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      return cached || network;
    }),
  );
});
