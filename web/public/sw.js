// FuelFinder service worker
// - interfaccia (HTML/CSS/JS/librerie): cache, aggiornata in background
// - loghi: cache al primo uso
// - API prezzi e tile della mappa: sempre dalla rete (i prezzi devono essere freschi)

const VERSION = "ff-v3";
const SHELL = [
  "/",
  "/app.css",
  "/app.js",
  "/manifest.webmanifest",
  "/vendor/leaflet.css",
  "/vendor/leaflet.js",
  "/vendor/MarkerCluster.css",
  "/vendor/leaflet.markercluster.js",
  "/logos/brands.json",
  "/icons/icon-192.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;   // tile OSM: rete
  if (url.pathname.startsWith("/api/")) return;        // prezzi: rete

  // Loghi: cache-first
  if (url.pathname.startsWith("/logos/") && url.pathname.endsWith(".webp")) {
    event.respondWith(
      caches.match(req).then((hit) => hit || fetch(req).then((res) => {
        if (res.ok) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(req, copy)); }
        return res;
      }))
    );
    return;
  }

  // Interfaccia: stale-while-revalidate (navigazioni servite da "/")
  const key = req.mode === "navigate" ? "/" : req;
  event.respondWith(
    caches.match(key).then((hit) => {
      const net = fetch(req).then((res) => {
        if (res.ok) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(key, copy)); }
        return res;
      }).catch(() => hit);
      return hit || net;
    })
  );
});
