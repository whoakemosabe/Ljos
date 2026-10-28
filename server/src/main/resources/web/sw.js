// Ljós service worker.
// - The phone app opens instantly from its saved copy, which refreshes in the background.
// - Forecasts come from the network, with a 4 s limit before falling back to the last good copy
//   (the page says how old it is).
// - A new version installs quietly and waits; the phone page offers "Update", the lobby screen
//   takes it straight away (nobody is there to tap).
// - A page that was never saved, with no network, gets offline.html instead of a browser error.
// Bump VERSION when shell files change.
const VERSION = "ljos-v37";
const DATA = "ljos-data";
// Only what the phone app needs; the lobby's files are saved the first time a lobby screen loads.
const SHELL = ["/", "/style.css", "/app.js", "/aurora.js", "/skylab.js", "/skylab.css", "/map.js", "/glasspane.js",
  "/offline.html", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-180.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: "reload" })))));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k !== DATA).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") self.skipWaiting();
});

/** fetch() that gives up after [ms], so a connection that's "up" but stalled can't hang the app. */
function fetchWithin(request, ms) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timeout")), ms);
    fetch(request).then((r) => { clearTimeout(t); resolve(r); }, (e) => { clearTimeout(t); reject(e); });
  });
}

/** Saved copy straight away; the network refreshes it for next time. */
async function staleWhileRevalidate(event, request, cacheKey = request) {
  const cache = await caches.open(VERSION);
  const hit = await cache.match(cacheKey);
  const net = fetch(request)
    .then((res) => { if (res.ok) cache.put(cacheKey, res.clone()); return res; })
    .catch(() => null);
  if (hit) { event.waitUntil(net); return hit; }
  const res = await net;
  return res || (await caches.match("/offline.html")) || new Response("Offline", { status: 503 });
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== location.origin) return;

  // Forecasts: network first within 4 s, then the last good copy.
  if (url.pathname.startsWith("/v1/")) {
    event.respondWith((async () => {
      try {
        const res = await fetchWithin(request, 4000);
        if (res.ok) { const copy = res.clone(); event.waitUntil(caches.open(DATA).then((c) => c.put(request, copy))); }
        return res;
      } catch {
        const hit = await caches.match(request);
        if (hit) return hit;
        return fetch(request).catch(() => Response.error()); // first visit: wait for the network after all
      }
    })());
    return;
  }

  // Pages: the phone app is one saved page; every lobby address shares the lobby page.
  if (request.mode === "navigate") {
    const key = url.pathname.startsWith("/lobby") ? "/lobby.html" : url.pathname === "/" ? "/" : null;
    if (key) { event.respondWith(staleWhileRevalidate(event, request, key)); return; }
    event.respondWith(fetchWithin(request, 6000).catch(async () => (await caches.match("/offline.html")) || Response.error()));
    return;
  }

  // Everything else from this site (scripts, styles, fonts, icons): saved copy, refreshed quietly.
  event.respondWith(staleWhileRevalidate(event, request));
});
