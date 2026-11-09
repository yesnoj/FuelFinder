// FuelFinder — Worker di fuelfinder.frankprochilo.com
//
//   GET /api/distributori?latitude=..&longitude=..&distance=..&fuel=..&results=..
//       Distributori vicini con il prezzo del carburante scelto, stessa risposta
//       del vecchio wrapper prezzi-carburante (usata da sito e app Android).
//       I dati vengono dai CSV ufficiali del MIMIT: il workflow GitHub
//       "Dati prezzi MIMIT" li prepara ogni giorno in tile da 0,5° nel branch
//       dati-prezzi del repository, e qui si leggono solo i tile vicini.
//   GET /api/luoghi?q=...        ricerca località (Nominatim)
//   GET /api/distanze?...        distanze stradali (Google Distance Matrix)
//   GET /api/health              stato del servizio e data dei dati

const DATA_BASE = "https://raw.githubusercontent.com/yesnoj/FuelFinder/dati-prezzi";
const TILE_DEG = 0.5;
const TILE_CACHE_SEC = 3600;       // i dati cambiano una volta al giorno
const RESPONSE_CACHE_SEC = 300;
const MAX_PRICE_AGE_DAYS = 15;     // come il wrapper: prezzi più vecchi non sono affidabili

const FUELS = new Set(["gasolio", "benzina", "gpl", "metano"]);
const MAX_DISTANCE_KM = 50;        // stesso limite dell'app Android
const MAX_RESULTS = 200;

// Riquadro largo attorno all'Italia: scarta coordinate chiaramente sbagliate
const LAT_MIN = 35, LAT_MAX = 48, LON_MIN = 6, LON_MAX = 19;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (url.pathname === "/api/health") {
      const meta = await fetchData("meta.json");
      return json({
        ok: true,
        time: new Date().toISOString(),
        distanzeStradali: Boolean(env && env.GOOGLE_MAPS_KEY),
        datiPrezzi: meta ? {
          estrazione: meta.estrazionePrezzi,
          generato: meta.generato,
          impianti: meta.impiantiConPrezzi,
        } : null,
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
  const distance = parseFloat(p.get("distance") ?? "10");
  const fuel = (p.get("fuel") ?? "").toLowerCase();
  const limit = Math.min(Math.max(parseInt(p.get("results") ?? "", 10) || MAX_RESULTS, 1), MAX_RESULTS);

  // --- Validazione ---
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return json({ error: "latitude e longitude sono obbligatori" }, 400);
  }
  if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) {
    return json({ error: "Coordinate fuori dall'Italia" }, 400);
  }
  if (!Number.isFinite(distance) || distance <= 0 || distance > MAX_DISTANCE_KM) {
    return json({ error: `distance deve essere tra 1 e ${MAX_DISTANCE_KM} km` }, 400);
  }
  if (!FUELS.has(fuel)) {
    // Promemoria: il MIMIT usa "gasolio", non "diesel"
    return json({ error: `fuel deve essere uno tra: ${[...FUELS].join(", ")}` }, 400);
  }

  // --- Tile che coprono il cerchio di ricerca ---
  const dLat = distance / 111.2;
  const dLon = distance / (111.2 * Math.cos((lat * Math.PI) / 180));
  const keys = [];
  for (let i = Math.floor((lat - dLat) / TILE_DEG); i <= Math.floor((lat + dLat) / TILE_DEG); i++) {
    for (let j = Math.floor((lon - dLon) / TILE_DEG); j <= Math.floor((lon + dLon) / TILE_DEG); j++) {
      keys.push(`${i}_${j}`);
    }
  }

  const tiles = await Promise.all(keys.map((k) => fetchData(`tiles/${k}.json`, true)));
  if (tiles.every((t) => t === null)) {
    return json({ error: "Dati prezzi non disponibili, riprova tra poco" }, 503);
  }

  // --- Filtro: carburante, età del prezzo, distanza ---
  const notBefore = Date.now() / 1000 - MAX_PRICE_AGE_DAYS * 86400;
  const matches = [];
  for (const tile of tiles) {
    if (!tile || !Array.isArray(tile.s)) continue;
    for (const [id, gestore, nome, indirizzo, sLat, sLon, prezzi] of tile.s) {
      const pr = prezzi[fuel];
      if (!pr || pr[3] < notBefore) continue;
      const d = haversineKm(lat, lon, sLat, sLon);
      if (d > distance) continue;
      matches.push({ id, gestore, nome, indirizzo, sLat, sLon, pr, d });
    }
  }

  // Prezzo più basso prima, a parità di prezzo il più vicino (come il wrapper)
  matches.sort((a, b) => a.pr[0] - b.pr[0] || a.d - b.d);
  const out = matches.slice(0, limit).map((m, i) => ({
    ranking: i + 1,
    gestore: m.gestore,
    indirizzo: m.indirizzo,
    prezzo: m.pr[0],
    self: m.pr[1] === 1,
    data: m.pr[2],
    distanza: m.d.toFixed(2),
    latitudine: m.sLat,
    longitudine: m.sLon,
    id: m.id,
    nome: m.nome,
  }));

  return new Response(JSON.stringify(out), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${RESPONSE_CACHE_SEC}`,
      ...corsHeaders(),
    },
  });
}

// Legge un file dei dati prezzi (branch dati-prezzi), con cache sul bordo di Cloudflare.
// Restituisce l'oggetto JSON, {s: []} per un tile che non esiste (mare, estero) o null se
// il file non è raggiungibile.
async function fetchData(path, isTile = false) {
  try {
    const r = await fetch(`${DATA_BASE}/${path}`, {
      cf: { cacheTtl: TILE_CACHE_SEC, cacheEverything: true },
      signal: AbortSignal.timeout(10000),
    });
    if (r.status === 404 && isTile) return { s: [] };
    if (!r.ok) return null;
    return await r.json();
  } catch {
    return null;
  }
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const toRad = (x) => (x * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1), dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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

