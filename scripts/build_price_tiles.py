#!/usr/bin/env python3
"""Prepara i dati prezzi di FuelFinder dai CSV ufficiali del MIMIT.

Scarica anagrafica impianti e prezzi (open data MIMIT), li unisce e li divide in
"tile" di 0,5° x 0,5° (circa 55 x 40 km), così il Worker Cloudflare a ogni ricerca
legge solo i pochi file intorno al punto cercato.

Stessa logica del wrapper prezzi-carburante (github.com/dstmrk/prezzi-carburante):
  - gestore = Bandiera (marchio), altrimenti Gestore, altrimenti Nome Impianto
  - indirizzo = Indirizzo + Comune + Provincia
  - per ogni carburante il prezzo più basso tra self e servito
  - scartati prezzi più vecchi di 15 giorni, datati nel futuro o fuori da
    [0,6x, 2x] la mediana nazionale del carburante (errori di inserimento)

Uso: python3 scripts/build_price_tiles.py OUTPUT_DIR [anagrafica.csv prezzi.csv]
(senza i due file li scarica dal MIMIT).
"""
import json, math, os, re, sys, time, urllib.request
from collections import defaultdict
from datetime import datetime, timezone, timedelta

ANAGRAFICA_URL = "https://www.mimit.gov.it/images/exportCSV/anagrafica_impianti_attivi.csv"
PREZZI_URL = "https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv"
USER_AGENT = "FuelFinder/1.0 (+https://fuelfinder.frankprochilo.com)"
FUELS = {"benzina", "gasolio", "gpl", "metano"}   # quelli usati da sito e app
TILE_DEG = 0.5
MAX_AGE_DAYS = 15
PLAUSIBLE = (0.6, 2.0)
DATE_RE = re.compile(r"^(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2}):(\d{2})$")
PREZZIBENZINA_RE = re.compile(r"\s*\|\s*gestori\.prezzibenzina\.it", re.I)


def download(url):
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"  tentativo {attempt + 1} fallito per {url}: {e}", file=sys.stderr)
            time.sleep(30 * (attempt + 1))
    raise SystemExit(f"Impossibile scaricare {url}: {last}")


def decode(raw):
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", "replace")


def parse_table(text, required, overflow=None):
    """CSV MIMIT: separatore '|', nessun quoting, intestazione dopo 'Estrazione del ...'."""
    lines = text.splitlines()
    extraction = None
    for i, line in enumerate(lines):
        if line.lower().startswith("estrazione del"):
            extraction = line.split()[-1]
            continue
        cells = [c.strip().lower() for c in line.split("|")]
        if all(r.lower() in cells for r in required):
            header, start = cells, i + 1
            break
    else:
        raise SystemExit(f"Intestazione non trovata, attese: {required}")
    ov = header.index(overflow.lower()) if overflow and overflow.lower() in header else -1
    rows = []
    for line in lines[start:]:
        if not line.strip():
            continue
        cells = line.split("|")
        if len(cells) > len(header) and ov >= 0:
            extra = len(cells) - len(header)
            cells = cells[:ov] + ["|".join(cells[ov:ov + extra + 1])] + cells[ov + extra + 1:]
        rows.append({h: (cells[j].strip() if j < len(cells) else "") for j, h in enumerate(header)})
    return rows, extraction


def num(s):
    try:
        return float(s.replace(",", "."))
    except ValueError:
        return math.nan


def rome_ts(s):
    """'DD/MM/YYYY HH:MM:SS' ora italiana -> epoch secondi (CET/CEST approssimato)."""
    m = DATE_RE.match(s.strip())
    if not m:
        return None
    d, mo, y, h, mi, se = map(int, m.groups())
    try:
        local = datetime(y, mo, d, h, mi, se)
    except ValueError:
        return None
    # ora legale UE: dall'ultima domenica di marzo all'ultima di ottobre
    def last_sunday(month):
        dt = datetime(y, month, 31 if month in (3, 10) else 30)
        return dt - timedelta(days=(dt.weekday() + 1) % 7)
    offset = 2 if last_sunday(3).replace(hour=2) <= local < last_sunday(10).replace(hour=3) else 1
    return int((local - timedelta(hours=offset)).replace(tzinfo=timezone.utc).timestamp())


def collapse(s):
    return re.sub(r"\s+", " ", s).strip()


def main():
    out_dir = sys.argv[1]
    if len(sys.argv) >= 4:
        raw_a, raw_p = open(sys.argv[2], "rb").read(), open(sys.argv[3], "rb").read()
    else:
        raw_a, raw_p = download(ANAGRAFICA_URL), download(PREZZI_URL)
    print(f"Scaricati: anagrafica {len(raw_a) // 1024} KB, prezzi {len(raw_p) // 1024} KB")

    anag, ext_a = parse_table(PREZZIBENZINA_RE.sub("", decode(raw_a)),
                              ["idImpianto", "Indirizzo", "Latitudine", "Longitudine"], "Nome Impianto")
    prez, ext_p = parse_table(decode(raw_p), ["idImpianto", "descCarburante", "prezzo", "isSelf", "dtComu"])

    stations = {}
    for r in anag:
        sid = r.get("idimpianto", "")
        lat, lon = num(r.get("latitudine", "")), num(r.get("longitudine", ""))
        # scarta coordinate mancanti o palesemente fuori dall'Italia
        if not sid or not (35 <= lat <= 48 and 6 <= lon <= 19):
            continue
        stations[sid] = {
            "gestore": r.get("bandiera") or r.get("gestore") or r.get("nome impianto", ""),
            "nome": collapse(r.get("nome impianto", "")),
            "indirizzo": collapse(" ".join(x for x in (r.get("indirizzo", ""), r.get("comune", ""), r.get("provincia", "")) if x)),
            "lat": lat, "lon": lon, "prezzi": {},
        }

    now = time.time()
    oldest, newest = now - MAX_AGE_DAYS * 86400, now + 86400
    candidates, samples = [], defaultdict(list)
    for r in prez:
        st = stations.get(r.get("idimpianto", ""))
        fuel = collapse(r.get("desccarburante", "")).lower()
        if not st or fuel not in FUELS:
            continue
        price = num(r.get("prezzo", ""))
        ts = rome_ts(r.get("dtcomu", ""))
        if not (price > 0) or ts is None or not (oldest <= ts <= newest):
            continue
        candidates.append((st, fuel, price, r.get("isself") == "1", r["dtcomu"].strip(), ts))
        samples[fuel].append(price)

    ranges = {}
    for fuel, vals in samples.items():
        if len(vals) >= 10:
            vals.sort()
            n = len(vals)
            med = vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2
            ranges[fuel] = (med * PLAUSIBLE[0], med * PLAUSIBLE[1])

    dropped = 0
    for st, fuel, price, self_, data, ts in candidates:
        lo, hi = ranges.get(fuel, (0, math.inf))
        if not (lo <= price <= hi):
            dropped += 1
            continue
        cur = st["prezzi"].get(fuel)
        if cur is None or price < cur[0]:
            st["prezzi"][fuel] = [price, 1 if self_ else 0, data, ts]

    tiles = defaultdict(list)
    with_prices = 0
    for sid, st in stations.items():
        if not st["prezzi"]:
            continue
        with_prices += 1
        key = f"{math.floor(st['lat'] / TILE_DEG)}_{math.floor(st['lon'] / TILE_DEG)}"
        tiles[key].append([int(sid) if sid.isdigit() else sid, st["gestore"], st["nome"], st["indirizzo"],
                           round(st["lat"], 6), round(st["lon"], 6), st["prezzi"]])

    os.makedirs(os.path.join(out_dir, "tiles"), exist_ok=True)
    total_bytes = 0
    for key, rows in tiles.items():
        rows.sort(key=lambda x: str(x[0]))
        data = json.dumps({"v": 1, "s": rows}, ensure_ascii=False, separators=(",", ":"))
        total_bytes += len(data.encode())
        with open(os.path.join(out_dir, "tiles", f"{key}.json"), "w", encoding="utf-8") as f:
            f.write(data)

    meta = {
        "v": 1,
        "tileDeg": TILE_DEG,
        "estrazioneAnagrafica": ext_a,
        "estrazionePrezzi": ext_p,
        "generato": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "impianti": len(stations),
        "impiantiConPrezzi": with_prices,
        "prezziScartati": dropped,
        "tile": len(tiles),
        "carburanti": {f: sum(1 for s in stations.values() if f in s["prezzi"]) for f in sorted(FUELS)},
    }
    with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print(json.dumps(meta, ensure_ascii=False))
    print(f"Totale tile: {total_bytes // 1024} KB")
    if with_prices < 10000:
        raise SystemExit(f"Troppi pochi impianti con prezzi ({with_prices}): non pubblico dati sospetti")


if __name__ == "__main__":
    main()
