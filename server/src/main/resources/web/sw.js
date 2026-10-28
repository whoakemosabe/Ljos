// Ljós service worker: the app shell works offline, and the last forecast is shown when the
// network is down (the page says how old it is). Bump VERSION when shell files change.
const VERSION = "ljos-v2";
const SHELL = ["/", "/style.css", "/app.js", "/aurora.js", "/fraunces.woff2", "/schibsted-grotesk.woff2", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-180.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k !== "ljos-data").map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin) return;

  // Forecasts: network first, last good copy when offline.
  if (url.pathname.startsWith("/v1/")) {
    event.respondWith(
      fetch(event.request)
        .then((res) => {
          if (res.ok) { const copy = res.clone(); caches.open("ljos-data").then((c) => c.put(event.request, copy)); }
          return res;
        })
        .catch(() => caches.match(event.request).then((hit) => hit || Response.error())),
    );
    return;
  }

  // Pages: every route is the same page.
  if (event.request.mode === "navigate") {
    event.respondWith(fetch(event.request).catch(() => caches.match("/")));
    return;
  }

  // Shell files: cached copy straight away, refreshed in the background.
  event.respondWith(
    caches.match(event.request).then((hit) => {
      const net = fetch(event.request).then((res) => {
        if (res.ok) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(event.request, copy)); }
        return res;
      }).catch(() => hit);
      return hit || net;
    }),
  );
});
