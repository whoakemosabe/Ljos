// The location map in "Where to go", mirroring the app's SpotMap (ui/Map.kt, data/MapTiles.kt):
// a dark Esri basemap with its label layer (OpenStreetMap, darkened, if Esri is down), centred
// on home, with each place as a pin in its score colour, its score beside it, a slow ring on
// the best one, and the green home pin in the middle. Tapping a pin selects it.

const NS = "http://www.w3.org/2000/svg";
const ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas";
const ATTR_ESRI = "Esri, HERE, Garmin, © OpenStreetMap";
const ATTR_OSM = "© OpenStreetMap contributors";

/** Position in "world pixels" at [zoom], 256 px per tile. */
function project(lat, lon, zoom) {
  const n = 256 * 2 ** zoom;
  const r = (lat * Math.PI) / 180;
  return [((lon + 180) / 360) * n, ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n];
}

function km(aLat, aLon, bLat, bLon) {
  const R = 6371, d = Math.PI / 180;
  const x = Math.sin(((bLat - aLat) * d) / 2) ** 2 + Math.cos(aLat * d) * Math.cos(bLat * d) * Math.sin(((bLon - aLon) * d) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(Math.min(1, x)));
}

/** Zoom (fractional, so the places fill the map) that fits [radiusKm] in half the map height. */
function zoomFor(lat, radiusKm, halfHeight) {
  const z = Math.log2((156543.03 * Math.cos((lat * Math.PI) / 180) * halfHeight) / (radiusKm * 1000 * 1.2));
  return Math.max(5, Math.min(12, z));
}

const svgEl = (name, attrs) => {
  const n = document.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
  return n;
};

let built = null; // { key, w, h, zoom, tiles, pins, attr }

/**
 * Draws the map into [box]. [home] {lat, lon}; [spots] [{id, lat, lon}] (home first);
 * [scores] Map id → score; [colorFor](score) → CSS colour.
 */
export function renderMap(box, { home, spots, scores, bestId, selectedId, onSelect, colorFor }) {
  const w = Math.round(box.clientWidth), h = Math.round(box.clientHeight);
  if (!w || !h) return;
  const radiusKm = Math.max(8, ...spots.map((s) => km(home.lat, home.lon, s.lat, s.lon)));
  const zoom = zoomFor(home.lat, radiusKm, h / 2);
  const key = `${home.lat.toFixed(4)},${home.lon.toFixed(4)},${zoom.toFixed(3)},${w}x${h}`;
  const [cx, cy] = project(home.lat, home.lon, zoom);
  const at = (lat, lon) => { const [x, y] = project(lat, lon, zoom); return [x - cx + w / 2, y - cy + h / 2]; };

  if (!built || built.key !== key || !box.contains(built.tiles)) {
    box.replaceChildren();
    const tiles = document.createElement("div");
    tiles.className = "map-tiles";
    // Tiles from the next whole level up (one more on dense screens), scaled down to fit, so they stay crisp.
    const z = Math.min(13, Math.ceil(zoom) + (devicePixelRatio > 1.5 ? 1 : 0)), n = 2 ** z;
    const side = 256 * 2 ** (zoom - z);
    const left = cx - w / 2, top = cy - h / 2;
    const x0 = Math.floor(left / side), x1 = Math.floor((left + w) / side);
    const y0 = Math.floor(top / side), y1 = Math.floor((top + h) / side);
    let esriOk = true;
    const attr = document.createElement("p");
    attr.className = "map-attr";
    attr.textContent = ATTR_ESRI;
    for (let x = x0; x <= x1; x++) {
      for (let y = y0; y <= y1; y++) {
        if (y < 0 || y >= n) continue;
        const wx = ((x % n) + n) % n;
        const place = (img) => { img.style.cssText = `left:${(x * side - left).toFixed(2)}px;top:${(y * side - top).toFixed(2)}px;width:${(side + 1).toFixed(2)}px;height:${(side + 1).toFixed(2)}px`; };
        const base = new Image();
        base.alt = "";
        base.decoding = "async";
        base.className = "map-tile";
        place(base);
        base.onload = () => base.classList.add("in");
        base.onerror = () => {
          // Esri down: OpenStreetMap, inverted and cooled to read as a night map.
          esriOk = false;
          attr.textContent = ATTR_OSM;
          base.onerror = null;
          base.classList.add("osm");
          base.src = `https://tile.openstreetmap.org/${z}/${wx}/${y}.png`;
          label.remove();
        };
        base.src = `${ESRI}/World_Dark_Gray_Base/MapServer/tile/${z}/${y}/${wx}`;
        const label = new Image();
        label.alt = "";
        label.decoding = "async";
        label.className = "map-tile labels";
        place(label);
        label.onload = () => label.classList.add("in");
        label.onerror = () => label.remove();
        if (esriOk) label.src = `${ESRI}/World_Dark_Gray_Reference/MapServer/tile/${z}/${y}/${wx}`;
        tiles.append(base, label);
      }
    }
    const shade = document.createElement("div");
    shade.className = "map-shade";
    const pins = svgEl("svg", { class: "map-pins", width: w, height: h, viewBox: `0 0 ${w} ${h}`, role: "img", "aria-label": "Map of the places to go" });
    pins.addEventListener("click", (e) => {
      const r = pins.getBoundingClientRect();
      const tx = e.clientX - r.left, ty = e.clientY - r.top;
      let hit = null, best = 32;
      for (const s of built.spots) {
        const [px, py] = at(s.lat, s.lon);
        const d = Math.hypot(px - tx, py - ty);
        if (d < best) { best = d; hit = s; }
      }
      if (hit) built.onSelect?.(hit.id);
    });
    box.append(tiles, shade, pins, attr);
    built = { key, tiles, pins };
  }
  built.spots = spots.slice(1);
  built.onSelect = onSelect;

  // Pins, redrawn on every render (scores and selection change).
  const g = built.pins;
  g.replaceChildren();
  for (const s of spots.slice(1)) {
    const [px, py] = at(s.lat, s.lon);
    const score = scores.get(s.id);
    const col = score != null ? colorFor(score) : "#2fd3c6";
    const sel = s.id === selectedId;
    if (s.id === bestId) g.append(svgEl("circle", { class: "map-ring", cx: px, cy: py, r: 8, stroke: col }));
    g.append(
      svgEl("circle", { cx: px, cy: py, r: sel ? 13 : 10, fill: col, "fill-opacity": sel ? 0.42 : 0.22, class: "map-halo" }),
      svgEl("circle", { cx: px, cy: py, r: sel ? 5.5 : 4, fill: col }),
    );
    if (score != null) {
      const t = svgEl("text", { x: px + 9, y: py, fill: col, class: "map-score", "dominant-baseline": "central" });
      t.textContent = String(score);
      g.append(t);
    } else g.append(svgEl("circle", { cx: px, cy: py, r: 1.4, fill: "#0a1322" }));
  }
  // Home: a green pin in the middle.
  const hx = w / 2, hy = h / 2, headY = hy - 15;
  g.append(
    svgEl("circle", { cx: hx, cy: hy, r: 24, fill: "#3dffa0", "fill-opacity": 0.14 }),
    svgEl("circle", { cx: hx, cy: hy + 1, r: 3, fill: "#000", "fill-opacity": 0.4 }),
    svgEl("path", { d: `M${hx} ${hy} L${hx - 7.5} ${headY + 3} L${hx + 7.5} ${headY + 3} Z`, fill: "#3dffa0" }),
    svgEl("circle", { cx: hx, cy: headY, r: 8.5, fill: "#3dffa0" }),
    svgEl("circle", { cx: hx, cy: headY, r: 3.2, fill: "#0a1322" }),
  );
}
