// FuelFinder web — stessa logica di MainActivity.kt / SettingsDialog.kt dell'app Android.
(() => {
  "use strict";

  // ── Costanti (come in MainActivity.companion) ───────────────────────────────
  const DEFAULT_ZOOM = 13;
  const MAX_LOCATION_RETRIES = 5;
  const CORRIDOR_KM = 3.0;
  const AHEAD_MAX_ANGLE_DEG = 70.0;
  const MIN_SPEED_FOR_HEADING = 1.5;   // m/s: sotto questa velocità il browser dà heading poco affidabili
  const MIN_MOVE_FOR_BEARING_KM = 0.025;
  const API = "/api/distributori";
  const STORAGE_KEY = "fuelfinder.settings.v1";

  // ── Impostazioni (stessi default dell'app) ─────────────────────────────────
  const DEFAULTS = {
    lookAheadKm: 10, maxResults: 5,          // Percorso
    lookAheadKm360: 5, maxResults360: 20,    // 360°
    updateFrequencyMin: 1,
    alongRouteMode: false,
    fuelType: "gasolio",
  };
  let settings = loadSettings();

  // ── Stato ──────────────────────────────────────────────────────────────────
  let currentLocation = null;     // {lat, lon, heading, speed}
  let lastFixForBearing = null;
  let lastGoodBearingDeg = null;
  let isLiveSearchActive = false;
  let isCustomLocationMode = false;
  let customSearchPoint = null;
  let sortMode = "price";
  let currentStations = [];
  let updateTimer = null;
  let retryTimer = null;
  let locationRetryCount = 0;
  let firstFix = true;
  let wakeLock = null;
  let requestSeq = 0;

  // ── DOM ────────────────────────────────────────────────────────────────────
  const $ = (id) => document.getElementById(id);
  const el = {
    topCard: $("topCard"), modeToggle: $("modeToggle"), modeLabel: $("modeLabel"), btnSettings: $("btnSettings"),
    btnSearch: $("btnSearch"), btnExitCustom: $("btnExitCustom"), btnToggleList: $("btnToggleList"),
    customPin: $("customPin"), sheet: $("sheet"), sheetHandle: $("sheetHandle"), status: $("status"),
    list: $("stationList"), toast: $("toast"),
    stationDialog: $("stationDialog"), dLogo: $("dLogo"), dName: $("dName"), dAddress: $("dAddress"),
    dDistance: $("dDistance"), dUpdate: $("dUpdate"), dPrice: $("dPrice"), dNavigate: $("dNavigate"),
    settingsDialog: $("settingsDialog"),
  };

  // ── Mappa ──────────────────────────────────────────────────────────────────
  const map = L.map("map", { zoomControl: false, attributionControl: true }).setView([42.5, 12.5], 6);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);
  L.control.zoom({ position: "bottomleft" }).addTo(map);

  const clusterGroup = L.markerClusterGroup({
    showCoverageOnHover: false,
    spiderfyOnMaxZoom: true,
    disableClusteringAtZoom: 16,
    maxClusterRadius: 50,
    iconCreateFunction: (cluster) => {
      const n = cluster.getChildCount();
      const size = n < 10 ? 40 : n < 50 ? 48 : 56;
      const label = n >= 100 ? "99+" : String(n);
      return L.divIcon({
        html: `<span>${label}</span>`,
        className: "ff-cluster",
        iconSize: [size, size],
      });
    },
  });
  map.addLayer(clusterGroup);

  let userMarker = null;
  let customPointMarker = null;

  const userIcon = L.divIcon({ className: "ff-user", iconSize: [20, 20] });
  const pointIcon = L.divIcon({
    className: "",
    html: `<svg class="ff-point" viewBox="0 0 48 64"><path fill="#FB8C00" stroke="#E65100" stroke-width="2" d="M24,2 C13.5,2 5,10.5 5,21 C5,34 24,62 24,62 C24,62 43,34 43,21 C43,10.5 34.5,2 24,2 Z"/><circle cx="24" cy="22" r="8" fill="#fff"/></svg>`,
    iconSize: [32, 43], iconAnchor: [16, 43],
  });

  // ── Loghi brand (BrandLogoManager) ─────────────────────────────────────────
  const normalize = (s) => String(s).trim().toLowerCase().replace(/[-_.]/g, " ").replace(/\s+/g, " ");
  let brandNames = null;            // id -> nome
  const nameToId = new Map();       // nome normalizzato -> id
  const logoCache = new Map();

  fetch("/logos/brands.json").then((r) => r.json()).then((names) => {
    brandNames = names;
    for (const [id, name] of Object.entries(names)) nameToId.set(normalize(name), id);
    if (currentStations.length) renderStations(false);
  }).catch(() => {});

  function logoIdByName(brand) {
    if (!brand || !brandNames) return null;
    const key = normalize(brand);
    if (logoCache.has(key)) return logoCache.get(key);
    let id = nameToId.get(key) || null;
    if (!id) {
      for (const [n, i] of nameToId) {
        if (key.includes(n) || n.includes(key)) { id = i; break; }
      }
    }
    logoCache.set(key, id);
    return id;
  }
  function logoUrl(station, preferBandiera) {
    let id = null;
    if (preferBandiera && station.bandieraId && brandNames && brandNames[station.bandieraId]) id = String(station.bandieraId);
    if (!id) id = logoIdByName(station.brand);
    return id ? `/logos/${id}.webp` : null;
  }

  // ── Utility ────────────────────────────────────────────────────────────────
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  // Formato italiano, come String.format("%.3f") su un telefono in italiano
  const fmtNum = (n, d) => n.toLocaleString("it-IT", { minimumFractionDigits: d, maximumFractionDigits: d, useGrouping: false });
  const fmtPrice = (p) => `€ ${fmtNum(p, 3)}`;
  const fmtKm = (k) => `${fmtNum(k, 1)} km`;
  const fuelLabel = (f) => ({ gasolio: "Diesel", benzina: "Benzina", gpl: "GPL", metano: "Metano" }[f] || f);

  let toastTimer = null;
  function showToast(msg) {
    el.toast.textContent = msg;
    el.toast.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.toast.classList.remove("show"), 2500);
  }

  function getCurrentTime() {
    return new Date().toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" });
  }

  // "dd/MM/yyyy HH:mm:ss" (ora italiana)
  function parseMiseDate(s) {
    const m = /^(\d{2})\/(\d{2})\/(\d{4})\s+(\d{2}):(\d{2}):(\d{2})$/.exec(s || "");
    if (!m) return null;
    return new Date(+m[3], +m[2] - 1, +m[1], +m[4], +m[5], +m[6]);
  }
  // Lista: come StationAdapter ("Aggiornato Xh fa" / "Xgg fa")
  function listUpdateText(s) {
    const d = parseMiseDate(s);
    if (!d) return "";
    const hours = Math.floor((Date.now() - d.getTime()) / 3600000);
    if (hours < 0) return "";
    return hours < 24 ? `Aggiornato ${hours}h fa` : `Aggiornato ${Math.floor(hours / 24)}gg fa`;
  }
  // Dialog: come MainActivity.humanizeAge
  function formatRelativeUpdate(s) {
    const d = parseMiseDate(s);
    if (!d) return "n/d";
    const m = Math.floor((Date.now() - d.getTime()) / 60000);
    if (m < 0) return "n/d";
    if (m < 1) return "pochi secondi fa";
    if (m < 2) return "1 minuto fa";
    if (m < 60) return `${m} minuti fa`;
    if (m / 60 < 2) return "1 ora fa";
    if (m / 60 < 24) return `${Math.floor(m / 60)} ore fa`;
    if (m / 1440 < 2) return "1 gg fa";
    if (m / 1440 < 7) return `${Math.floor(m / 1440)} gg fa`;
    if (m / 10080 < 2) return "1 sett fa";
    if (m / 10080 < 5) return `${Math.floor(m / 10080)} sett fa`;
    if (m / 43200 < 2) return "1 mese fa";
    if (m / 43200 < 12) return `${Math.floor(m / 43200)} mesi fa`;
    return `${Math.floor(m / 525600)} anni fa`;
  }

  // ── Geometria (identica a MainActivity) ────────────────────────────────────
  const toRad = (d) => (d * Math.PI) / 180;
  const toDeg = (r) => (r * 180) / Math.PI;
  function haversineKm(lat1, lon1, lat2, lon2) {
    const r = 6371.0, dLat = toRad(lat2 - lat1), dLon = toRad(lon2 - lon1);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
  const bearingToUnitVector = (b) => [Math.sin(toRad(b)), Math.cos(toRad(b))];
  function toLocalENKm(o, p) {
    const lat0 = toRad(o.lat), r = 6371.0;
    return [toRad(p.lon - o.lon) * r * Math.cos(lat0), toRad(p.lat - o.lat) * r];
  }
  function projectToDirectionKm(o, p, dir) { const [e, n] = toLocalENKm(o, p); return e * dir[0] + n * dir[1]; }
  function crossTrackKm(o, p, dir) { const [e, n] = toLocalENKm(o, p); return Math.abs(e * -dir[1] + n * dir[0]); }
  function normalizeAngleDeg(a) { let x = a; while (x > 180) x -= 360; while (x < -180) x += 360; return x; }
  function angleToPointDeg(o, p, b) { const [e, n] = toLocalENKm(o, p); return Math.abs(normalizeAngleDeg(toDeg(Math.atan2(e, n)) - b)); }
  function bearingBetween(a, b) {
    const [e, n] = toLocalENKm(a, b);
    return (toDeg(Math.atan2(e, n)) + 360) % 360;
  }

  // ── Impostazioni persistenti ───────────────────────────────────────────────
  function loadSettings() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) return { ...DEFAULTS, ...JSON.parse(raw) };
    } catch (_) {}
    return { ...DEFAULTS };
  }
  function saveSettings() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); } catch (_) {}
  }

  // ── Bottom sheet ───────────────────────────────────────────────────────────
  const isWide = () => window.matchMedia("(min-width: 900px)").matches;
  function fitSheetHeight() {
    const cardBottom = el.topCard.getBoundingClientRect().bottom + (isWide() ? 12 : 24);
    const h = Math.max(window.innerHeight - cardBottom - (isWide() ? 16 : 0), 200);
    el.sheet.style.height = `${h}px`;
  }
  const openSheet = () => el.sheet.classList.add("open");
  const hideSheet = () => el.sheet.classList.remove("open");
  const sheetOpen = () => el.sheet.classList.contains("open");
  window.addEventListener("resize", fitSheetHeight);
  fitSheetHeight();

  // Trascina verso il basso la maniglia per chiudere
  (() => {
    let startY = null;
    const zone = [el.sheetHandle, el.sheet.querySelector(".sheet-bar")];
    zone.forEach((z) => {
      z.addEventListener("touchstart", (e) => { startY = e.touches[0].clientY; }, { passive: true });
      z.addEventListener("touchend", (e) => {
        if (startY !== null && e.changedTouches[0].clientY - startY > 60) hideSheet();
        startY = null;
      });
    });
    el.sheetHandle.addEventListener("click", hideSheet);
  })();

  // ── Posizione ──────────────────────────────────────────────────────────────
  function startLocationUpdates() {
    if (!("geolocation" in navigator)) { showToast("Geolocalizzazione non supportata da questo browser"); return; }
    navigator.geolocation.watchPosition(onPosition, onPositionError, {
      enableHighAccuracy: true, maximumAge: 2000, timeout: 20000,
    });
  }
  function onPositionError(err) {
    if (err.code === err.PERMISSION_DENIED) showToast("Permesso posizione necessario per il funzionamento dell'app");
  }
  function onPosition(pos) {
    const c = pos.coords;
    const loc = { lat: c.latitude, lon: c.longitude, heading: c.heading, speed: c.speed };
    locationRetryCount = 0;

    // Direzione: heading del dispositivo se in movimento, altrimenti dagli ultimi spostamenti
    if (loc.heading != null && !Number.isNaN(loc.heading) && (loc.speed ?? 0) >= MIN_SPEED_FOR_HEADING) {
      lastGoodBearingDeg = loc.heading;
    } else if (lastFixForBearing && haversineKm(lastFixForBearing.lat, lastFixForBearing.lon, loc.lat, loc.lon) >= MIN_MOVE_FOR_BEARING_KM) {
      lastGoodBearingDeg = bearingBetween(lastFixForBearing, loc);
    }
    if (!lastFixForBearing || haversineKm(lastFixForBearing.lat, lastFixForBearing.lon, loc.lat, loc.lon) >= MIN_MOVE_FOR_BEARING_KM) {
      lastFixForBearing = loc;
    }

    currentLocation = loc;
    const ll = [loc.lat, loc.lon];
    if (!userMarker) {
      userMarker = L.marker(ll, { icon: userIcon, zIndexOffset: 1000, title: "La tua posizione" }).addTo(map);
    } else {
      userMarker.setLatLng(ll);
    }
    if (firstFix) {
      firstFix = false;
      if (!isCustomLocationMode) map.flyTo(ll, DEFAULT_ZOOM, { duration: 0.8 });
    }
    if (isLiveSearchActive && currentStations.length) updateAirDistancesOnly();
  }

  // ── Modalità punto personalizzato ──────────────────────────────────────────
  function enterCustomLocationMode() {
    isCustomLocationMode = true;
    el.modeLabel.textContent = "Posizione manuale";
    el.customPin.classList.remove("hidden");
    el.btnExitCustom.classList.remove("hidden");
    if (isLiveSearchActive) stopLiveSearch();
    setSearchFab("manual");
    hideSheet();
  }

  function exitCustomLocationMode() {
    isCustomLocationMode = false;
    customSearchPoint = null;
    if (customPointMarker) { map.removeLayer(customPointMarker); customPointMarker = null; }
    el.modeLabel.textContent = "GPS";
    el.customPin.classList.add("hidden");
    el.btnExitCustom.classList.add("hidden");
    setSearchFab("idle");
    if (currentLocation) map.flyTo([currentLocation.lat, currentLocation.lon], DEFAULT_ZOOM, { duration: 0.8 });
    clearResults();
  }

  function setSearchFab(state) {
    el.btnSearch.classList.toggle("live", state === "live");
    el.btnSearch.classList.toggle("manual", state === "manual");
    el.btnSearch.querySelector("use").setAttribute("href", state === "live" ? "#i-stop" : "#i-search");
    el.btnSearch.setAttribute("aria-label",
      state === "live" ? "Ferma ricerca" : state === "manual" ? "Cerca qui" : "Avvia ricerca");
  }

  function clearResults() {
    clusterGroup.clearLayers();
    currentStations = [];
    el.list.innerHTML = "";
    el.status.textContent = "In attesa ricerca…";
    hideSheet();
  }

  // ── Chiamata API ───────────────────────────────────────────────────────────
  async function fetchStations(lat, lon, radiusKm) {
    const url = `${API}?latitude=${lat.toFixed(6)}&longitude=${lon.toFixed(6)}&distance=${radiusKm}&fuel=${encodeURIComponent(settings.fuelType)}&results=200`;
    const r = await fetch(url, { headers: { Accept: "application/json" } });
    if (!r.ok) { const e = new Error("api"); e.status = r.status; throw e; }
    const body = await r.json();
    return Array.isArray(body) ? body : [];
  }

  function toStations(body, origin, radiusKm) {
    const out = [];
    for (const dto of body) {
      const lat = dto.latitudine, lon = dto.longitudine, price = dto.prezzo;
      if (lat == null || lon == null || price == null) continue;
      const dist = haversineKm(origin.lat, origin.lon, lat, lon);
      if (dist > radiusKm) continue;
      out.push({
        id: `${dto.ranking ?? 0}_${lat}_${lon}`,
        name: dto.gestore || "Distributore",
        brand: dto.gestore || "",
        address: dto.indirizzo || "Indirizzo non disponibile",
        lat, lon, price,
        airDistanceKm: dist,
        lastUpdate: dto.data || null,
        bandieraId: dto.bandieraId ?? null,
      });
    }
    return out;
  }

  // ── Ricerca da punto personalizzato ────────────────────────────────────────
  async function searchFromCustomPoint(point) {
    el.status.textContent = "Ricerca in corso…";
    openSheet();
    if (customPointMarker) map.removeLayer(customPointMarker);
    customPointMarker = L.marker([point.lat, point.lon], { icon: pointIcon, title: "Punto di ricerca", zIndexOffset: 900 }).addTo(map);

    const seq = ++requestSeq;
    const radius = settings.lookAheadKm360;
    try {
      const body = await fetchStations(point.lat, point.lon, radius);
      if (seq !== requestSeq) return;
      if (!body.length) { el.status.textContent = "Nessun distributore trovato"; showEmpty(); return; }
      const stations = toStations(body, point, radius)
        .sort((a, b) => a.airDistanceKm - b.airDistanceKm)
        .slice(0, settings.maxResults360);
      updateStationDisplay(stations);
      el.status.textContent = `Punto personalizzato · ${stations.length} trovati · raggio ${radius}km`;
    } catch (e) {
      if (seq !== requestSeq) return;
      handleSearchError(e);
    }
  }

  // ── Ricerca live (GPS) ─────────────────────────────────────────────────────
  function toggleLiveSearch() { isLiveSearchActive ? stopLiveSearch() : startLiveSearch(); }

  function startLiveSearch() {
    if (!currentLocation) {
      showToast("Attendo la posizione GPS…");
      if (locationRetryCount < MAX_LOCATION_RETRIES) {
        locationRetryCount++;
        clearTimeout(retryTimer);
        retryTimer = setTimeout(startLiveSearch, 2000);
      } else {
        showToast("Impossibile ottenere la posizione GPS");
        locationRetryCount = 0;
      }
      return;
    }
    isLiveSearchActive = true;
    setSearchFab("live");
    openSheet();
    searchAndUpdate();
    scheduleUpdates();
    requestWakeLock();
  }

  function scheduleUpdates() {
    clearInterval(updateTimer);
    updateTimer = setInterval(() => { if (isLiveSearchActive) searchAndUpdate(); }, settings.updateFrequencyMin * 60000);
  }

  function stopLiveSearch() {
    isLiveSearchActive = false;
    clearInterval(updateTimer); updateTimer = null;
    clearTimeout(retryTimer);
    requestSeq++;
    setSearchFab(isCustomLocationMode ? "manual" : "idle");
    clearResults();
    releaseWakeLock();
  }

  async function searchAndUpdate() {
    const loc = currentLocation;
    if (!loc) return;
    el.status.textContent = "Ricerca in corso…";
    const route = settings.alongRouteMode;
    const activeRadius = route ? settings.lookAheadKm : settings.lookAheadKm360;
    const activeMax = route ? settings.maxResults : settings.maxResults360;
    const seq = ++requestSeq;
    try {
      const body = await fetchStations(loc.lat, loc.lon, activeRadius);
      if (seq !== requestSeq || !isLiveSearchActive) return;
      if (!body.length) { el.status.textContent = "Nessun distributore trovato"; showEmpty(); return; }
      const raw = toStations(body, loc, activeRadius);
      const finalList = route
        ? filterStationsAlongDirectionWithFallback(raw, loc)
        : raw.sort((a, b) => a.airDistanceKm - b.airDistanceKm).slice(0, activeMax);
      updateStationDisplay(finalList);
      el.status.textContent = `Trovati: ${finalList.length} (${route ? "Percorso" : "360°"}) • Max: ${activeRadius}km • ${getCurrentTime()}`;
    } catch (e) {
      if (seq !== requestSeq) return;
      handleSearchError(e);
    }
  }

  function handleSearchError(e) {
    if (e && e.status) {
      el.status.textContent = `Errore API: ${e.status}`;
    } else {
      el.status.textContent = "Errore rete";
      showToast("Errore di connessione");
    }
  }

  function filterStationsAlongDirectionWithFallback(all, loc) {
    const bearing = lastGoodBearingDeg;
    if (bearing == null) {
      showToast("Direzione non disponibile: mostro risultati a 360°");
      return all;
    }
    const o = { lat: loc.lat, lon: loc.lon };
    const dir = bearingToUnitVector(bearing);
    const out = all.filter((st) => {
      const p = { lat: st.lat, lon: st.lon };
      const proj = projectToDirectionKm(o, p, dir);
      const cross = crossTrackKm(o, p, dir);
      const angle = angleToPointDeg(o, p, bearing);
      return !(proj <= 0.5 || proj > settings.lookAheadKm || cross > CORRIDOR_KM || angle > AHEAD_MAX_ANGLE_DEG);
    });
    out.sort((a, b) => projectToDirectionKm(o, a, dir) - projectToDirectionKm(o, b, dir));
    return out.slice(0, settings.maxResults);
  }

  // ── Visualizzazione ────────────────────────────────────────────────────────
  function updateStationDisplay(stations) {
    currentStations = stations;
    renderStations(true);
  }

  function renderStations(fitBounds) {
    clusterGroup.clearLayers();
    for (const st of currentStations) {
      const m = L.marker([st.lat, st.lon], { icon: stationIcon(st), title: st.name });
      m.on("click", () => showNavigationDialog(st));
      clusterGroup.addLayer(m);
    }
    applySortAndRefresh();

    if (fitBounds && currentStations.length) {
      const pts = currentStations.map((s) => [s.lat, s.lon]);
      const center = customSearchPoint || (currentLocation && !isCustomLocationMode ? currentLocation : null);
      if (center) pts.push([center.lat, center.lon]);
      const wide = isWide();
      map.fitBounds(L.latLngBounds(pts), {
        paddingTopLeft: [wide && sheetOpen() ? el.sheet.offsetWidth + 60 : 60, el.topCard.getBoundingClientRect().bottom + 40],
        paddingBottomRight: [90, !wide && sheetOpen() ? Math.min(el.sheet.offsetHeight, window.innerHeight * 0.45) : 60],
        maxZoom: 16,
      });
    }
  }

  function stationIcon(st) {
    const url = logoUrl(st, true);
    const logo = url ? `<img src="${url}" alt="">` : `<span class="fallback">⛽</span>`;
    return L.divIcon({
      className: "",
      html: `<div class="ff-pin"><div class="box"><div class="price">${fmtNum(st.price, 3)} €</div><div class="logo">${logo}</div></div><div class="tip"></div></div>`,
      iconSize: [56, 70],
      iconAnchor: [28, 70],
    });
  }

  function applySortAndRefresh() {
    const sorted = [...currentStations].sort((a, b) =>
      sortMode === "price" ? a.price - b.price : a.airDistanceKm - b.airDistanceKm);
    if (!sorted.length) { el.list.innerHTML = ""; return; }
    el.list.innerHTML = sorted.map((s, i) => {
      const url = logoUrl(s, false);
      return `
      <div class="station" data-i="${i}">
        <div class="st-main">
          <div class="st-head">
            ${url ? `<img class="st-logo" src="${url}" alt="">` : ""}
            <div>
              <div class="st-name">${esc(s.name)}</div>
              <div class="st-sub">${esc(s.address)}</div>
            </div>
          </div>
          <div class="st-sub">📍 ${fmtKm(s.airDistanceKm)}</div>
          <div class="st-small">${esc(listUpdateText(s.lastUpdate))}</div>
          <div class="st-price">${fmtPrice(s.price)}</div>
        </div>
        <button class="btn-go" data-go="${i}"><svg><use href="#i-nav"/></svg>VAI</button>
      </div>`;
    }).join("");
    el.list._sorted = sorted;
  }

  function showEmpty() {
    clusterGroup.clearLayers();
    currentStations = [];
    el.list.innerHTML = `<div class="empty">Nessun distributore trovato per ${esc(fuelLabel(settings.fuelType))}.<br>Prova ad aumentare il raggio nelle impostazioni.</div>`;
  }

  function updateAirDistancesOnly() {
    const loc = currentLocation;
    if (!loc) return;
    for (const s of currentStations) s.airDistanceKm = haversineKm(loc.lat, loc.lon, s.lat, s.lon);
    applySortAndRefresh();
  }

  el.list.addEventListener("click", (e) => {
    const sorted = el.list._sorted || [];
    const go = e.target.closest("[data-go]");
    if (go) { navigateToStation(sorted[+go.dataset.go]); return; }
    const card = e.target.closest(".station");
    if (card) focusStationOnMap(sorted[+card.dataset.i]);
  });

  function focusStationOnMap(st) {
    if (!st) return;
    hideSheet();
    map.flyTo([st.lat, st.lon], 16, { duration: 0.6 });
  }

  function navigateToStation(st) {
    if (!st) return;
    const url = `https://www.google.com/maps/dir/?api=1&destination=${st.lat},${st.lon}&travelmode=driving`;
    window.open(url, "_blank", "noopener");
  }

  // ── Dialog distributore ────────────────────────────────────────────────────
  let dialogStation = null;
  function showNavigationDialog(st) {
    dialogStation = st;
    const url = logoUrl(st, true);
    el.dLogo.classList.toggle("hidden", !url);
    if (url) el.dLogo.src = url;
    el.dName.textContent = st.name;
    el.dAddress.textContent = st.address;
    el.dAddress.classList.toggle("hidden", !st.address);
    el.dDistance.textContent = st.airDistanceKm > 0 ? fmtKm(st.airDistanceKm) : "";
    el.dDistance.classList.toggle("hidden", !(st.airDistanceKm > 0));
    el.dUpdate.textContent = st.lastUpdate ? formatRelativeUpdate(st.lastUpdate) : "";
    el.dUpdate.classList.toggle("hidden", !st.lastUpdate);
    el.dPrice.textContent = fmtPrice(st.price);
    el.stationDialog.classList.remove("hidden");
  }
  el.dNavigate.addEventListener("click", () => { el.stationDialog.classList.add("hidden"); navigateToStation(dialogStation); });
  el.stationDialog.addEventListener("click", (e) => { if (e.target === el.stationDialog) el.stationDialog.classList.add("hidden"); });

  // ── Dialog impostazioni (SettingsDialog.kt) ────────────────────────────────
  const sd = {
    title: $("sTitle"), fuelChips: $("fuelChips"), radiusLabel: $("radiusLabel"),
    radius: $("radiusSlider"), radiusValue: $("radiusValue"), max: $("maxSlider"), maxValue: $("maxValue"),
    route: $("swAlongRoute"), routeLabel: $("routeLabel"), routeInfo: $("routeInfo"),
    realInfo: $("realDistanceInfo"), cancel: $("sCancel"), ok: $("sOk"),
  };
  let draft = null;

  function openSettings() {
    draft = { ...settings };
    sd.title.textContent = draft.alongRouteMode ? "Impostazioni · Percorso" : "Impostazioni · 360°";
    sd.fuelChips.querySelectorAll(".chip").forEach((c) => setChip(c, c.dataset.fuel === draft.fuelType));
    document.querySelectorAll('input[name="freq"]').forEach((r) => { r.checked = +r.value === draft.updateFrequencyMin; });
    if (![1, 3, 5].includes(draft.updateFrequencyMin)) document.querySelector('input[name="freq"][value="1"]').checked = true;
    sd.route.checked = draft.alongRouteMode;
    applyRouteMode(draft.alongRouteMode);
    el.settingsDialog.classList.remove("hidden");
  }

  function setChip(c, on) { c.classList.toggle("checked", on); c.setAttribute("aria-checked", on ? "true" : "false"); }

  function applyRouteMode(isRoute) {
    sd.radiusLabel.textContent = isRoute ? "Prossimi km di viaggio" : "Raggio di ricerca";
    sd.routeLabel.textContent = isRoute ? "Modalità percorso" : "Modalità 360°";
    sd.routeInfo.textContent = isRoute
      ? "Cerca distributori lungo la tua direzione di marcia"
      : "Cerca distributori in tutte le direzioni intorno alla posizione attuale (360°)";
    if (isRoute) {
      setRange(sd.radius, 5, 50, 5);
      draft.lookAheadKm = clamp(Math.round(draft.lookAheadKm / 5) * 5, 5, 50);
      sd.radius.value = draft.lookAheadKm;
      setRange(sd.max, 1, 10, 1);
      draft.maxResults = clamp(draft.maxResults, 1, 10);
      sd.max.value = draft.maxResults;
      sd.realInfo.textContent = "Distanza stradale non disponibile nella versione web: uso la linea d'aria";
    } else {
      setRange(sd.radius, 1, 10, 1);
      draft.lookAheadKm360 = clamp(draft.lookAheadKm360, 1, 10);
      sd.radius.value = draft.lookAheadKm360;
      setRange(sd.max, 5, 200, 5);
      draft.maxResults360 = clamp(Math.round(draft.maxResults360 / 5) * 5, 5, 200);
      sd.max.value = draft.maxResults360;
      sd.realInfo.textContent = "In modalità 360° è disponibile solo la distanza in linea d'aria";
    }
    sd.radiusValue.textContent = `${sd.radius.value} km`;
    sd.maxValue.textContent = `${sd.max.value}`;
  }
  const clamp = (v, a, b) => Math.min(Math.max(v, a), b);
  function setRange(input, min, max, step) { input.min = min; input.max = max; input.step = step; }

  sd.fuelChips.addEventListener("click", (e) => {
    const c = e.target.closest(".chip");
    if (!c) return;
    draft.fuelType = c.dataset.fuel;
    sd.fuelChips.querySelectorAll(".chip").forEach((x) => setChip(x, x === c));
  });
  sd.route.addEventListener("change", () => { draft.alongRouteMode = sd.route.checked; applyRouteMode(draft.alongRouteMode); });
  sd.radius.addEventListener("input", () => {
    const v = +sd.radius.value;
    if (draft.alongRouteMode) draft.lookAheadKm = v; else draft.lookAheadKm360 = v;
    sd.radiusValue.textContent = `${v} km`;
  });
  sd.max.addEventListener("input", () => {
    const v = +sd.max.value;
    if (draft.alongRouteMode) draft.maxResults = v; else draft.maxResults360 = v;
    sd.maxValue.textContent = `${v}`;
  });
  document.querySelectorAll('input[name="freq"]').forEach((r) =>
    r.addEventListener("change", () => { if (r.checked) draft.updateFrequencyMin = +r.value; }));

  sd.cancel.addEventListener("click", () => el.settingsDialog.classList.add("hidden"));
  sd.ok.addEventListener("click", () => {
    settings = { ...draft };
    saveSettings();
    el.settingsDialog.classList.add("hidden");
    showToast("Impostazioni salvate");
    if (isCustomLocationMode) {
      if (customSearchPoint) searchFromCustomPoint(customSearchPoint);
    } else if (isLiveSearchActive) {
      searchAndUpdate();
      scheduleUpdates();
    }
  });

  // ── Wake lock: lo schermo resta acceso durante la ricerca live ─────────────
  async function requestWakeLock() {
    try {
      if ("wakeLock" in navigator && !wakeLock) {
        wakeLock = await navigator.wakeLock.request("screen");
        wakeLock.addEventListener("release", () => { wakeLock = null; });
      }
    } catch (_) { wakeLock = null; }
  }
  function releaseWakeLock() { try { wakeLock && wakeLock.release(); } catch (_) {} wakeLock = null; }
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && isLiveSearchActive) {
      requestWakeLock();
      searchAndUpdate();   // aggiorna subito al rientro nell'app
    }
  });

  // ── Listener principali ────────────────────────────────────────────────────
  el.btnSettings.addEventListener("click", openSettings);
  el.modeToggle.addEventListener("click", () => (isCustomLocationMode ? exitCustomLocationMode() : enterCustomLocationMode()));
  el.btnSearch.addEventListener("click", () => {
    if (isCustomLocationMode) {
      const c = map.getCenter();
      customSearchPoint = { lat: c.lat, lon: c.lng };
      searchFromCustomPoint(customSearchPoint);
    } else {
      toggleLiveSearch();
    }
  });
  el.btnExitCustom.addEventListener("click", exitCustomLocationMode);
  el.btnToggleList.addEventListener("click", () => (sheetOpen() ? hideSheet() : openSheet()));
  document.querySelector(".sheet-bar .chips").addEventListener("click", (e) => {
    const c = e.target.closest(".chip");
    if (!c) return;
    sortMode = c.dataset.sort;
    document.querySelectorAll(".sheet-bar .chip").forEach((x) => setChip(x, x === c));
    applySortAndRefresh();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") { el.stationDialog.classList.add("hidden"); el.settingsDialog.classList.add("hidden"); }
  });

  // ── Avvio ──────────────────────────────────────────────────────────────────
  startLocationUpdates();

  if ("serviceWorker" in navigator) {
    window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
  }
})();
