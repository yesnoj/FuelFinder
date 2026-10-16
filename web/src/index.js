// FuelFinder — Worker proxy per fuelfinder.frankprochilo.com
//
// Espone la stessa API che usa l'app Android:
//   GET /api/distributori?latitude=..&longitude=..&distance=..&fuel=..&results=..
// e la inoltra al wrapper prezzi-carburante (dati MISE), aggiungendo:
//   - CORS, così la PWA può chiamarla dal browser
//   - cache di qualche minuto per zona, che assorbe anche il "cold start" di Render
//   - validazione dei parametri
//
// Endpoint di servizio:
//   GET /api/health

const UPSTREAM = "https://prezzi-carburante.onrender.com/api/distributori";

const FUELS = new Set(["gasolio", "benzina", "gpl", "metano"]);

const CACHE_TTL_SEC = 600;         // 10 minuti: i prezzi MISE non cambiano più spesso di così
const GRID = 100;                  // arrotonda le coordinate a 0.01° (~1 km) per condividere la cache
const GRID_MARGIN_KM = 1;          // compensa lo spostamento del centro dovuto all'arrotondamento
const MAX_DISTANCE_KM = 50;        // stesso limite dell'app Android
const UPSTREAM_TIMEOUT_MS = 60000; // Render free può impiegare decine di secondi a svegliarsi

// Riquadro largo attorno all'Italia: scarta coordinate chiaramente sbagliate
const LAT_MIN = 35, LAT_MAX = 48, LON_MIN = 6, LON_MAX = 19;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (url.pathname === "/api/health") {
      return json({
        ok: true,
        time: new Date().toISOString(),
        distanzeStradali: Boolean(env && env.GOOGLE_MAPS_KEY),
      });
    }

    if (url.pathname === "/api/distributori") {
      if (request.method !== "GET") {
        return json({ error: "Metodo non consentito" }, 405);
      }
      return handleDistributori(url, ctx);
    }

    if (url.pathname === "/api/luoghi") {
      if (request.method !== "GET") {
        return json({ error: "Metodo non consentito" }, 405);
      }
      return handleLuoghi(url, ctx);
    }

    if (url.pathname === "/api/distanze") {
      if (request.method !== "GET") {
        return json({ error: "Metodo non consentito" }, 405);
      }
      return handleDistanze(url, request, env, ctx);
    }

    return json({ error: "Not found" }, 404);
  },
};

// ── Ricerca località (Nominatim / OpenStreetMap) ─────────────────────────────
//
//   GET /api/luoghi?q=Bologna
//   → [{ name, detail, lat, lon }, ...] (max 5, solo Italia)
//
// Usato sia dalla PWA sia dall'app Android. Regole d'uso di Nominatim:
// User-Agent che identifica l'app, niente ricerca "mentre scrivi" (si cerca
// solo all'invio), cache lunga perché i luoghi non cambiano.

const NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
const LUOGHI_CACHE_TTL_SEC = 7 * 24 * 3600;

async function handleLuoghi(url, ctx) {
  const q = (url.searchParams.get("q") ?? "").trim().replace(/\s+/g, " ");
  if (q.length < 2 || q.length > 120) {
    return json({ error: "Scrivi da 2 a 120 caratteri" }, 400);
  }

  const cacheKey = new Request(
    `https://fuelfinder.frankprochilo.com/__cache/luoghi/${encodeURIComponent(q.toLowerCase())}`
  );
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return withCors(cached, "HIT");

  const nUrl = new URL(NOMINATIM_URL);
  nUrl.searchParams.set("q", q);
  nUrl.searchParams.set("format", "jsonv2");
  nUrl.searchParams.set("countrycodes", "it");
  nUrl.searchParams.set("limit", "5");
  nUrl.searchParams.set("accept-language", "it");

  let data;
  try {
    const r = await fetch(nUrl.toString(), {
      headers: {
        "User-Agent": "FuelFinder/1.0 (+https://fuelfinder.frankprochilo.com)",
        "Referer": "https://fuelfinder.frankprochilo.com/",
        "Accept": "application/json",
      },
      signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) return json({ error: "Errore dal servizio località", upstreamStatus: r.status }, 502);
    data = await r.json();
  } catch {
    return json({ error: "Servizio località non raggiungibile" }, 502);
  }
  if (!Array.isArray(data)) return json({ error: "Formato inatteso dal servizio località" }, 502);

  const out = data
    .map((p) => {
      const lat = parseFloat(p.lat), lon = parseFloat(p.lon);
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
      const parts = String(p.display_name ?? "").split(",").map((s) => s.trim()).filter(Boolean);
      const name = p.name || parts[0] || q;
      // Dettaglio leggibile: le parti successive al nome, senza "Italia" e CAP
      const detail = parts
        .filter((s) => s !== name && s !== "Italia" && !/^\d{5}$/.test(s))
        .slice(0, 3)
        .join(", ");
      return { name, detail, lat, lon };
    })
    .filter(Boolean);

  const response = new Response(JSON.stringify(out), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${LUOGHI_CACHE_TTL_SEC}`,
    },
  });
  if (out.length) ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return withCors(response, "MISS");
}

// ── Distanze stradali (Google Distance Matrix) ────────────────────────────────
//
//   GET /api/distanze?origin=lat,lon&dest=lat,lon|lat,lon|...
//   → [{ distanceKm, durationMin } | null, ...] nello stesso ordine di dest
//
// La chiave Google sta solo nel segreto GOOGLE_MAPS_KEY del Worker (dashboard
// Cloudflare → fuelfinder → Settings → Variables and secrets), mai nel codice.
// Ogni chiamata costa crediti Google, quindi:
//   - solo richieste dal sito FuelFinder (controllo Origin/Referer/Sec-Fetch-Site)
//   - massimo MAX_DESTINATIONS destinazioni per richiesta (come l'app Android)
//   - risultati in cache per qualche minuto, per origine arrotondata a ~100 m

const DM_URL = "https://maps.googleapis.com/maps/api/distancematrix/json";
const MAX_DESTINATIONS = 10;
const DM_CACHE_TTL_SEC = 300;
const SITE_ORIGINS = new Set(["https://fuelfinder.frankprochilo.com"]);

function isFromOwnSite(request, url) {
  const allowed = new Set([...SITE_ORIGINS, url.origin]);
  const origin = request.headers.get("Origin");
  if (origin) return allowed.has(origin);
  const referer = request.headers.get("Referer");
  if (referer) {
    try { return allowed.has(new URL(referer).origin); } catch { return false; }
  }
  return request.headers.get("Sec-Fetch-Site") === "same-origin";
}

function parsePoint(s) {
  const parts = String(s ?? "").split(",");
  if (parts.length !== 2) return null;
  const lat = parseFloat(parts[0]), lon = parseFloat(parts[1]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) return null;
  return { lat, lon };
}

async function handleDistanze(url, request, env, ctx) {
  const key = env && env.GOOGLE_MAPS_KEY;
  if (!key) {
    return json({ error: "Distanze stradali non configurate", configured: false }, 503);
  }
  if (!isFromOwnSite(request, url)) {
    return json({ error: "Richiesta non consentita" }, 403);
  }

  const origin = parsePoint(url.searchParams.get("origin"));
  const dests = (url.searchParams.get("dest") ?? "").split("|").filter(Boolean).map(parsePoint);
  if (!origin) return json({ error: "origin non valido" }, 400);
  if (!dests.length || dests.some((d) => !d)) return json({ error: "dest non valido" }, 400);
  if (dests.length > MAX_DESTINATIONS) {
    return json({ error: `Massimo ${MAX_DESTINATIONS} destinazioni per richiesta` }, 400);
  }

  // Origine arrotondata a 3 decimali (~100 m): poco errore, molta più cache condivisa
  const o = `${origin.lat.toFixed(3)},${origin.lon.toFixed(3)}`;
  const d = dests.map((p) => `${p.lat.toFixed(5)},${p.lon.toFixed(5)}`).join("|");

  const cacheKey = new Request(
    `https://fuelfinder.frankprochilo.com/__cache/distanze/${encodeURIComponent(o)}/${encodeURIComponent(d)}`
  );
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return withCors(cached, "HIT");

  const dmUrl = new URL(DM_URL);
  dmUrl.searchParams.set("origins", o);
  dmUrl.searchParams.set("destinations", d);
  dmUrl.searchParams.set("mode", "driving");
  dmUrl.searchParams.set("language", "it");
  dmUrl.searchParams.set("key", key);

  let data;
  try {
    const r = await fetch(dmUrl.toString(), { signal: AbortSignal.timeout(15000) });
    if (!r.ok) return json({ error: "Errore dal servizio distanze", upstreamStatus: r.status }, 502);
    data = await r.json();
  } catch {
    return json({ error: "Servizio distanze non raggiungibile" }, 502);
  }

  if (data.status !== "OK") {
    // Non inoltro error_message: potrebbe contenere dettagli sulla chiave
    return json({ error: "Distanze non disponibili", googleStatus: data.status }, 502);
  }

  const elements = (data.rows && data.rows[0] && data.rows[0].elements) || [];
  const out = dests.map((_, i) => {
    const e = elements[i];
    if (!e || e.status !== "OK" || !e.distance || !e.duration) return null;
    return {
      distanceKm: Math.round(e.distance.value / 100) / 10,
      durationMin: Math.round(e.duration.value / 60),
    };
  });

  const response = new Response(JSON.stringify(out), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${DM_CACHE_TTL_SEC}`,
    },
  });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return withCors(response, "MISS");
}

async function handleDistributori(url, ctx) {
  const p = url.searchParams;

  const lat = parseFloat(p.get("latitude"));
  const lon = parseFloat(p.get("longitude"));
  const distance = parseInt(p.get("distance") ?? "10", 10);
  const fuel = (p.get("fuel") ?? "").toLowerCase();
  const results = Math.min(parseInt(p.get("results") ?? "200", 10) || 200, 200);

  // --- Validazione ---
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return json({ error: "latitude e longitude sono obbligatori" }, 400);
  }
  if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) {
    return json({ error: "Coordinate fuori dall'Italia" }, 400);
  }
  if (!Number.isFinite(distance) || distance < 1 || distance > MAX_DISTANCE_KM) {
    return json({ error: `distance deve essere tra 1 e ${MAX_DISTANCE_KM} km` }, 400);
  }
  if (!FUELS.has(fuel)) {
    // Promemoria: il MISE vuole "gasolio", non "diesel"
    return json({ error: `fuel deve essere uno tra: ${[...FUELS].join(", ")}` }, 400);
  }

  // --- Chiave di cache per zona ---
  // Il centro viene arrotondato, quindi il raggio viene allargato di un margine:
  // il client filtra comunque per distanza reale dal suo punto esatto (come fa già l'app).
  const snapLat = Math.round(lat * GRID) / GRID;
  const snapLon = Math.round(lon * GRID) / GRID;
  const upstreamDistance = distance + GRID_MARGIN_KM;

  const cacheKey = new Request(
    `https://fuelfinder.frankprochilo.com/__cache/distributori/${fuel}/${snapLat}/${snapLon}/${upstreamDistance}/${results}`
  );
  const cache = caches.default;

  const cached = await cache.match(cacheKey);
  if (cached) {
    return withCors(cached, "HIT");
  }

  // --- Chiamata al wrapper ---
  const upstreamUrl = new URL(UPSTREAM);
  upstreamUrl.searchParams.set("latitude", String(snapLat));
  upstreamUrl.searchParams.set("longitude", String(snapLon));
  upstreamUrl.searchParams.set("distance", String(upstreamDistance));
  upstreamUrl.searchParams.set("fuel", fuel);
  upstreamUrl.searchParams.set("results", String(results));

  let upstreamResp;
  try {
    upstreamResp = await fetch(upstreamUrl.toString(), {
      headers: {
        "Accept": "application/json",
        "User-Agent": "FuelFinder-Web/1.0 (+https://fuelfinder.frankprochilo.com)",
      },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch (e) {
    const isTimeout = e && (e.name === "TimeoutError" || e.name === "AbortError");
    return json(
      { error: isTimeout ? "Il servizio prezzi non ha risposto in tempo" : "Servizio prezzi non raggiungibile" },
      isTimeout ? 504 : 502
    );
  }

  if (!upstreamResp.ok) {
    return json({ error: "Errore dal servizio prezzi", upstreamStatus: upstreamResp.status }, 502);
  }

  let data;
  try {
    data = await upstreamResp.json();
  } catch {
    return json({ error: "Risposta non valida dal servizio prezzi" }, 502);
  }
  if (!Array.isArray(data)) {
    return json({ error: "Formato inatteso dal servizio prezzi" }, 502);
  }

  const response = new Response(JSON.stringify(data), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${CACHE_TTL_SEC}`,
    },
  });

  // Salva in cache senza far aspettare il client.
  // Nota: la Cache API funziona sul dominio personalizzato, non su *.workers.dev.
  // Le liste vuote non vengono messe in cache: potrebbero essere un errore temporaneo a monte.
  if (data.length > 0) {
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
  }

  return withCors(response, "MISS");
}

// --- Helper ---

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Accept",
    "Access-Control-Max-Age": "86400",
  };
}

function withCors(response, cacheStatus) {
  const r = new Response(response.body, response);
  for (const [k, v] of Object.entries(corsHeaders())) r.headers.set(k, v);
  r.headers.set("X-Cache", cacheStatus);
  return r;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...corsHeaders(),
    },
  });
}
