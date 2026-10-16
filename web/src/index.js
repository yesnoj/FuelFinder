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
      return json({ ok: true, time: new Date().toISOString() });
    }

    if (url.pathname === "/api/distributori") {
      if (request.method !== "GET") {
        return json({ error: "Metodo non consentito" }, 405);
      }
      return handleDistributori(url, ctx);
    }

    return json({ error: "Not found" }, 404);
  },
};

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
